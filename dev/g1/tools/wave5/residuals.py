#!/usr/bin/env python3
"""Enumerate residual unguarded JNI sites in the wave-5-touched files,
post-patch — the honest list for dev/g1/NOTES.md.

Reference tooling, not a CI gate. Reads the staged+patched verify tree
(G1_STAGING, default /tmp/g1wave5); stage it with gen_patches.py in this
directory if it isn't there.
"""
import os
import pathlib
import re

TOOLS_DIR = pathlib.Path(__file__).resolve().parent
STAGING = pathlib.Path(os.environ.get("G1_STAGING") or "/tmp/g1wave5")
CALL = re.compile(r"(?:->|\.)((?:Call|New)[A-Z]\w*Method\s*\(|NewObject\s*\()")
CHECK = re.compile(r"ExceptionCheck")
V = STAGING / "verify" / "engine" / "jni"

for rel in ["jglobe.cpp", "interop/core/ManagedProjection.cpp",
            "interop/core/Interop.cpp", "jioproviderfactory.cpp",
            "jglmapview.cpp"]:
    p = V / rel
    lines = p.read_text().splitlines()
    print("==", rel)
    for i, line in enumerate(lines):
        if CALL.search(line) and not CHECK.search("\n".join(lines[i + 1:i + 5])):
            print(f"  :{i+1}: {line.strip()[:110]}")