# 发布流程

本文件描述一次完整发布需要做什么。脚本实现见 `tools/release.sh`。

## 环境分工

| 角色 | 机器 | 职责 |
| --- | --- | --- |
| 指挥 | 本机 | 下发命令、上传资产、触发工作流 |
| 编译 | `buildserver` | 编译、签名、LSPatch 集成、拆分（重负载） |
| 合并 | GitHub Actions | 分片拼接与整包上传 |

## 一、准备

1. 确认宿主版本与模块版本号
2. 宿主 APK 放到编译服务器：`/root/pipixia/pipixia-<版本>.apk`
3. 确认签名配置：`ppx-assistant/local.properties` 中的
   `storeFile` / `storePassword` / `keyAlias` / `keyPassword`

## 二、静态核对 Hook 目标（新增宿主版本时必做）

```sh
# 在编译服务器上导出目标 APK 的 dex dump
cd /tmp && rm -rf dexX && mkdir dexX && cd dexX
unzip -o -q /root/pipixia/pipixia-<版本>.apk "classes*.dex"
for f in classes*.dex; do
  /root/android-sdk/build-tools/36.0.0/dexdump -d "$f" > /tmp/dd_${f%.dex}.dex.txt
done

# 校验模块引用的 Hook 目标
cd /root/pipixia/ppx-assistant
python3 tools/verify-hooks.py --dump-dir /tmp --pattern "dd_*.dex.txt" \
    --targets tools/hook-targets.json
python3 tools/verify-hooks.py --dump-dir /tmp --pattern "dd_*.dex.txt" \
    --targets tools/hook-targets.generated.json --allow-missing
```

判定标准：

- 核心清单必须全部 `implemented`
- 全量清单允许 `missing`（宿主版本差异），但**不允许 `noop`**
  （`noop` 表示钩到了空方法，是真正的失效）

## 三、修改版本号

```sh
# app/build.gradle.kts
val verCode = <yyyyMMddNN>
val verName = "<x.y.z>"
```

同时更新：

- `app/src/main/java/com/akari/ppx/utils/VersionChecker.kt` 的 `supportedVersions`
- `README.md` 顶部的「最新版本」与兼容范围

## 四、编译与签名

```sh
rsync -az --exclude build --exclude .git --exclude .gradle --exclude local.properties \
    ./ buildserver:/root/pipixia/ppx-assistant/
ssh buildserver 'cd /root/pipixia/ppx-assistant && ./gradlew :app:assembleRelease'
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名校验：

```sh
/root/android-sdk/build-tools/36.0.0/apksigner verify --print-certs app-release.apk
```

期望证书：`CN=PPX`，SHA-256 `541d41bc…bcbc`

## 五、发布模块本体

```sh
gh release create v<x.y.z> app-release.apk#Piyou-Assistant-<x.y.z>.apk \
  --repo Daniaode/piyou-assistant \
  --title "<x.y.z> <一句话说明>" \
  --notes-file docs/release-notes/<x.y.z>.md
```

模块包约 2.8MB，直接上传即可，不需要分片。

## 六、制作集成版（可选，免 root 用户）

```sh
# 在编译服务器上：用 LSPatch 把模块内嵌进宿主并重签名
cd /root/pipixia
PW=$(grep '^storePassword=' ppx-assistant/local.properties | cut -d= -f2-)
KP=$(grep '^keyPassword='  ppx-assistant/local.properties | cut -d= -f2-)
[ -z "$KP" ] && KP="$PW"
rm -rf out<ver> && mkdir -p out<ver>
java -Xmx6g -jar lspatch.jar \
  -m ppx-assistant/app/build/outputs/apk/release/app-release.apk \
  -o out<ver> -f -k keystore.jks "$PW" ppx "$KP" pipixia-<版本>.apk
```

## 七、上传集成版（分片 + GitHub 侧合并）

> 跨境链路上传 100MB+ 单文件会被中途重置（实测 10~490 KB/s 波动、connection reset）。
> GitHub 单资产上限是 2GB，所以**不是大小限制**，而是链路问题。
> 结论：本地分片上传，GitHub 侧合并。

```sh
# 1) 分片（39MB 一段，实测成功率最高）
split -b 39M -d -a 1 "集成版.apk" gh.part
for f in gh.part*; do mv "$f" "$f.apk"; done

# 2) 逐片上传（失败只重传该片）
for p in gh.part*.apk; do
  until gh release upload v<x.y.z> "$p" --repo Daniaode/piyou-assistant --clobber; do
    sleep 15
  done
done

# 3) 触发合并工作流
gh workflow run merge-release-assets.yml --repo Daniaode/piyou-assistant \
  -f tag=v<x.y.z> \
  -f parts_prefix=gh.part \
  -f merged_name=Piyou-Integrated-<x.y.z>-<宿主版本>.apk \
  -f md5=<合并后的 MD5> \
  -f delete_parts=true

# 4) 确认结果
gh run list --repo Daniaode/piyou-assistant --workflow=merge-release-assets.yml --limit 1
gh release view v<x.y.z> --repo Daniaode/piyou-assistant --json assets --jq '.assets[] | "\(.name) \(.size)"'
```

工作流会：下载分片 → 按名排序拼接 → 校验 MD5 与 APK 结构 → 上传整包 → 删除分片。
**校验不通过不会上传**，避免发出损坏包。

## 八、收尾

```sh
# 源码与文档
git push origin main

# 确认线上状态
gh release view v<x.y.z> --repo Daniaode/piyou-assistant
curl -s https://raw.githubusercontent.com/Daniaode/piyou-assistant/main/README.md | head -5

# 清理
rm -f *.part*.apk /tmp/dd_*.dex.txt
ssh buildserver 'rm -rf /tmp/dexX /root/pipixia/out<ver>'
```

## 九、发布检查清单

- [ ] 静态核对：核心清单全过、全量无 `noop`
- [ ] 签名证书为 `CN=PPX`（SHA-256 `541d41bc…`）
- [ ] 模块包已挂到 Release
- [ ] 集成版分片全部上传成功
- [ ] 合并工作流 success，Release 上只剩**一个**集成版整包
- [ ] 合并包 MD5 与本地一致
- [ ] README / Release 说明已更新
- [ ] 临时文件已清理
