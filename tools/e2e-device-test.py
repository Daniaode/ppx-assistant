#!/usr/bin/env python3
"""皮友助手 —— 真机端到端自动化测试。

用途：验证「视频页自动切下一条」是否真的生效，不需要人工盯着屏幕。
原理：用 uiautomator 抓取界面文本，对比 T0 与 T+N 秒的文本指纹，
      文本变化即判定发生了翻页。

前提：
  1. 一台已 root（Magisk + LSPosed）并已启用本模块的真机，adb 可达；
  2. 皮皮虾已安装、已登录；
  3. adb 在 PATH 中。

用法：
  # USB 连接
  python3 tools/e2e-device-test.py --apk ../皮友助手-0.0.17-适配6.6.1.apk

  # 无线连接
  adb connect 192.168.1.23:5555
  python3 tools/e2e-device-test.py --serial 192.168.1.23:5555 --wait 30

判定：
  PASS  —— 在等待窗口内界面主要内容发生变化（视频切走了）
  FAIL  —— 窗口内内容无变化
退出码：0 通过，1 失败，2 环境问题。
"""
import argparse
import hashlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.sup.android.superb"
MODULE = "io.github.vs2pk0.piyou"


def sh(cmd, serial=None, timeout=30, check=False):
    full = ["adb"]
    if serial:
        full += ["-s", serial]
    full += cmd if isinstance(cmd, list) else cmd.split()
    try:
        p = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 1, "", "timeout"
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(full)} 失败: {p.stderr.strip()}")
    return p.returncode, p.stdout, p.stderr


def dump_ui(serial=None):
    """抓取当前界面并返回所有非空 text 属性。"""
    remote = "/sdcard/e2e_ui.xml"
    sh(["shell", "uiautomator", "dump", remote], serial=serial, timeout=40)
    rc, out, err = sh(["exec-out", "cat", remote], serial=serial, timeout=30)
    if rc != 0 or not out.strip().startswith("<?xml"):
        return None
    try:
        root = ET.fromstring(out)
    except ET.ParseError:
        return None
    texts = []
    for node in root.iter("node"):
        t = (node.get("text") or "").strip()
        if t and len(t) > 1:
            texts.append(t)
        d = (node.get("content-desc") or "").strip()
        if d and len(d) > 1:
            texts.append(d)
    # 去重保序
    seen, uniq = set(), []
    for t in texts:
        if t not in seen:
            seen.add(t)
            uniq.append(t)
    return uniq


def fingerprint(texts):
    """内容指纹：去掉时间、数字等易变项后取摘要。"""
    if not texts:
        return None, []
    stable = []
    for t in texts:
        s = re.sub(r"\d+", "#", t)
        s = re.sub(r"\s+", "", s)
        if len(s) >= 4:
            stable.append(s)
    if not stable:
        return None, []
    blob = "|".join(sorted(stable))
    return hashlib.md5(blob.encode()).hexdigest(), stable


def find_tab(serial, names=("视频", "视频 ")):
    """在界面里找「视频」Tab 并点击。返回是否找到。"""
    remote = "/sdcard/e2e_tab.xml"
    sh(["shell", "uiautomator", "dump", remote], serial=serial, timeout=40)
    rc, out, _ = sh(["exec-out", "cat", remote], serial=serial, timeout=30)
    if rc != 0 or not out.strip().startswith("<?xml"):
        return False
    try:
        root = ET.fromstring(out)
    except ET.ParseError:
        return False
    for node in root.iter("node"):
        t = (node.get("text") or "").strip()
        d = (node.get("content-desc") or "").strip()
        if t in names or d in names:
            b = node.get("bounds") or ""
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            sh(["shell", "input", "tap", str(cx), str(cy)], serial=serial)
            return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="要安装的 APK；省略则跳过安装")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--wait", type=int, default=25, help="观察窗口秒数")
    ap.add_argument("--interval", type=float, default=3.0, help="采样间隔")
    ap.add_argument("--skip-install", action="store_true")
    args = ap.parse_args()

    rc, out, _ = sh(["devices"], timeout=15)
    if "device" not in out:
        print("没有可用的 adb 设备。请先 USB 连接或 adb connect <ip:port>。", file=sys.stderr)
        return 2

    serial = args.serial
    if serial is None:
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serial = parts[0]
                break
    if serial is None:
        print("未找到在线设备。", file=sys.stderr)
        return 2
    print(f"设备：{serial}")

    if args.apk and not args.skip_install:
        print(f"安装 {args.apk} …")
        rc, out, err = sh(["install", "-r", "-d", args.apk], serial=serial, timeout=180)
        if rc != 0:
            print(f"安装失败：{err or out}", file=sys.stderr)
            return 2
        print("安装完成")

    # 确认模块已启用（有 lspd 日志目录说明 LSPosed 在工作）
    rc, out, _ = sh(["shell", "su", "-c", "ls /data/adb/lspd"], serial=serial, timeout=15)
    if rc != 0 or "log" not in out and out.strip() == "":
        print("警告：读不到 /data/adb/lspd，LSPosed 可能未启用或设备未 root。", file=sys.stderr)

    print("启动皮皮虾 …")
    sh(["shell", "am", "force-stop", PKG], serial=serial)
    time.sleep(1)
    sh(["shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"],
       serial=serial, timeout=40)
    time.sleep(6)

    rc, out, _ = sh(["shell", "pidof", PKG], serial=serial)
    if not out.strip():
        print("皮皮虾启动后没有进程——可能崩溃了。抓 logcat：", file=sys.stderr)
        rc, log, _ = sh(["logcat", "-d", "-t", "200"], serial=serial, timeout=30)
        for line in log.splitlines():
            if "FATAL" in line or "superb" in line.lower():
                print("  " + line)
        return 2
    print(f"进程已启动 pid={out.strip()}")

    print("切换到「视频」页 …")
    if find_tab(serial):
        print("已点击视频 Tab")
    else:
        print("没找到「视频」Tab，按当前页面继续")
    time.sleep(4)

    base_texts = dump_ui(serial)
    base_fp, base_stable = fingerprint(base_texts)
    if base_fp is None:
        print("拿不到界面文本，无法判定。", file=sys.stderr)
        return 2
    print(f"T0 内容指纹 {base_fp[:8]}（{len(base_stable)} 条文本）")

    deadline = time.time() + args.wait
    changed_at = None
    while time.time() < deadline:
        time.sleep(args.interval)
        texts = dump_ui(serial)
        fp, stable = fingerprint(texts)
        if fp and fp != base_fp:
            changed_at = args.wait - int(deadline - time.time())
            print(f"T+{changed_at}s 内容变化 -> 发生翻页")
            diff = set(stable) - set(base_stable)
            if diff:
                sample = list(diff)[:3]
                print("  新增文本样例：" + " / ".join(sample))
            break

    if changed_at is None:
        print(f"\nFAIL：{args.wait} 秒内界面内容没有变化，没有自动翻页。")
        return 1

    print("\nPASS：视频页在等待窗口内发生了自动翻页。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
