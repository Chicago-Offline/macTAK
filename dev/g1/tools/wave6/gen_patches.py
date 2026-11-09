#!/usr/bin/env python3
"""Wave-6 patch generation + verification (post-wave-5 baseline).

Staging layout, relative to $G1_STAGING (default /tmp/g1wave6):
a/ = gdal-drift + committed g1 patches except the wave-6 set applied
     (post-wave-5 baseline — what stage_baseline.py rebuilds from the repo)
b/ = same + all wave-6 edits (class-init guard sweep + residual closures)

Patch names are chosen so that, applied in glob (alphabetical) order by
build-native.sh, every wave-6 patch that touches a file also touched by a
committed g1/wave-5 patch sorts AFTER that patch:
  ioproviderfactory-guards-and-filecount  < ioproviderfactory-sweep-init-guards
  jglobe-listener-guards                  < jglobe-sweep-guards
  jglmapview-forwarder-guards             < jglmapview-sweep-guards
  jnilayer2-exception-clears              < jnilayer2-sweep-guards
  jnilayerspi2-exception-clears           < jnilayerspi2-sweep-guards
  jni-renderable-draw-clears-exception    < jni-renderable-sweep-guards
  managedrendercontext-guards             < managedrendercontext-sweep-guards
  projection-math-interop-guards          < projection-sweep-hygiene
Verifies by replaying gdal + the committed set (wave-6 patches included
when present) on a pristine copy and asserting the jni tree equals b/.

Reference tooling, not a CI gate. Writing patches needs the staged a/b
trees (authoring state, only where the wave ran); the verification half
rebuilds everything else from the repo. Paths resolve from this file's
location (<repo>/dev/g1/tools/wave6/); G1_REPO / G1_STAGING override
(see ../README.md); src/ must be populated per CONTRIBUTING.md.
"""
import os
import pathlib
import shutil
import subprocess
import sys

TOOLS_DIR = pathlib.Path(__file__).resolve().parent
REPO = pathlib.Path(os.environ.get("G1_REPO") or TOOLS_DIR.parents[3])
ROOT = pathlib.Path(os.environ.get("G1_STAGING") or "/tmp/g1wave6")
ENGINE = REPO / "src" / "takkernel" / "engine" / "src" / "main"
PATCHES = REPO / "dev" / "g1" / "patches"
JNI = "jni"

# wave-6 file -> patch name (only files a committed patch also touches)
OVERLAP = {
    "jni/interop/core/Interop.cpp": "projection-sweep-hygiene.patch",
    "jni/interop/core/ManagedProjection.cpp": "projection-sweep-hygiene.patch",
    "jni/interop/math/Interop.cpp": "projection-sweep-hygiene.patch",
    "jni/interop/model/Interop.cpp": "projection-sweep-hygiene.patch",
    "jni/interop/core/ManagedRenderContext.cpp": "managedrendercontext-sweep-guards.patch",
    "jni/jglobe.cpp": "jglobe-sweep-guards.patch",
    "jni/jglmapview.cpp": "jglmapview-sweep-guards.patch",
    "jni/jioproviderfactory.cpp": "ioproviderfactory-sweep-init-guards.patch",
    "jni/interop/renderer/core/ManagedGLLayer2.cpp": "jnilayer2-sweep-guards.patch",
    "jni/interop/renderer/core/ManagedGLLayerSpi2.cpp": "jnilayerspi2-sweep-guards.patch",
    "jni/interop/renderer/core/ManagedGLMapRenderable2.cpp": "jni-renderable-sweep-guards.patch",
}
SPECIAL = {
    "jni/common.cpp": "common-onload-classloader-guards.patch",
    "jni/interop/renderer/ManagedTextFormat2.cpp": "textformat2-class-init-consumer-guards.patch",
}
# the wave-6 set, as patch filenames — stage_baseline.py rebuilds tree b

def rels_touched():
    a_root = ROOT / "a"
    b_root = ROOT / "b"
    b_src = b_root / "tmp" / "takeng-src"
    if not b_src.is_dir():
        return []
    out = []
    for p in sorted(b_src.rglob("*")):
        if p.is_file():
            rel = str(p.relative_to(b_root / "tmp" / "takeng-src"))
            if not (a_root / "tmp" / "takeng-src" / rel).exists() or \
               (a_root / "tmp" / "takeng-src" / rel).read_bytes() != p.read_bytes():
                out.append(rel)
    return out

def main():
    groups = {}
    for rel in rels_touched():
        name = SPECIAL.get(rel) or OVERLAP.get(rel) or "jni-class-init-guards.patch"
        groups.setdefault(name, []).append(rel)

    a_root = ROOT / "a" / "tmp" / "takeng-src"
    b_root = ROOT / "b" / "tmp" / "takeng-src"
    if groups:
        for name in sorted(groups):
            parts = []
            for rel in sorted(groups[name]):
                r = subprocess.run(["diff", "-u",
                                    "--label", f"a/tmp/takeng-src/{rel}",
                                    "--label", f"b/tmp/takeng-src/{rel}",
                                    str(a_root / rel), str(b_root / rel)],
                                   capture_output=True, text=True)
                assert r.returncode == 1, f"diff rc={r.returncode} for {rel}: {r.stderr}"
                parts.append(r.stdout)
            (PATCHES / name).write_text("".join(parts))
            print(f"wrote {name}: {len(groups[name])} file(s), "
                  f"{sum(len(p.splitlines()) for p in parts)} lines")
    else:
        print(f"no staged a/b trees under {ROOT} — patch regeneration skipped "
              "(authoring state only exists where the wave ran); "
              "stage_baseline.py rebuilds them from the repo")

    if not ENGINE.is_dir():
        sys.exit(f"engine tree missing at {ENGINE} — populate src/ per "
                 "CONTRIBUTING.md (upstream pin @ 889eee292c43d3d2eafdd1f2fbf378ad5cd89ecc)")
    # verify: pristine + gdal + committed (incl. wave-6 when present) == tree b
    VERIFY = ROOT / "verify"
    if (VERIFY / "engine" / ".staged6").exists():
        print("verify tree already staged")
    else:
        if VERIFY.exists():
            shutil.rmtree(VERIFY)
        VERIFY.mkdir(parents=True)
        print("staging pristine engine copy …")
        subprocess.run(["rsync", "-a", f"{ENGINE}/", f"{VERIFY}/engine/"], check=True)
        GDAL = sorted((REPO / "dev" / "spike" / "g0" / "patches").glob("gdal-drift-*.patch"))
        G1 = sorted(PATCHES.glob("*.patch"))
        for p in GDAL + G1:
            r = subprocess.run(["patch", "--forward", "--batch", "--silent", "-p3",
                                "-d", str(VERIFY / "engine"), "-i", str(p)],
                               capture_output=True, text=True, timeout=300)
            assert r.returncode == 0, f"patch failed: {p.name}\n{r.stdout}\n{r.stderr}"
        print(f"applied {len(GDAL)} gdal-drift + {len(G1)} g1 patches "
              "(the committed set, wave-6 patches included when present) in glob order")
        (VERIFY / "engine" / ".staged6").write_text("wave6\n")

    # assert verify jni tree == b jni tree (patches strip a/tmp/takeng-src/ via -p3)
    B_JNI = ROOT / "b" / "tmp" / "takeng-src" / "jni"
    if B_JNI.is_dir() and any(B_JNI.iterdir()):
        bad = 0
        for p in sorted(B_JNI.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(ROOT / "b" / "tmp" / "takeng-src")
            v = VERIFY / "engine" / rel
            if not v.exists() or v.read_bytes() != p.read_bytes():
                print(f"  MISMATCH: {rel}")
                bad += 1
        assert bad == 0, f"{bad} jni files differ between verify tree and b"
        print("VERIFY OK: replay of gdal + committed + wave-6 patches reproduces tree b exactly")
    else:
        print("tree b not staged — clean-apply check only; run "
              "stage_baseline.py --rebuild-b for the byte comparison against b")


if __name__ == "__main__":
    main()
# with exactly these (applied last, in glob order, after the baseline set)
WAVE6_PATCHES = sorted({*OVERLAP.values(), *SPECIAL.values(),
                        "jni-class-init-guards.patch"})
