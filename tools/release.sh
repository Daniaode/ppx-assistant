#!/usr/bin/env bash
# release.sh — 皮友助手发布流水线
#
# 把 docs/RELEASE.md 的流程固化成命令，减少手工失误。
#
# 前提：
#   - 本机可 ssh 到编译机（默认别名 buildserver）
#   - 已 gh auth login（对 Daniaode/piyou-assistant 有写权限）
#   - 宿主 APK 位于编译机 /root/pipixia/pipixia-<host>.apk
#   - 签名配置在编译机 ppx-assistant/local.properties
#
# 用法：
#   ./tools/release.sh module   1.1.1            # 只发模块本体
#   ./tools/release.sh all      1.1.1 6.6.5      # 模块 + 集成版（含分片合并）
#   ./tools/release.sh notes    1.1.1            # 只打印将要使用的 Release 说明
#
# 环境变量覆盖：
#   REMOTE=buildserver  REPO=Daniaode/piyou-assistant  REMOTE_DIR=/root/pipixia

set -euo pipefail

REMOTE="${REMOTE:-buildserver}"
REPO="${REPO:-Daniaode/piyou-assistant}"
REMOTE_DIR="${REMOTE_DIR:-/root/pipixia}"
APK_NAME="app/build/outputs/apk/release/app-release.apk"

log()  { printf '\033[36m[release]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[release] %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null || die "缺少依赖: $1"; }

build_module() {
    local ver="$1"
    log "同步源码到编译机"
    rsync -az --exclude build --exclude .git --exclude .gradle --exclude local.properties \
        ./ "$REMOTE:$REMOTE_DIR/ppx-assistant/"

    log "编译 $ver"
    ssh "$REMOTE" "cd $REMOTE_DIR/ppx-assistant && ./gradlew :app:assembleRelease --console=plain" \
        || die "编译失败"

    log "校验签名证书"
    local out
    out=$(ssh "$REMOTE" "cd $REMOTE_DIR/ppx-assistant && \
        /root/android-sdk/build-tools/36.0.0/apksigner verify --print-certs $APK_NAME 2>&1 \
        | grep -E 'SHA-256 digest' | head -1")
    echo "  $out"
    echo "$out" | grep -q "541d41bc" || die "签名证书不符（期望 541d41bc…）"

    log "回传模块包"
    scp -q "$REMOTE:$REMOTE_DIR/ppx-assistant/$APK_NAME" "皮友助手-$ver.apk"
    ls -l "皮友助手-$ver.apk"
}

publish_module() {
    local ver="$1" notes="$2"
    log "发布模块 $ver"
    gh release create "v$ver" "皮友助手-$ver.apk#Piyou-Assistant-$ver.apk" \
        --repo "$REPO" --title "$ver" --notes-file "$notes" \
    || gh release upload "v$ver" "皮友助手-$ver.apk#Piyou-Assistant-$ver.apk" \
        --repo "$REPO" --clobber
    log "模块已发布：https://github.com/$REPO/releases/tag/v$ver"
}

build_integrated() {
    local ver="$1" host="$2"
    log "在编译机上生成集成版（LSPatch）"
    ssh "$REMOTE" "cd $REMOTE_DIR && \
        PW=\$(grep '^storePassword=' ppx-assistant/local.properties | cut -d= -f2-) && \
        KP=\$(grep '^keyPassword='  ppx-assistant/local.properties | cut -d= -f2-) && \
        [ -z \"\$KP\" ] && KP=\"\$PW\"; \
        rm -rf out$ver && mkdir -p out$ver && \
        java -Xmx6g -jar lspatch.jar \
            -m ppx-assistant/$APK_NAME \
            -o out$ver -f -k keystore.jks \"\$PW\" ppx \"\$KP\" pipixia-$host.apk" \
        || die "LSPatch 集成失败"

    local remote_apk
    remote_apk=$(ssh "$REMOTE" "ls $REMOTE_DIR/out$ver/*.apk | head -1")
    log "回传集成版：$remote_apk"
    scp -q "$REMOTE:$remote_apk" "皮友助手-集成版$ver-皮皮虾$host.apk"
    ls -l "皮友助手-集成版$ver-皮皮虾$host.apk"
}

upload_parts_and_merge() {
    local ver="$1" host="$2"
    local merged="皮友助手-集成版$ver-皮皮虾$host.apk"
    local md5
    md5=$(md5sum "$merged" | cut -d' ' -f1)
    log "集成版 MD5: $md5"

    rm -f gh.part*.apk
    split -b 39M -d -a 1 "$merged" gh.part
    for f in gh.part*; do mv "$f" "$f.apk"; done

    for p in gh.part*.apk; do
        log "上传分片 $p"
        local ok=0
        for attempt in 1 2 3 4 5; do
            if gh release upload "v$ver" "$p" --repo "$REPO" --clobber; then ok=1; break; fi
            log "  第 $attempt 次失败，15s 后重试"
            sleep 15
        done
        [ "$ok" = "1" ] || die "分片 $p 上传失败"
    done

    log "触发 GitHub 侧合并"
    gh workflow run merge-release-assets.yml --repo "$REPO" \
        -f "tag=v$ver" \
        -f parts_prefix=gh.part \
        -f "merged_name=Piyou-Integrated-$ver-$host.apk" \
        -f "md5=$md5" \
        -f delete_parts=true

    log "等待合并完成"
    sleep 10
    local run
    run=$(gh run list --repo "$REPO" --workflow=merge-release-assets.yml --limit 1 --json databaseId --jq '.[0].databaseId')
    gh run watch "$run" --repo "$REPO" --exit-status || die "合并工作流失败"

    log "最终资产："
    gh release view "v$ver" --repo "$REPO" --json assets \
        --jq '.assets[] | "  \(.name)  \(.size)"'

    rm -f gh.part*.apk
}

case "${1:-}" in
    module)
        ver="${2:?用法: release.sh module <版本>}"
        need gh; need rsync; need scp
        build_module "$ver"
        notes="docs/release-notes/$ver.md"
        [ -f "$notes" ] || die "缺少 Release 说明: $notes"
        publish_module "$ver" "$notes"
        ;;
    all)
        ver="${2:?用法: release.sh all <版本> <宿主版本>}"
        host="${3:?用法: release.sh all <版本> <宿主版本>}"
        need gh; need rsync; need scp; need split
        build_module "$ver"
        notes="docs/release-notes/$ver.md"
        [ -f "$notes" ] || die "缺少 Release 说明: $notes"
        publish_module "$ver" "$notes"
        build_integrated "$ver" "$host"
        upload_parts_and_merge "$ver" "$host"
        ;;
    notes)
        ver="${2:?用法: release.sh notes <版本>}"
        cat "docs/release-notes/$ver.md"
        ;;
    *)
        die "用法: release.sh {module|all|notes} <版本> [宿主版本]"
        ;;
esac

log "完成"
