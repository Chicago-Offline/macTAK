#!/usr/bin/env python3
"""Wave-5 patch regeneration + verification.

The full generator (apply_edits.py, not committed — it completed its edits
and only its patch-writing step produced wrong headers) left the staged
trees intact: <staging>/a/ = pristine engine files laid out as
tmp/takeng-src/<rel>, <staging>/b/ = same with all wave-5 edits applied.
This regenerates the 7 patches from those trees with explicit relative
labels, then replays build-native.sh's apply step on a fresh pristine copy
and asserts every wave-5 change landed.

Reference tooling, not a CI gate. Staged trees a/ and b/ are authoring
state (they exist only on the machine that ran the wave); regeneration is
skipped without them and the verification half still runs — it stages
pristine + gdal + the committed patches itself. Paths resolve from this
file's location (<repo>/dev/g1/tools/wave5/); G1_REPO / G1_STAGING
override (see ../README.md); src/ must be populated per CONTRIBUTING.md.
"""
import os
import pathlib
import shutil
import subprocess

TOOLS_DIR = pathlib.Path(__file__).resolve().parent
REPO = pathlib.Path(os.environ.get("G1_REPO") or TOOLS_DIR.parents[3])
ROOT = pathlib.Path(os.environ.get("G1_STAGING") or "/tmp/g1wave5")
ENGINE = REPO / "src" / "takkernel" / "engine" / "src" / "main"
PATCHES = REPO / "dev" / "g1" / "patches"
SHADERS = "cpp/renderer/feature/GLBatchGeometryShaders.cpp"
MV2 = "cpp/renderer/core/GLMapView2.cpp"
JGLOBE = "jni/jglobe.cpp"
JGLMAPVIEW = "jni/jglmapview.cpp"
MRC = "jni/interop/core/ManagedRenderContext.cpp"
MPROJ = "jni/interop/core/ManagedProjection.cpp"
CORE = "jni/interop/core/Interop.cpp"
MATH = "jni/interop/math/Interop.cpp"
MODEL = "jni/interop/model/Interop.cpp"
JIO = "jni/jioproviderfactory.cpp"

PATCH_SET = [
    ("glbatchshaders-dead-store.patch", [SHADERS]),
    ("glmapview2-srid-capability.patch", [MV2]),
    ("jglobe-listener-guards.patch", [JGLOBE]),
    ("jglmapview-forwarder-guards.patch", [JGLMAPVIEW]),
    ("managedrendercontext-guards.patch", [MRC]),
    ("projection-math-interop-guards.patch", [MPROJ, CORE, MATH, MODEL]),
    ("ioproviderfactory-guards-and-filecount.patch", [JIO]),
]


def gen_patch(name, rels):
    a_root = ROOT / "a" / "tmp" / "takeng-src"
    b_root = ROOT / "b" / "tmp" / "takeng-src"
    parts = []
    for rel in rels:
        r = subprocess.run(["diff", "-u",
                            "--label", f"a/tmp/takeng-src/{rel}",
                            "--label", f"b/tmp/takeng-src/{rel}",
                            str(a_root / rel), str(b_root / rel)],
                           capture_output=True, text=True)
        assert r.returncode == 1, f"diff for {rel}: rc={r.returncode} {r.stderr}"
        parts.append(r.stdout)
    (PATCHES / name).write_text("".join(parts))
    print(f"wrote {name} ({sum(len(p.splitlines()) for p in parts)} lines)")


if (ROOT / "a" / "tmp" / "takeng-src").is_dir() and \
        (ROOT / "b" / "tmp" / "takeng-src").is_dir():
    for name, rels in PATCH_SET:
        gen_patch(name, rels)
else:
    print("staged a/b trees not found — patch regeneration skipped "
          "(authoring state only exists where the wave ran; see ../README.md)")

VERIFY = ROOT / "verify"
if (VERIFY / "engine" / ".staged").exists():
    print("reusing staged+patched verify tree")
else:
    if VERIFY.exists():
        shutil.rmtree(VERIFY)
    VERIFY.mkdir(parents=True)
    print("staging pristine engine copy for verify …")
    subprocess.run(["rsync", "-a", f"{ENGINE}/", f"{VERIFY}/engine/"], check=True)

    GDAL = sorted((REPO / "dev" / "spike" / "g0" / "patches").glob("gdal-drift-*.patch"))
    G1 = sorted(PATCHES.glob("*.patch"))
    for p in GDAL + G1:
        print(f"  applying {p.name} …")
        r = subprocess.run(["patch", "--forward", "--batch", "--silent", "-p3",
                            "-d", str(VERIFY / "engine"), "-i", str(p)],
                           capture_output=True, text=True, timeout=120)
        assert r.returncode == 0, f"patch failed: {p.name}\n{r.stdout}\n{r.stderr}"
    print(f"applied {len(GDAL)} gdal-drift + {len(G1)} g1 patches cleanly in glob order")
    (VERIFY / "engine" / ".staged").write_text("wave5\n")


def patched(rel):
    return (VERIFY / "engine" / rel).read_text()


def pristine(rel):
    return (ROOT / "a" / "tmp" / "takeng-src" / rel).read_text()


def count(text, needle):
    n = text.count(needle)
    assert n > 0, f"missing in patched tree: {needle[:70]!r}"
    return n


def added(rel, needle, expected):
    got = count(patched(rel), needle) - pristine(rel).count(needle)
    assert got == expected, f"{rel}: expected +{expected} {needle[:50]!r}, got +{got}"


s = patched(SHADERS)
assert 'shader.aColor = glGetUniformLocation' not in s, "dead store still present"
s = patched(MV2)
assert 'tile.data_proj.srid == state.drawSrid' not in s, "SRID selection still present"
count(s, "pick the terrain local frame from the active shader")
added(MV2, 'if(shader.aEcefVertCoords >= 0)', 3)  # +1 here, +2 from wave-4 attrib guards
added(JGLOBE, "env->ExceptionDescribe()", 9)
added(JGLMAPVIEW, "env->ExceptionDescribe()", 2)
added(MRC, "env->ExceptionDescribe()", 15)
added(MRC, "env.ExceptionDescribe()", 1)  # ctor helper takes JNIEnv&
added(MPROJ, "env->ExceptionDescribe()", 4)
added(CORE, "env->ExceptionDescribe()", 1)  # GeoPoint helper takes JNIEnv*
added(CORE, "env.ExceptionDescribe()", 2)  # MapSceneModel marshals take JNIEnv&
added(MATH, "env.ExceptionDescribe()", 2)
added(MATH, "if(!value)", 3)
added(MATH, "if(!mrect)", 1)
added(MODEL, "env->ExceptionClear()", 4)
added(JIO, "env->ExceptionCheck()", 3)  # remove, mkdirs, createTempFile getAbsolutePath
s = patched(JIO)
count(s, "code = this->isDirectory(&b, path);\n        if(b) {\n            std::vector")
assert s.count("return success ? TE_Ok : TE_Err;\n") == 2, "remove/mkdirs shape changed"

print("ALL WAVE-5 PATCHES VERIFIED AGAINST PRISTINE TREE")