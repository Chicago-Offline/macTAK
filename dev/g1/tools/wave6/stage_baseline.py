#!/usr/bin/env python3
"""Rebuild the wave-6 staging trees from the repo alone — no leftover
/tmp state required.

a/  pristine engine + gdal-drift patches + every committed g1 patch
    except the wave-6 set → <staging>/a/tmp/takeng-src/ — the
    post-wave-5 baseline the wave-6 audit (class_init_audit.py) reads
b/  pristine + gdal + the full committed set incl. wave-6 →
    <staging>/b/tmp/takeng-src/ — the post-sweep tree, for gen_patches.py's
    byte comparison

Prerequisites: src/ populated per CONTRIBUTING.md (upstream pin
889eee292c43d3d2eafdd1f2fbf378ad5cd89ecc), and — for --rebuild-b — the
wave-6 patch set present in dev/g1/patches (PR #64; main has them once
it merges). Trees only ever grow: re-runs are no-ops unless --force.

Reference tooling, not a CI gate. Paths resolve from this file's
location; G1_REPO / G1_STAGING override (see ../README.md).
"""
import argparse
import pathlib
import shutil
import subprocess
import sys

TOOLS_DIR = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))
from gen_patches import ENGINE, PATCHES, REPO, ROOT, WAVE6_PATCHES

A = ROOT / "a"
B = ROOT / "b"


def apply_patch(root, patch):
    r = subprocess.run(
        ["patch", "--forward", "--batch", "--silent", "-p3",
         "-d", str(root), "-i", str(patch)],
        capture_output=True, text=True, timeout=300)
    assert r.returncode == 0, f"patch failed: {patch.name}\n{r.stdout}\n{r.stderr}"


def stage(path, marker, patches, label):
    if (path / marker).exists() and not ARGS.force:
        print(f"{label}: already staged ({path})")
        return
    if path.exists():
        shutil.rmtree(path)
    (path / "tmp" / "takeng-src").mkdir(parents=True)
    print(f"{label}: rsyncing pristine engine …")
    subprocess.run(["rsync", "-a", f"{ENGINE}/",
                    f"{path / 'tmp' / 'takeng-src'}/"], check=True)
    print(f"{label}: applying {len(patches)} patches …")
    for p in patches:
        apply_patch(path / "tmp" / "takeng-src", p)
    (path / marker).write_text("stage_baseline\n")
    print(f"{label}: done ({len(patches)} patches applied)")


def main():
    global ARGS
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rebuild-b", action="store_true",
                    help="also rebuild tree b (baseline + the wave-6 patches)")
    ap.add_argument("--force", action="store_true",
                    help="rebuild even when the staged markers exist")
    ARGS = ap.parse_args()
    if not ENGINE.is_dir():
        sys.exit(f"engine tree missing at {ENGINE} — populate src/ per "
                 "CONTRIBUTING.md (upstream pin @ 889eee292c43d3d2eafdd1f2fbf378ad5cd89ecc)")
    gdal = sorted((REPO / "dev" / "spike" / "g0" / "patches").glob("gdal-drift-*.patch"))
    wave6 = {PATCHES / n for n in WAVE6_PATCHES if (PATCHES / n).exists()}
    missing = [n for n in WAVE6_PATCHES if not (PATCHES / n).exists()]
    baseline = sorted(p for p in PATCHES.glob("*.patch") if p not in wave6)
    stage(A, ".stageda", gdal + baseline, "a/ (post-wave-5 baseline)")
    if not ARGS.rebuild_b:
        print("(use --rebuild-b to also rebuild tree b)")
        return
    if missing:
        print(f"--rebuild-b: {len(missing)} wave-6 patch(es) not in "
              f"dev/g1/patches ({', '.join(missing[:3])} …): b/ would equal a/. "
              "Check out / merge the wave-6 patch set first (PR #64).")
        return
    stage(B, ".stagedb", gdal + sorted(PATCHES.glob("*.patch")),
          "b/ (post-wave-6 tree)")


if __name__ == "__main__":
    main()