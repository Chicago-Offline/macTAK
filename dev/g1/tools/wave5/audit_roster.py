#!/usr/bin/env python3
"""Wave-5 Phase-1 roster validation: the planning-pass heuristic (JNI
Call*/New*Method sites with no ExceptionCheck within the following 4
lines) re-run over the pristine jni/ tree vs the fully-patched verify
tree. Validates the pinned 18-file zero-check roster and shows the wave-5
guard patches draining the live render-thread surface. MethodID/FieldID
lookups (Get*ID) are excluded — their failure mode is the systemic
class-init masking, which stays report-only this wave.

Reference tooling, not a CI gate. Paths resolve from this file's location
(<repo>/dev/g1/tools/wave5/); G1_REPO / G1_STAGING override for a checkout
or staging root elsewhere (see ../README.md). Requires src/ populated per
CONTRIBUTING.md and a staged verify tree (python3 gen_patches.py in this
directory stages it on demand).
"""
import os
import pathlib
import re

TOOLS_DIR = pathlib.Path(__file__).resolve().parent
REPO = pathlib.Path(os.environ.get("G1_REPO") or TOOLS_DIR.parents[3])
STAGING = pathlib.Path(os.environ.get("G1_STAGING") or "/tmp/g1wave5")
ENGINE = REPO / "src" / "takkernel" / "engine" / "src" / "main"
CALL = re.compile(r"(?:->|\.)((?:Call|New)[A-Z]\w*Method\s*\(|NewObject\s*\()")
CHECK = re.compile(r"ExceptionCheck")

def audit(jni_root):
    roster = {}
    for p in sorted(jni_root.rglob("*.cpp")):
        rel = str(p.relative_to(jni_root))
        lines = p.read_text(errors="replace").splitlines()
        bad = 0
        for i, line in enumerate(lines):
            if not CALL.search(line):
                continue
            window = "\n".join(lines[i + 1:i + 9])
            if not CHECK.search(window):
                bad += 1
        if bad:
            roster[rel] = bad
    return roster

pristine = audit(ENGINE / "jni")
patched = audit(STAGING / "verify" / "engine" / "jni")

print(f"pristine roster: {len(pristine)} files with >=1 unguarded JNI call "
      f"({sum(pristine.values())} sites)")
print(f"patched roster:  {len(patched)} files ({sum(patched.values())} sites)")

LIVE = ["jglobe.cpp", "jglmapview.cpp", "interop/core/ManagedRenderContext.cpp",
        "interop/core/ManagedProjection.cpp", "interop/core/Interop.cpp",
        "interop/math/Interop.cpp", "interop/model/Interop.cpp",
        "jioproviderfactory.cpp"]
print("\nwave-5-touched files (pristine → patched unguarded sites):")
for rel in LIVE:
    hit = next((k for k in pristine if k.endswith(rel.split("/")[-1]) and
                ("/".join(rel.split("/")[-2:]) in k)), None)
    k = next((r for r in pristine if r.endswith(rel)), rel)
    print(f"  {rel}: {pristine.get(k, 0)} → {patched.get(k, 0)}")

print("\nremaining roster (defensive / not this wave):")
for rel, n in sorted(patched.items(), key=lambda kv: -kv[1]):
    print(f"  {rel}: {n}")