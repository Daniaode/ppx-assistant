#!/usr/bin/env python3
"""从模块源码自动提取 Hook 目标。

手工维护目标清单迟早会漏。0.0.14~0.0.16 的三次返工都是因为
「钩了空方法 / 钩了不存在的方法」没被发现。

本项目里有三种写法，必须都覆盖：
  1. String 扩展：  "com.foo.Bar".hookAfterMethod(cl, "m", Int::class.java)
                    —— 注意第一个参数是 ClassLoader，方法名在第二个
  2. Class 接收者：  "com.foo.Bar".findClass(cl).hookAfterMethod("m", ...)
  3. apply 作用域：  "com.foo.Bar".findClass(cl).apply { hookAfterMethod("m") { } }

用法：
    python3 tools/extract-hooks.py --src app/src/main/java \
        --out tools/hook-targets.generated.json
"""
import argparse
import json
import os
import re
import sys

TYPE_MAP = {
    "Int": "I", "Integer": "Ljava/lang/Integer;",
    "Boolean": "Z", "Long": "J", "Float": "F", "Double": "D",
    "Byte": "B", "Char": "C", "Short": "S",
    "String": "Ljava/lang/String;",
    "LayoutInflater": "Landroid/view/LayoutInflater;",
    "ViewGroup": "Landroid/view/ViewGroup;",
    "Bundle": "Landroid/os/Bundle;",
    "Context": "Landroid/content/Context;",
    "View": "Landroid/view/View;",
    "Intent": "Landroid/content/Intent;",
    "Runnable": "Ljava/lang/Runnable;",
    "ArrayList": "Ljava/util/ArrayList;",
    "RecyclerView": "Landroidx/recyclerview/widget/RecyclerView;",
    "Activity": "Landroid/app/Activity;",
    "Dialog": "Landroid/app/Dialog;",
}

FQCN = r'[a-zA-Z_][\w.$]*'
VAL_DEF = re.compile(r'\bval\s+(\w+)\s*(?::\s*Class<[^>]*>)?\s*=\s*"(' + FQCN + r')"')
VAL_DEF_FIND = re.compile(r'\bval\s+(\w+)\s*(?::\s*Class<[^>]*>)?\s*=\s*"(' + FQCN + r')"\s*\.\s*findClass')
HOOK_CALL = re.compile(r'\.\s*(hookAfterMethod|hookBeforeMethod|hookAllMethods)\s*\(')
FIND_CLASS = re.compile(r'"(' + FQCN + r')"\s*\.\s*findClass(?:OrNull|Compat)?\s*\(')
LIST_DEF = re.compile(r'\bval\s+(\w+)\s*(?::\s*[\w<>,\s]+)?\s*=\s*listOf\s*\(', re.S)
FOREACH = re.compile(r'(\w+)\.forEach\s*\{\s*(\w+)\s*->')


def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def iter_sources(root):
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if name.endswith((".kt", ".java")):
                yield os.path.join(dirpath, name)


def collect_lists(sources):
    """val L = listOf("a", "b") -> {L: [a, b]}"""
    table = {}
    for _path, text in sources:
        for m in LIST_DEF.finditer(text):
            args = split_top(balanced_args(text, m.end() - 1))
            items = [mm.group(1).replace('\\$', '$') for mm in
                     (re.match(r'"([\w.$\\\\]+)"', a) for a in args) if mm]
            if items:
                table.setdefault(m.group(1), items)
    return table


def extract_list_for_each(path, text, lists, unresolved):
    """LIST.forEach { name -> name.findClass(cl).hookAfterMethod("m") }"""
    found, seen = [], set()
    rel = os.path.relpath(path)
    for m in FOREACH.finditer(text):
        coll, var = m.group(1), m.group(2)
        classes = lists.get(coll)
        if not classes:
            continue
        brace = text.find("{", m.end() - 1)
        block, _ = balanced_brace(text, brace)
        pattern = r'\b' + re.escape(var) + r'\s*\.\s*findClass(?:OrNull|Compat)?\s*[\(]' + r'[^)]*\)\s*\.\s*(hookAfterMethod|hookBeforeMethod)\s*\('
        for hm in re.finditer(pattern, block):
            args = split_top(balanced_args(block, hm.end() - 1))
            if not args:
                continue
            nm = re.match(r'"([^"]*)"', args[0])
            if not nm:
                unresolved.append(f"{rel}:{line_of(text, m.start())} 方法名非字面量")
                continue
            for cls in classes:
                add(found, seen, cls, nm.group(1), parse_types(args[1:]),
                    {"source": f"{rel}:{line_of(text, m.start())}", "hook": hm.group(1)})
    return found


def extract_from_list_literal(path, text, unresolved):
    """listOf("a","b").forEach { x -> x.findClass(...).hookAfterMethod("m") }"""
    found, seen = [], set()
    rel = os.path.relpath(path)
    for m in re.finditer(r'listOf\s*\(', text):
        args = split_top(balanced_args(text, m.end() - 1))
        classes = [mm.group(1).replace('\\$', '$') for mm in
                   (re.match(r'"([\w.$\\\\]+)"', a) for a in args) if mm]
        if not classes:
            continue
        fe = re.match(r'\s*\)\s*\.\s*forEach\s*\{\s*(\w+)\s*->', text[m.end():])
        if not fe:
            continue
        nxt = m.end() + fe.end()
        brace = text.find("{", nxt - 1)
        block, _ = balanced_brace(text, brace)
        var = fe.group(1)
        pattern2 = r'\b' + re.escape(var) + r'\s*\.\s*findClass(?:OrNull|Compat)?\s*[\(]' + r'[^)]*\)\s*\.\s*(hookAfterMethod|hookBeforeMethod)\s*\('
        for hm in re.finditer(pattern2, block):
            hargs = split_top(balanced_args(block, hm.end() - 1))
            if not hargs:
                continue
            nm = re.match(r'"([^"]*)"', hargs[0])
            if not nm:
                continue
            for cls in classes:
                add(found, seen, cls, nm.group(1), parse_types(hargs[1:]),
                    {"source": f"{rel}:{line_of(text, m.start())}", "hook": hm.group(1)})
    return found


def collect_vars(sources):
    table = {}
    for _path, text in sources:
        for m in VAL_DEF.finditer(text):
            table.setdefault(m.group(1), m.group(2))
        # val x = "com.foo.Bar".findClass(cl)
        for m in VAL_DEF_FIND.finditer(text):
            table.setdefault(m.group(1), m.group(2))
    return table


def balanced_args(text, open_idx):
    depth = 0
    i = open_idx
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
        elif ch == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
        i += 1
    return ""


def balanced_brace(text, open_idx):
    depth = 0
    i = open_idx
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i
        elif ch == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
        i += 1
    return text[open_idx + 1:], len(text)


def split_top(text):
    parts, depth, cur = [], 0, []
    for ch in text:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def parse_types(args):
    if not args:
        return "()V"
    desc = []
    for a in args:
        a = a.strip()
        if not a:
            continue
        m = re.match(r"([A-Za-z_][\w.]*)\s*::\s*class", a)
        if not m:
            return "*"
        simple = m.group(1).split(".")[-1]
        if simple in TYPE_MAP:
            desc.append(TYPE_MAP[simple])
        else:
            return "*"
    return "(" + "".join(desc) + ")V"


def line_of(text, idx):
    return text.count("\n", 0, idx) + 1


def add(found, seen, cls, method, desc, meta):
    key = (cls, method, desc)
    if key in seen:
        return
    seen.add(key)
    found.append({"class": cls, "method": method, "desc": desc, **meta})


def extract(path, text, var_table, unresolved):
    found, seen = [], set()
    rel = os.path.relpath(path)

    # ---- 形式 1：String 扩展，"cls".hookAfterMethod(cl, "m", ...) ----
    for m in re.finditer(r'"(' + FQCN + r')"\s*\.\s*(hookAfterMethod|hookBeforeMethod)\s*\(', text):
        cls = m.group(1)
        args = split_top(balanced_args(text, m.end() - 1))
        if len(args) < 2:
            continue
        nm = re.match(r'"([^"]*)"', args[1])
        if not nm:
            unresolved.append(f"{rel}:{line_of(text, m.start())} 方法名非字面量")
            continue
        add(found, seen, cls, nm.group(1), parse_types(args[2:]),
            {"source": f"{rel}:{line_of(text, m.start())}", "hook": m.group(2)})

    # ---- 形式 2：findClass 后链式调用，"cls".findClass(cl).hookAfterMethod("m") ----
    for m in FIND_CLASS.finditer(text):
        cls = m.group(1)
        tail = text[m.end():m.end() + 300]
        hm = HOOK_CALL.search(tail)
        if not hm or tail[:hm.start()].count("apply") > 0:
            continue
        args = split_top(balanced_args(text, m.end() + hm.end() - 1))
        if not args:
            continue
        nm = re.match(r'"([^"]*)"', args[0])
        if not nm:
            unresolved.append(f"{rel}:{line_of(text, m.start())} 方法名非字面量")
            continue
        add(found, seen, cls, nm.group(1),
            "*" if hm.group(1) == "hookAllMethods" else parse_types(args[1:]),
            {"source": f"{rel}:{line_of(text, m.start())}", "hook": hm.group(1)})

    # ---- 形式 3：.apply { hookAfterMethod("m") } ----
    for m in re.finditer(r'"(' + FQCN + r')"\s*\.\s*findClass(?:OrNull|Compat)?\s*\([^)]*\)\s*\.\s*apply\s*\{', text):
        cls = m.group(1)
        brace = text.find("{", m.end() - 1)
        block, _ = balanced_brace(text, brace)
        for hm in HOOK_CALL.finditer(block):
            args = split_top(balanced_args(block, hm.end() - 1))
            if not args:
                continue
            nm = re.match(r'"([^"]*)"', args[0])
            if not nm:
                continue
            add(found, seen, cls, nm.group(1),
                "*" if hm.group(1) == "hookAllMethods" else parse_types(args[1:]),
                {"source": f"{rel}:{line_of(text, m.start())}", "hook": hm.group(1)})

    # ---- 形式 4：变量接收者 xxx.hookAfterMethod("m") ----
    for hm in HOOK_CALL.finditer(text):
        head = text[max(0, hm.start() - 100):hm.start()]
        vm = re.search(r'([A-Za-z_]\w*)\s*\??\s*$', head)
        if not vm:
            continue
        var = vm.group(1)
        cls = var_table.get(var)
        if not cls:
            continue
        args = split_top(balanced_args(text, hm.end() - 1))
        if not args:
            continue
        nm = re.match(r'"([^"]*)"', args[0])
        if not nm:
            continue
        add(found, seen, cls, nm.group(1),
            "*" if hm.group(1) == "hookAllMethods" else parse_types(args[1:]),
            {"source": f"{rel}:{line_of(text, hm.start())}", "hook": hm.group(1)})

    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="app/src/main/java")
    ap.add_argument("--out", default="tools/hook-targets.generated.json")
    args = ap.parse_args()

    if not os.path.isdir(args.src):
        print(f"源码目录不存在：{args.src}", file=sys.stderr)
        return 2

    sources = [(p, read(p)) for p in iter_sources(args.src)]
    var_table = collect_vars(sources)
    lists = collect_lists(sources)

    targets, unresolved = [], []
    seen = set()
    for path, text in sources:
        batch = extract(path, text, var_table, unresolved)
        batch += extract_list_for_each(path, text, lists, unresolved)
        batch += extract_from_list_literal(path, text, unresolved)
        for t in batch:
            key = (t["class"], t["method"], t["desc"])
            if key in seen:
                continue
            seen.add(key)
            targets.append(t)

    targets.sort(key=lambda x: (x["class"], x["method"], x["desc"]))
    out = [{
        "class": t["class"],
        "method": t["method"],
        "desc": t["desc"],
        "expect": "implemented",
        "strict": False,
        "note": f"{t['hook']} @ {t['source']}",
    } for t in targets]

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)

    by_hook = {}
    for t in targets:
        by_hook[t["hook"]] = by_hook.get(t["hook"], 0) + 1
    print(f"共提取 {len(out)} 个 Hook 目标（扫描 {len(sources)} 个源文件）")
    for k, v in sorted(by_hook.items()):
        print(f"  {k}: {v}")
    if unresolved:
        print(f"未能解析 {len(unresolved)} 处（方法名为变量等）：")
        for u in unresolved[:5]:
            print("  - " + u)
    print(f"已写入 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
