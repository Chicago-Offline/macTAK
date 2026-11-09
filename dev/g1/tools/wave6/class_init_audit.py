#!/usr/bin/env python3
"""Wave-6 audit: classify every class/init function in the JNI tree.

Operates on the staged post-wave-5 tree (default
$G1_STAGING/a/tmp/takeng-src, G1_STAGING defaults to /tmp/g1wave6; or
pass the jni tree as argv[1] — see ../README.md). For each init function
(X_class_init / X_init / registerSpi / init):
  - lookup calls: FindClass / ATAKMapEngineJNI_findClass / GetMethodID /
    GetStaticMethodID / GetFieldID / GetStaticFieldID / DefineClass /
    NewWeakGlobalRef
  - guards: null checks that bail (return false / JNI_ERR / NULL)
  - tail: does the function end with an unconditional `return true`?
Also audits the caller idiom `static bool <v> = <fn>(env...)`: is <v>
ever read afterwards?
Out-of-build TUs (c3dt/, vsi_jfile.cpp, jvsijfilefilesystemhandler.cpp)
are reported separately.

Reference tooling, not a CI gate: this is the wave-6 audit root,
re-runnable on any tree laid out the same way (e.g. one rebuilt from the
repo by stage_baseline.py in this directory).
"""
import os, pathlib, re, sys

TOOLS_DIR = pathlib.Path(__file__).resolve().parent
STAGING = pathlib.Path(os.environ.get("G1_STAGING") or "/tmp/g1wave6")
ROOT = (sys.argv[1] if len(sys.argv) > 1
        else os.environ.get("G1_AUDIT_ROOT")
        or str(STAGING / "a" / "tmp" / "takeng-src" / "jni"))
EXCLUDED = ("/c3dt/", "/vsi_jfile.cpp", "/jvsijfilefilesystemhandler.cpp")

DEFN = re.compile(
    r"\bbool\s+(\w+)\s*\(\s*(?:JNIEnv\s*&?|JNIEnv\s*\*)\s*\w*\s*\)\s*NOTHROWS\s*\{")
CALLER = re.compile(
    r"static\s+bool\s+(\w+)\s*=\s*(\w+)\s*\(\s*(?:env|env_)\w*\s*\)")

def extract_body(text, start):
    """start = index of '{'. Return (body, end_index) via brace matching,
    skipping braces in comments/strings."""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i+1] == "/":
            j = text.find("\n", i)
            i = j if j != -1 else n
            continue
        if c == "/" and i + 1 < n and text[i+1] == "*":
            j = text.find("*/", i)
            i = (j + 2) if j != -1 else n
            continue
        if c == '"' or c == "'":
            q = c
            i += 1
            while i < n and text[i] != q:
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[start:i+1], i
        i += 1
    return text[start:], n

LOOKUPS = [
    ("findClass",       r"(?:ATAKMapEngineJNI_findClass|->FindClass\(|\.FindClass\(|findClass\s*\()"),
    ("GetMethodID",     r"Get(?:Static)?MethodID\s*\("),
    ("GetFieldID",      r"Get(?:Static)?FieldID\s*\("),
    ("NewWeakGlobalRef",r"NewWeakGlobalRef\s*\("),
    ("DefineClass",     r"DefineClass\s*\("),
]
def count_guards(body):
    """Count `if (...) [return|{ return] false/JNI_ERR` bail-out guards.
    Cheap backward scan from each bail return."""
    guards = 0
    for m in re.finditer(r"return\s+(?:false|JNI_ERR)\s*;", body):
        pre = body[max(0, m.start() - 300):m.start()].rstrip()
        if pre.endswith("{"):
            pre = pre[:-1].rstrip()
        if not pre.endswith(")"):
            continue
        depth = 0
        j = len(pre) - 1
        while j >= 0:
            if pre[j] == ")":
                depth += 1
            elif pre[j] == "(":
                depth -= 1
                if depth == 0:
                    break
            j -= 1
        head = pre[:j].rstrip()
        if head.endswith("if") or re.search(r"\bif\s*$", head):
            guards += 1
    return guards


def classify(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    results = []
    for m in DEFN.finditer(text):
        name = m.group(1)
        body, end = extract_body(text, m.end() - 1)
        lookups = {k: len(re.findall(p, body)) for k, p in LOOKUPS}
        total_lookups = sum(lookups.values())
        guards = count_guards(body)
        rets = re.findall(r"return\s+([^;]+);", body)
        last = rets[-1].strip() if rets else "<none>"
        unconditional_true = (
            last == "true" and total_lookups > 0 and guards == 0)
        bails = bool(re.search(r"return\s+(?:false|JNI_ERR)\s*;", body))
        results.append(dict(
            name=name, line=text[:m.start()].count("\n") + 1,
            lookups=lookups, total=total_lookups, guards=guards,
            bails=bails, last_return=last, masked=unconditional_true,
        ))
    return results, text

def main():
    files = []
    for dp, dn, fn in os.walk(ROOT):
        for f in sorted(fn):
            if f.endswith((".cpp", ".h")):
                p = os.path.join(dp, f)
                rel = os.path.relpath(p, ROOT)
                files.append((rel, p, any(x in rel for x in EXCLUDED)))
    files.sort()
    live_funcs, dead_funcs = [], []
    callers = {}
    for rel, p, excluded in files:
        print(f"... scanning {rel}", flush=True)
        try:
            results, text = classify(p)
        except Exception as e:
            print(f"!! {rel}: {e}", file=sys.stderr)
            continue
        bucket = dead_funcs if excluded else live_funcs
        for r in results:
            r["file"] = rel
            bucket.append(r)
        for cm in CALLER.finditer(text):
            var, fn = cm.group(1), cm.group(2)
            reads = len(re.findall(r"\b" + re.escape(var) + r"\b", text)) - 1
            callers.setdefault(rel, []).append(
                dict(var=var, fn=fn, line=text[:cm.start()].count("\n") + 1,
                     reads=reads))

    def show(bucket, label):
        print(f"\n=== {label} ({len(bucket)} functions) ===")
        masked = [r for r in bucket if r["masked"]]
        partial = [r for r in bucket if r["bails"] and r["total"] > r["guards"]]
        ok = [r for r in bucket if r["guards"] >= r["total"] and r["total"] > 0]
        nolookup = [r for r in bucket if r["total"] == 0]
        print(f"  fully masked (0 guards, unconditional true): {len(masked)}")
        for r in masked:
            lk = ",".join(f"{k}:{v}" for k, v in r["lookups"].items() if v)
            print(f"    {r['file']}:{r['line']}  {r['name']}  [{lk}]")
        print(f"  partial (bails somewhere but not everywhere): {len(partial)}")
        for r in partial:
            lk = ",".join(f"{k}:{v}" for k, v in r["lookups"].items() if v)
            print(f"    {r['file']}:{r['line']}  {r['name']}  [{lk}] guards={r['guards']}/{r['total']}")
        print(f"  fully guarded: {len(ok)}")
        for r in ok:
            print(f"    {r['file']}:{r['line']}  {r['name']}  guards={r['guards']}/{r['total']}")
        print(f"  no lookups (trivial/registration-only): {len(nolookup)}")
        for r in nolookup:
            print(f"    {r['file']}:{r['line']}  {r['name']}  last={r['last_return'][:30]}")

    show(live_funcs, "LIVE on desktop build")
    show(dead_funcs, "EXCLUDED from desktop build")

    print("\n=== caller idiom `static bool v = fn(env)` — result ever read? ===")
    unread = 0
    for rel in sorted(callers):
        for c in callers[rel]:
            flag = "UNREAD" if c["reads"] == 0 else f"read x{c['reads']}"
            if c["reads"] == 0:
                unread += 1
            print(f"  {rel}:{c['line']}  {c['var']} = {c['fn']}(...)  [{flag}]")
    print(f"  unread-result callers: {unread}")

if __name__ == "__main__":
    main()

