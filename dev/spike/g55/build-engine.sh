#!/usr/bin/env bash
#
# Spike 1 (docs/rebase-study-5.5.md, next step 1): does the 5.5 engine
# (takkernel 8.30.5) build on macOS arm64 with Homebrew GDAL 3, without
# 3D Tiles?
#
# The core-engine half of dev/g1/build-native.sh, pointed at a 5.5 tree:
#   <5.5>/takkernel/engine/src/main  →  dev/spike/g55/build/engine (copy)
#   + dev/spike/g55/patches (the 5.5 series: gdal-drift-* first, then the
#     rest, sorted, at --fuzz=0 — build-native.sh's order and rule)
#   + GLES stub from <5.5>/khronos, nlohmann-json stand-in for tinygltf
#   → libtakengine.dylib, or the list of what failed to compile
#
# Differences from build-native.sh, each a spike decision (NOTES.md):
#   - formats/c3dt/ (cesium-native + draco, 3D Tiles) is left out, as G0
#     left out the LAS drivers; MacOSSources.cmake has no cesium-native
#     or draco paths, so a stock 5.5 macOS build cannot compile it
#   - no JNI glue, no tests, no canaries: the glue is the next iteration
#   - ninja -k 0: one run lists every failing source, not just the first
#
# usage: build-engine.sh UPSTREAM_55
#        UPSTREAM_55  root of an atak-civ checkout at 5.5.1.10 (needs
#                     takkernel/engine/src/main and khronos/)
# out:   dev/spike/g55/build/build.log    full compiler output
#        dev/spike/g55/build/summary.txt  failing sources, first error each
# exit:  0 libtakengine linked; 1 compile or link failures; 2 usage or
#        preflight
set -euo pipefail

G55="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # <repo>/dev/spike/g55
DEV="$(cd "$G55/../.." && pwd)"                        # <repo>/dev
WORK="$G55/build"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

log() { printf '\n[g55] %s\n' "$*"; }
die() { printf '\n[g55] FATAL: %s\n' "$*" >&2; exit 2; }

[[ $# -eq 1 ]] || die "usage: build-engine.sh UPSTREAM_55"
UPSTREAM="$(cd "$1" && pwd)" || die "no such directory: $1"
ENGINE_MAIN="$UPSTREAM/takkernel/engine/src/main"
KHRONOS="$UPSTREAM/khronos"

# --- preflight -----------------------------------------------------------
[[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] \
  || die "requires macOS arm64 (got $(uname -s) $(uname -m))"
for t in cmake ninja rsync clang patch python3 brew; do
  command -v "$t" >/dev/null 2>&1 || die "missing tool: $t"
done
[[ -d "$ENGINE_MAIN/cpp" ]] || die "no 5.5 engine at $ENGINE_MAIN"
[[ -f "$KHRONOS/OpenGL/api/GLES3/gl3.h" ]] || die "no khronos headers at $KHRONOS"
# build-native.sh's list + mdbtools: 5.5 adds an MS Access database
# provider (formats/msaccess) that links libmdb
for f in proj geos gdal libspatialite minizip nlohmann-json libxml2 mdbtools; do
  brew list --versions "$f" >/dev/null 2>&1 \
    || die "brew formula missing: $f
       → brew install proj geos gdal libspatialite minizip nlohmann-json libxml2 mdbtools ninja"
done
log "preflight ok (upstream=$UPSTREAM, -j$JOBS)"

# --- work tree -----------------------------------------------------------
rm -rf "$WORK"
mkdir -p "$WORK/gles-stub" "$WORK/overlay/tinygltf"

log "copying engine tree → $WORK/engine"
rsync -a --exclude .git "$ENGINE_MAIN/" "$WORK/engine/"

log "applying the 5.5 patch series (-p3, --fuzz=0)"
shopt -s nullglob
series=("$G55"/patches/gdal-drift-*.patch)
for p in "$G55"/patches/*.patch; do
  [[ "$(basename "$p")" == gdal-drift-* ]] || series+=("$p")
done
shopt -u nullglob
(( ${#series[@]} > 0 )) || die "no patches in $G55/patches"
for p in "${series[@]}"; do
  patch --forward --silent --no-backup-if-mismatch --fuzz=0 -p3 -d "$WORK/engine" < "$p" \
    || die "patch failed: $(basename "$p")"
done
log "  applied ${#series[@]} patches"

log "building GLES stub against 5.5's khronos headers"
clang -arch arm64 -dynamiclib -O2 \
  -I"$KHRONOS/OpenGL/api" -I"$KHRONOS/EGL/api" \
  -o "$WORK/gles-stub/libGLESv2.dylib" "$DEV/g2/jni-extra/mactak_gles_stub.c"

cp "$(brew --prefix nlohmann-json)/include/nlohmann/json.hpp" "$WORK/overlay/tinygltf/json.hpp"

log "generating wrapper CMakeLists"
cat > "$WORK/CMakeLists.txt" <<CMAKE
cmake_minimum_required(VERSION 3.5)
cmake_policy(SET CMP0079 NEW)
project(takengine55 LANGUAGES C CXX)

add_subdirectory($WORK/engine/cpp engine-cpp)

# SQLCipher rung, as build-native.sh
get_target_property(_takdefs takengine COMPILE_DEFINITIONS)
list(REMOVE_ITEM _takdefs SQLITE_HAS_CODEC)
set_target_properties(takengine PROPERTIES COMPILE_DEFINITIONS "\${_takdefs}")

target_include_directories(takengine PRIVATE
    /opt/homebrew/include
    $KHRONOS/OpenGL/api
    $KHRONOS/EGL/api
    $WORK/overlay
)
target_link_directories(takengine PRIVATE /opt/homebrew/lib)

# libkml's renamed minizip -> Homebrew minizip, as build-native.sh
target_compile_definitions(takengine PRIVATE
    libkml_unzClose=unzClose
    libkml_unzOpen=unzOpen
    libkml_unzOpenCurrentFile=unzOpenCurrentFile
    libkml_unzReadCurrentFile=unzReadCurrentFile
    libkml_unzGetGlobalInfo=unzGetGlobalInfo
    libkml_unzGetGlobalComment=unzGetGlobalComment
    libkml_unzGoToFirstFile=unzGoToFirstFile
    libkml_unzGoToNextFile=unzGoToNextFile
    libkml_unzLocateFile=unzLocateFile
    libkml_unzGetCurrentFileInfo=unzGetCurrentFileInfo
)
target_link_libraries(takengine PRIVATE minizip)

# LAS (dead upstream, as build-native.sh) and c3dt (3D Tiles, this spike)
get_target_property(_srcs takengine SOURCES)
set(_kept)
foreach(_s IN LISTS _srcs)
    if(NOT _s MATCHES "LASScene|las/LAS\\\\.cpp|/formats/c3dt/")
        list(APPEND _kept \${_s})
    endif()
endforeach()
set_target_properties(takengine PROPERTIES SOURCES "\${_kept}")

# ttp-dist libs out, GLES stub in, Homebrew sqlite and libxml2 by full
# path (one runtime each, as build-native.sh)
get_target_property(_libs takengine LINK_LIBRARIES)
list(REMOVE_ITEM _libs las las_c charset GLESv2)
list(APPEND _libs
    $WORK/gles-stub/libGLESv2.dylib
    /opt/homebrew/opt/sqlite/lib/libsqlite3.dylib
    /opt/homebrew/opt/libxml2/lib/libxml2.dylib)
set_target_properties(takengine PROPERTIES LINK_LIBRARIES "\${_libs}")
CMAKE

log "configuring (Ninja)"
cmake -S "$WORK" -B "$WORK/cmake" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=arm64 >"$WORK/configure.log" 2>&1 \
  || { tail -n 40 "$WORK/configure.log" >&2; die "cmake configure failed (log: $WORK/configure.log)"; }

log "building with ninja -k 0 (every failing source, not just the first)"
set +e
ninja -C "$WORK/cmake" -k 0 -j "$JOBS" >"$WORK/build.log" 2>&1
build_rc=$?
set -e

python3 "$G55/summarize-build.py" "$WORK/build.log" "$WORK/engine" >"$WORK/summary.txt"
cat "$WORK/summary.txt"

if (( build_rc == 0 )) && [[ -f "$WORK/cmake/engine-cpp/libtakengine.dylib" ]]; then
  log "PASS: libtakengine.dylib linked from the 5.5 tree"
  exit 0
fi
log "FAIL: ninja exit $build_rc (full log: $WORK/build.log)"
exit 1
