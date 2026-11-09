#!/usr/bin/env python3
"""Fix generator artifact: the inline New*Ref(GetStaticObjectField(E,
GetStaticFieldID(...))) statement was kept alongside its hoisted fid copy.
Rewrite the 4-line block so the statement USES the hoisted fid:
    VAR = env..New(G)Ref(env..GetStaticObjectField(E, env..GetStaticFieldID(E, N, S)));
    jfieldID FID = env..GetStaticFieldID(E, N, S);
    if(!FID)
        return BAIL;
->
    jfieldID FID = env..GetStaticFieldID(E, N, S);
    if(!FID)
        return BAIL;
    VAR = env..New(G)Ref(env..GetStaticObjectField(E, FID));

Reference tooling, not a CI gate — the one-off cleanup pass of the wave-6
sweep (its outcome is baked into the committed patches). Reads the b/
tree under $G1_STAGING (default /tmp/g1wave6). See ../README.md.
"""
import os, pathlib, re

STAGING = pathlib.Path(os.environ.get("G1_STAGING") or "/tmp/g1wave6")
ROOT = STAGING / "b" / "tmp" / "takeng-src" / "jni"
INLINE = re.compile(
    r'^(\s*)([A-Za-z_][\w.]*) = env(->|\.)New(Global|WeakGlobal)Ref\(env(->|\.)GetStaticObjectField\((\w+), env(->|\.)GetStaticFieldID\(\w+, "[^"]*", "[^"]*"\)\)\);\s*$')
FID = re.compile(
    r'^(\s*)jfieldID (\w+) = env(->|\.)GetStaticFieldID\((\w+), ("[^"]*"), ("[^"]*")\);\s*$')

total = 0
for dp, dn, fn in os.walk(ROOT):
    for f in sorted(fn):
        if not f.endswith(".cpp"):
            continue
        p = os.path.join(dp, f)
        lines = open(p, encoding="utf-8").read().split("\n")
        out = []
        i = 0
        fixed = 0
        while i < len(lines):
            m = INLINE.match(lines[i])
            if m and i + 3 < len(lines):
                ind, var, ar1, g, ar2, cls, ar3 = m.groups()
                fm = FID.match(lines[i + 1])
                gm = re.match(r"^\s*if\(!(\w+)\)\s*$", lines[i + 2])
                bm = re.match(r"^\s*return (false|JNI_ERR);\s*$", lines[i + 3])
                if fm and gm and bm and gm.group(1) == fm.group(2):
                    fid = fm.group(2)
                    ar = fm.group(3)
                    out.append(lines[i + 1])
                    out.append(lines[i + 2])
                    out.append(lines[i + 3])
                    out.append(f"{ind}{var} = env{ar}New{g}Ref(env{ar}GetStaticObjectField({cls}, {fid}));")
                    fixed += 1
                    i += 4
                    continue
            out.append(lines[i])
            i += 1
        if fixed:
            open(p, "w", encoding="utf-8").write("\n".join(out))
            print(f"  {os.path.relpath(p, ROOT)}: {fixed} inline statements rewritten")
            total += fixed
print(f"total rewritten: {total}")