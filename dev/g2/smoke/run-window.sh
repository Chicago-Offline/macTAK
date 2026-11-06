#!/usr/bin/env bash
#
# G2 M3.0 window-surface smoke: build + run egl-triangle-window against the
# staged ANGLE runtime.
#
# Proves the path the pbuffer smoke (run.sh) leaves unproven: NSWindow →
# CAMetalLayer → eglCreateWindowSurface → draw → pre-swap readback →
# on-screen presents. The run needs a GUI session (it opens a real window
# for ~3 s), so it is a dev-Mac manual gate (`make smoke-window`); CI
# compiles it only.
#
# Usage: dev/g2/smoke/run-window.sh [--compile-only] [angle-dist-dir]
#   --compile-only  build and link only; do not open a window
# Env:
#   MACTAK_SMOKE_PPM  path for the pre-swap framebuffer artifact (PPM)
# Prereqs: dev/g2/fetch-angle.sh (staged libEGL/libGLESv2) and the pinned
#   upstream tree at src/ for the Khronos headers (see docs/ci-qa.md).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # <repo>/dev/g2/smoke
G2="$(cd "$HERE/.." && pwd)"                           # <repo>/dev/g2
ROOT="$(cd "$G2/../.." && pwd)"                        # <repo>

COMPILE_ONLY=0
ANGLE="$G2/angle-dist"
for arg in "$@"; do
  case "$arg" in
    --compile-only) COMPILE_ONLY=1 ;;
    *) ANGLE="$arg" ;;
  esac
done
ANGLE="$(cd "$ANGLE" && pwd)"
KHR="$ROOT/src/khronos"

[[ -f "$ANGLE/libEGL.dylib" && -f "$ANGLE/libGLESv2.dylib" ]] \
  || { echo "FATAL: ANGLE missing in $ANGLE — run dev/g2/fetch-angle.sh first" >&2; exit 1; }
[[ -f "$KHR/EGL/api/EGL/egl.h" && -f "$KHR/OpenGL/api/GLES3/gl3.h" ]] \
  || { echo "FATAL: khronos headers missing under $KHR (check out the pinned upstream tree at src/ — see docs/ci-qa.md)" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

clang -arch arm64 -O1 -Wall -fobjc-arc \
  -I"$KHR/EGL/api" -I"$KHR/OpenGL/api" \
  -o "$WORK/egl-triangle-window" "$HERE/egl-triangle-window.m" \
  -framework AppKit -framework QuartzCore -framework Metal \
  -L"$ANGLE" -lEGL -lGLESv2 -Wl,-rpath,"$ANGLE"

if [ "$COMPILE_ONLY" = 1 ]; then
  echo "run-window: compile-only OK"
  exit 0
fi

OUT_PPM="${MACTAK_SMOKE_PPM:-}"
"$WORK/egl-triangle-window" ${OUT_PPM:+"$OUT_PPM"}
# Gate on the evidence artifact: the binary only WARNs on an unwritable
# artifact path, so an empty/missing PPM must fail the run here (#39).
[[ -z "$OUT_PPM" || -s "$OUT_PPM" ]] \
  || { echo "FATAL: artifact $OUT_PPM missing/empty (see the binary's WARN above)" >&2; exit 1; }
