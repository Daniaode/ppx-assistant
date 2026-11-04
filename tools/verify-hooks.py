#!/usr/bin/env python3
"""Hook 目标静态校验。

把「钩了一个不存在 / 空实现的方法」这类错误变成可自动化的构建期检查。
背景：0.0.14 / 0.0.15 的自动刷视频一直不生效，原因是钩了
SnapPageScrollListener.a(I)V —— 它在基类里只有一条 return-void，
真正的实现在匿名子类 XxxFragment$mPagerScrollListener$1 上。

用法（在反编译 dump 所在机器上）：
    python3 tools/verify-hooks.py --dump-dir /tmp --targets tools/hook-targets.json

dump 由 dexdump -d 生成，文件名形如 dd_classes1.dex.txt ... dd_classes35.dex.txt。
退出码 0 = 全部符合预期；1 = 有不符合项。
"""
import argparse
import glob
import json
import os
import re
import sys

CLASS_RE = re.compile(r"^\s*Class descriptor\s*:\s*'(L[^']+;)'")
METHOD_HEAD_RE = re.compile(r"^\s*#\d+\s*:\s*\(in (L[^)]+;)\)")
NAME_RE = re.compile(r"^\s*name\s*:\s*'([^']*)'")
TYPE_RE = re.compile(r"^\s*type\s*:\s*'([^']*)'")
INSNS_RE = re.compile(r"^\s*insns size\s*:\s*(\d+)")
INSN_RE = re.compile(r"^\s*[0-9a-f]{6,}:\s+[0-9a-f ]+\|([0-9a-f]{4}):\s+([a-z0-9\-/]+)")

# 只做 return-void 的方法视为空实现
NOOP_OPCODES = {"return-void"}


def parse_dump(path, index):
    cur_cls = None
    cur_name = None
    cur_desc = None
    cur_insns = None          # None = 无代码（abstract/native）
    cur_body = []

    def flush():
        if cur_cls and cur_name is not None and cur_desc is not None:
            index.setdefault(cur_cls, {})[(cur_name, cur_desc)] = {
                "insns": cur_insns,
                "body": list(cur_body),
            }

    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = CLASS_RE.match(line)
            if m:
                flush()
                # descriptor 用 '/' 分隔，目标清单用 '.' 分隔，统一成点号
                cur_cls = m.group(1)[1:-1].replace("/", ".")
                cur_name = cur_desc = None
                cur_insns = None
                cur_body = []
                continue
            if cur_cls is None:
                continue
            if METHOD_HEAD_RE.match(line):
                flush()
                cur_name = cur_desc = None
                cur_insns = None
                cur_body = []
                continue
            m = NAME_RE.match(line)
            if m:
                cur_name = m.group(1)
                continue
            m = TYPE_RE.match(line)
            if m:
                cur_desc = m.group(1)
                continue
            m = INSNS_RE.match(line)
            if m:
                cur_insns = int(m.group(1))
                cur_body = []
                continue
            if cur_insns is not None:
                m = INSN_RE.match(line)
                if m:
                    cur_body.append(m.group(2))
    flush()


def classify(record):
    if record is None:
        return "missing"
    if record["insns"] is None:
        return "nocode"
    body = [op for op in record["body"] if op]
    if not body or set(body) <= NOOP_OPCODES:
        return "noop"
    return "implemented"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", default="/tmp")
    ap.add_argument("--pattern", default="dd_classes*.dex.txt")
    ap.add_argument("--targets", default="tools/hook-targets.json")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.dump_dir, args.pattern)))
    if not files:
        print(f"找不到 dump 文件：{args.dump_dir}/{args.pattern}", file=sys.stderr)
        return 2

    index = {}
    for path in files:
        parse_dump(path, index)

    with open(args.targets, encoding="utf-8") as fh:
        targets = json.load(fh)

    failures = []
    warnings = []
    rows = []

    for t in targets:
        cls = t["class"]
        method = t["method"]
        desc = t.get("desc", "*")
        expect = t.get("expect", "implemented")
        note = t.get("note", "")

        methods = index.get(cls)
        if methods is None:
            status = "missing-class"
            found_desc = None
        else:
            candidates = [
                (d, rec) for (n, d), rec in methods.items()
                if n == method and (desc == "*" or d == desc)
            ]
            if not candidates:
                status = "missing-method"
                found_desc = None
            else:
                # 同名多签名时优先取已实现的那个
                ranked = sorted(
                    candidates,
                    key=lambda x: 0 if classify(x[1]) == "implemented" else 1,
                )
                found_desc, rec = ranked[0]
                status = classify(rec)

        rows.append((cls, method, desc, status, expect, note))

        if expect == "missing":
            if status not in ("missing-class", "missing-method"):
                failures.append(f"{cls}#{method}{desc}: 预期不存在，实际为 {status}")
        else:
            if status != expect:
                failures.append(f"{cls}#{method}{desc}: 预期 {expect}，实际 {status}")

        # 预期实现却拿到空方法 -> 找出真正覆写的子类，给出可执行建议
        if expect == "implemented" and status == "noop":
            overrides = []
            for other_cls, ms in index.items():
                if other_cls == cls or not other_cls.startswith(cls):
                    continue
                for (n, d), rec in ms.items():
                    if n == method and (desc == "*" or d == desc) and classify(rec) == "implemented":
                        overrides.append(f"{other_cls} ({d})")
            if overrides:
                warnings.append(
                    f"{cls}#{method}{desc} 是空方法，实际实现在子类："
                    + "; ".join(sorted(overrides))
                )

    if not args.quiet:
        print(f"{'结果':<14} {'类':<62} 方法/签名")
        print("-" * 110)
        for cls, method, desc, status, expect, note in rows:
            if expect == "missing":
                ok = status in ("missing-class", "missing-method")
            else:
                ok = status == expect
            flag = "OK" if ok else "FAIL"
            print(f"{status:<14} {cls:<62} {method}{desc}  [{flag}]")
            if note:
                print(f"{'':<14} 备注：{note}")

    if warnings:
        print("\n提示：")
        for w in warnings:
            print("  - " + w)

    if failures:
        print("\n失败项：")
        for f in failures:
            print("  - " + f)
        return 1

    print(f"\n全部 {len(rows)} 项符合预期。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
