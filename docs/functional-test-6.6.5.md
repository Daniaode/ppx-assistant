# 皮皮虾 6.6.5 适配记录

- 日期：2026-11-28
- 宿主：皮皮虾 6.6.5（versionCode 665，arm64-v8a，35 个 dex，112MB）
- 模块：皮友助手 1.1.0
- 包名：`com.sup.android.superb`

## 一、适配方法

对 6.6.5 做**全量静态核对**，而不是逐个功能手测：

1. 从 APK 导出全部 35 个 dex，用 `dexdump` 生成 1.9GB 文本 dump
2. 解析出 **106,001 个类**的索引
3. 用 `tools/extract-hooks.py` 从模块源码提取 Hook 目标
4. 用 `tools/verify-hooks.py` 逐项校验：类是否存在、方法是否存在、是否有真实实现

```sh
# 1) 导出 dump（在编译服务器上执行）
unzip -o -q pipixia-6.6.5.apk "classes*.dex"
for f in classes*.dex; do dexdump -d "$f" > /tmp/dd665_${f%.dex}.dex.txt; done

# 2) 提取模块 Hook 目标
python3 tools/extract-hooks.py --src app/src/main/java \
    --out tools/hook-targets.generated.json

# 3) 校验（核心清单 / 全量清单）
python3 tools/verify-hooks.py --dump-dir /tmp --pattern "dd665_*.dex.txt" \
    --targets tools/hook-targets.json
python3 tools/verify-hooks.py --dump-dir /tmp --pattern "dd665_*.dex.txt" \
    --targets tools/hook-targets.generated.json --allow-missing
```

## 二、核对结果

| 项目 | 结果 |
| --- | --- |
| 核心 13 项 Hook | ✅ 全部通过 |
| 全量 31 项 Hook | 仅 `TTVideoEngine#onCompletion` 兜底缺失，主路径正常 |
| 红点相关目标 | ✅ `FeedTabFragmentV2`、`MyProfileHeaderLayout`、`MainActivity.handleBadgeAndPopup` 均存在 |
| 去广告 / 弹窗 / 红包引用类 | ✅ 全部存在 |

### 6.6.5 的破坏性变化

6.6.5 是一次大重构，安装包从 240MB 降到 112MB。以下**可读类名**已被移除：

- 下载 / 分享：`VideoDownloadHelper`、`VideoDownLoadConfig`、`IVideoDownloadLogCallback`、
  `DetailVideoDownloadHelper`、`ShareVideoDownloadHelper`、`FeedVideoDownloadHelper`、`ShareHelper`
- 金币：`MoneyExcitingUtil`、`IMoneyFinishTaskCallback`
- 悬浮控件：`PetFloatingWidget`、`BaseDurationView`

模块此前已针对这些位置写了**混淆名兜底**，逐个核实后 6.6.5 中均存在：

`money.a`、`money.c`、`feedui.util.ai`、`feedui.util.m`、`detail.util.j`、`utils.br`

## 三、工具修复

`verify-hooks.py` 早期版本把方法返回类型写死为 `)V`，导致返回 `View` 的
`com.sup.android.base.main.bottom.b#a(Context,String,String,int)` 被误报为缺失。

已改为**只比较参数类型、忽略返回类型**。

## 四、需要真机确认的项目

静态核对只能证明「类与方法存在」，不能证明运行时调用时机未变。发布后需实测：

- [x] 视频页播放完自动纵向切下一条
- [x] 评论页返回后底栏保持隐藏
- [x] 四个自动播放 / 自动刷新开关默认关闭
- [x] 求好评弹窗抑制
- [ ] 去广告（开屏 / 信息流）
- [ ] 右上角搜索红点
- [ ] 红包悬浮按钮

异常时抓取 `logcat` 中 `PPX` 前缀日志反馈。
