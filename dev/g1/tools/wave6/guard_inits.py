#!/usr/bin/env python3
"""Wave-6: insert failure guards into class-init function bodies (tree b).

For every target function (masked/partial init fns from the audit), insert
house-style guards after every unguarded lookup assignment:
  X = ATAKMapEngineJNI_findClass(...);   -> if(!X) return <bail>;
  X = env[->.]Get...ID(...);             -> if(!X) return <bail>;
  V = env[->.]New(G|WG)Ref(env[->.]GetStaticObjectField(E,
        env[->.]GetStaticFieldID(E, "N", "S")));  -> hoist + guard + reuse
Bail token: JNI_OnLoad -> JNI_ERR; otherwise the function's own `false`.
Lines already followed by an `if` guard are left untouched.

Reference tooling, not a CI gate. Operates on the b/ tree under
$G1_STAGING (default /tmp/g1wave6); the audit logic comes from
class_init_audit.py, imported as a sibling module (running this file
directly puts its directory on sys.path). See ../README.md.
"""
import os, re, sys

# class_init_audit is a sibling module; no /tmp path needed
from class_init_audit import DEFN, extract_body, classify, ROOT, STAGING

TREE_B = STAGING / "b" / "tmp" / "takeng-src" / "jni"

FINDCLASS = re.compile(
    r'^(\s*)(?:jclass\s+)?([A-Za-z_][\w.]*)\s*=\s*ATAKMapEngineJNI_findClass\(.*\);\s*$', re.S)
GETID = re.compile(
    r'^(\s*)(?:(?:jclass|jmethodID|jfieldID)\s+)?([A-Za-z_][\w.]*)\s*=\s*env(?:->|\.)Get(?:Static)?(?:Method|Field)ID\(.*\);\s*$', re.S)
INLINE_STAT = re.compile(
    r'^(\s*)([A-Za-z_][\w.]*)\s*=\s*env(?:->|\.)'
    r'(New(?:Global|WeakGlobal)Ref)\(\s*env(?:->|\.)GetStaticObjectField\(\s*(\w+)\s*,\s*'
    r'env(?:->|\.)GetStaticFieldID\(\s*(\w+)\s*,\s*("[^"]*")\s*,\s*("[^"]*")\s*\)\s*\)\s*\);\s*$', re.S)


def split_statements(body):
    """Group physical lines into logical statements: accumulate lines until
    paren depth returns to 0 on a line ending with ';'. Preprocessor lines
    (with their continuations) are excluded from statements. Returns
    (statements, preproc_indexes)."""
    lines = body.split("\n")
    stmts = []
    preproc = set()
    cur = []
    cur_start = 0
    depth = 0
    in_preproc = False
    for idx, ln in enumerate(lines):
        if ln.lstrip().startswith("#") or (in_preproc and cur == [] ):
            preproc.add(idx)
            if ln.rstrip().endswith("\\"):
                in_preproc = True
            else:
                in_preproc = False
            continue
        if in_preproc and ln.rstrip().endswith("\\"):
            preproc.add(idx)
            continue
        if in_preproc:
            in_preproc = False
            preproc.add(idx)
            continue
        # comment-only / brace-only lines are statement boundaries of their own
        stripped = ln.strip()
        if stripped.startswith("//") or stripped in ("{", "}"):
            if cur:
                stmts.append((cur_start, idx - 1, "\n".join(cur)))
                cur = []
                depth = 0
            stmts.append((idx, idx, ln))
            continue
        if cur:
            cur.append(ln)
        elif ln.strip():
            cur = [ln]
            cur_start = idx
        else:
            continue
        depth += ln.count("(") - ln.count(")")
        if depth <= 0 and cur[-1].rstrip().endswith(";"):
            stmts.append((cur_start, idx, "\n".join(cur)))
            cur = []
            depth = 0
    if cur:
        stmts.append((cur_start, idx, "\n".join(cur)))
    return stmts, preproc

def patch_body(body, bail, label=""):
    lines = body.split("\n")
    # extract_body includes the opening '{' and the closing '}': strip both
    # so statement splitting sees only the body's statements.
    head = lines[0] if lines[0].strip() == "{" else None
    tail = None
    if lines[-1].rstrip().endswith("}") and len(lines) > 1:
        tail = lines[-1]
        lines = lines[:-1]
    if head is not None:
        lines = lines[1:]
    inserts = {}        # last_idx (into `lines`) -> list of guard lines
    used_tmp = set()
    n_ins = 0
    inner = "\n".join(lines)
    stmts, preproc = split_statements(inner)
    for first, last, text in stmts:
        indent = re.match(r"^\s*", text).group(0)
        # what physically follows the statement (skip blank/preproc lines)
        j = last + 1
        while j < len(lines) and (not lines[j].strip() or j in preproc):
            j += 1
        guarded = j < len(lines) and lines[j].lstrip().startswith("if")
        if guarded:
            continue
        m = INLINE_STAT.match(text)
        if m:
            _, var, newref, cls1, cls2, fname, fsig = m.groups()
            assert cls1 == cls2, f"inline class mismatch: {text!r}"
            cls = cls1
            tmp = re.sub(r"[^A-Za-z0-9_]", "_", f"{cls}_{fname.strip(chr(34))}_fid")
            k = 2
            base = tmp
            while tmp in used_tmp:
                tmp = f"{base}_{k}"
                k += 1
            used_tmp.add(tmp)
            envform = "->" if "env->" in text else "."
            inserts[last] = [
                f"{indent}jfieldID {tmp} = env{envform}GetStaticFieldID({cls}, {fname}, {fsig});",
                f"{indent}if(!{tmp})",
                f"{indent}    return {bail};",
            ]
            n_ins += 1
            continue
        m = FINDCLASS.match(text) or GETID.match(text)
        if m:
            var = m.group(2)
            if var == "retval":
                continue
            inserts[last] = [
                f"{indent}if(!{var})",
                f"{indent}    return {bail};",
            ]
            n_ins += 1
        elif re.search(r"ATAKMapEngineJNI_findClass\(|Get(?:Static)?(?:Method|Field)ID\(", text):
            print(f"  !! unguarded lookup left: {label}: {text.strip()[:90]!r}")
    out = []
    for idx, ln in enumerate(lines):
        out.append(ln)
        if idx in inserts:
            out.extend(inserts[idx])
    result = "\n".join(out)
    if head is not None:
        result = head + "\n" + result
    if tail is not None:
        result = result + "\n" + tail
    return result, n_ins

def main():
    files = {}
    SKIP = ("c3dt/jgltf.cpp", "vsi_jfile.cpp", "jvsijfilefilesystemhandler.cpp")
    for dp, dn, fn in os.walk(ROOT):
        for f in sorted(fn):
            if f.endswith(".cpp"):
                rel = os.path.relpath(os.path.join(dp, f), ROOT)
                if any(x in rel for x in SKIP):
                    continue
                results, text = classify(os.path.join(dp, f))
                for r in results:
                    if r["name"] == "JNI_OnLoad":
                        r["bail"] = "JNI_ERR"
                    elif r["total"] > r["guards"]:
                        r["bail"] = "false"
                    else:
                        continue
                    files.setdefault(rel, []).append(r)
    total_ins = 0
    touched = []
    EXTRA = {"common.cpp": [("JNI_OnLoad", "JNI_ERR"),
                            ("ATAKMapEngineJNI_equals", "false")]}
    for rel, extra in EXTRA.items():
        for name, bail in extra:
            if not any(r["name"] == name for r in files.get(rel, [])):
                files.setdefault(rel, []).append({"name": name, "bail": bail})
    for rel in sorted(files):
        path = os.path.join(TREE_B, rel)
        text = open(path, encoding="utf-8", errors="replace").read()
        orig = text
        for r in files[rel]:
            m = re.search(
                r"\b(?:bool|jint)\s+(?:\w+\s+)?" + re.escape(r["name"]) +
                r"\s*\([^)]*\)\s*(?:NOTHROWS\s*)?\{", text)
            if not m:
                print(f"!! skip (defn not found): {rel}:{r['name']}")
                continue
            start = m.end() - 1
            body, end = extract_body(text, start)
            new_body, n_ins = patch_body(body, r["bail"], f"{rel}:{r['name']}")
            if n_ins:
                text = text[:start] + new_body + text[end + 1:]
                print(f"  {rel}:{r['name']}  +{n_ins} guard lines  (bail {r['bail']})")
                total_ins += n_ins
        if text != orig:
            open(path, "w", encoding="utf-8").write(text)
            touched.append(rel)
    print(f"\nfiles touched: {len(touched)}, guard lines inserted: {total_ins}")
    print("\n".join(touched))

if __name__ == "__main__":
    main()