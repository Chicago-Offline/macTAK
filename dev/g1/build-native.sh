#!/usr/bin/env bash
#
# G1.0 — deterministic native build of the takkernel C++ engine (macOS arm64).
#
# Reproduces the G0 spike result (dev/spike/g0/NOTES.md) without touching the
# evidence tree or /tmp:
#   src/takkernel/engine/src/main  →  dev/g1/build/engine (throwaway copy)
#   + dev/spike/g0/patches/gdal-drift-*.patch (3-file GDAL API-drift set)
#   + GLES stub (dev/spike/g0/gles-stub) as the libGLESv2 slot (ANGLE lands at G2)
#   + nlohmann-json stand-in for tinygltf
#   → libtakengine.dylib staged in dev/g1/native-dist/
#   → canary: dlopen + Catch2 subset of engine/src/test/cpp
#
# Everything lands under g1/build (wiped each run) for reproducibility.
# A clean build is ~4–5 min at -j$(hw.ncpu).
set -euo pipefail

DEV="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # <repo>/dev — this script lives at dev/g1/
ROOT="$(cd "$DEV/.." && pwd)"                             # <repo>
G1="$DEV/g1"
WORK="$G1/build"
OUT="$G1/native-dist"
ENGINE_MAIN="$ROOT/src/takkernel/engine/src/main"
TESTS_DIR="$ROOT/src/takkernel/engine/src/test/cpp"
JOBS="$(sysctl -n hw.ncpu)"

log() { printf '\n[g1] %s\n' "$*"; }
die() { printf '\n[g1] FATAL: %s\n' "$*" >&2; exit 1; }

# --- preflight -----------------------------------------------------------
[[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] \
  || die "requires macOS arm64 (got $(uname -s) $(uname -m))"
for t in cmake rsync clang patch python3 brew; do
  command -v "$t" >/dev/null 2>&1 || die "missing tool: $t"
done
[[ -d "$ENGINE_MAIN/cpp" ]] \
  || die "engine tree missing — run: (cd $ROOT/src && git submodule update --init --recursive)"
[[ -d "$TESTS_DIR" ]] || die "engine C++ tests missing at $TESTS_DIR"
for f in proj geos gdal libspatialite minizip nlohmann-json laszip googletest; do
  brew list --versions "$f" >/dev/null 2>&1 \
    || die "brew formula missing: $f
       → brew install proj geos gdal libspatialite laszip minizip nlohmann-json googletest"
done

log "preflight ok (root=$ROOT, -j$JOBS)"

# --- work tree -----------------------------------------------------------
rm -rf "$WORK"
mkdir -p "$WORK/gles-stub" "$WORK/overlay" "$OUT"

log "copying engine tree → $WORK/engine"
rsync -a --exclude .git "$ENGINE_MAIN/" "$WORK/engine/"

log "applying GDAL-drift patches (-p3 → cpp/...)"
for p in "$DEV"/spike/g0/patches/gdal-drift-*.patch "$G1"/patches/*.patch; do
  patch --forward --silent -p3 -d "$WORK/engine" < "$p"
  log "  applied $(basename "$p")"
done

log "building GLES stub (throwaway; ANGLE replaces it at G2)"
clang -arch arm64 -dynamiclib -O2 \
  -o "$WORK/gles-stub/libGLESv2.dylib" "$DEV/spike/g0/gles-stub/stub.c"

log "tinygltf overlay (nlohmann-json stand-in)"
mkdir -p "$WORK/overlay/tinygltf"
cp "$(brew --prefix nlohmann-json)/include/nlohmann/json.hpp" \
   "$WORK/overlay/tinygltf/json.hpp"

log "generating wrapper CMakeLists"
cat > "$WORK/wrapper-CMakeLists.txt.in" <<'CMAKE'
cmake_minimum_required(VERSION 3.5)
cmake_policy(SET CMP0079 NEW)
project(takengine LANGUAGES C CXX)

set(SRC @ROOT@)
set(WORK @WORK@)

add_subdirectory(${WORK}/engine/cpp engine-cpp)

# SQLITE_HAS_CODEC needs SQLCipher; upstream's #else branch degrades
# gracefully (warning + ignore passphrase). Revisit at the encryption rung.
get_target_property(_takdefs takengine COMPILE_DEFINITIONS)
list(REMOVE_ITEM _takdefs SQLITE_HAS_CODEC)
set_target_properties(takengine PROPERTIES COMPILE_DEFINITIONS "${_takdefs}")

if(TARGET takengine)
    # Homebrew deps replace the ttp-dist bundle the macOS branch expects.
    target_include_directories(takengine PRIVATE
        /opt/homebrew/include
        ${SRC}/src/khronos/OpenGL/api   # GLES3/gl3.h (GLES-stub slot)
        ${SRC}/src/khronos/EGL/api      # EGL + KHR/khrplatform.h
        ${SRC}/src/stl-soft/include     # platformstl/* (util/IO*.cpp)
        ${WORK}/overlay                 # tinygltf/json.hpp stand-in
    )
    target_link_directories(takengine PRIVATE /opt/homebrew/lib)

    # libkml's renamed minizip -> Homebrew minizip (same signatures)
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

    # libLAS is dead upstream — exclude the LAS TUs (self-contained SPI).
    get_target_property(_srcs takengine SOURCES)
    set(_kept)
    foreach(_s IN LISTS _srcs)
        if(NOT _s MATCHES "LASScene|las/LAS\\.cpp")
            list(APPEND _kept ${_s})
        endif()
    endforeach()
    set_target_properties(takengine PROPERTIES SOURCES "${_kept}")

    # Drop ttp-dist libs; GLESv2 -> stub; append system sqlite3.
    get_target_property(_libs takengine LINK_LIBRARIES)
    list(REMOVE_ITEM _libs las las_c charset GLESv2)
    list(APPEND _libs ${WORK}/gles-stub/libGLESv2.dylib)
    list(APPEND _libs sqlite3)
    set_target_properties(takengine PROPERTIES LINK_LIBRARIES "${_libs}")

    # ---------------------------------------------------------------
    # G1.0 canary: googletest subset of engine/src/test/cpp,
    # pure-logic TUs only (no GL, no fixtures). Grows as the port
    # matures.
    # ---------------------------------------------------------------
    get_target_property(_takincs takengine INCLUDE_DIRECTORIES)
    find_package(GTest CONFIG REQUIRED)
    add_executable(takengine-tests
        ${SRC}/src/takkernel/engine/src/test/cpp/MathUtilsTests.cpp
        ${SRC}/src/takkernel/engine/src/test/cpp/GeoPoint2Tests.cpp
        ${SRC}/src/takkernel/engine/src/test/cpp/TessellationTests.cpp
        ${SRC}/src/takkernel/engine/src/test/cpp/StringBuilderTests.cpp
        ${SRC}/src/takkernel/engine/src/test/cpp/GeometryFactoryTests.cpp
        ${SRC}/src/takkernel/engine/src/test/cpp/DatasetDescriptorTests.cpp
        ${SRC}/src/takkernel/engine/src/test/cpp/TakEngineTestsLogging.cpp
        # PRITests.cpp: upstream-broken at this pin — includes PRI.h which is
        # not in the tree, and needs a .NTF fixture. Revisit at G1.5.
        # NOTE: TakEngineTests{Hexdump,Resources}.cpp are NOT compiled:
        # Resources.cpp is Windows-only (Windows.h); Hexdump is link-unused
        # by the TUs above. Logging.cpp IS needed (TestLogger, portable).
    )
    target_include_directories(takengine-tests PRIVATE
        ${_takincs}
        /opt/homebrew/include
        ${SRC}/src/khronos/OpenGL/api
        ${SRC}/src/khronos/EGL/api
        ${SRC}/src/stl-soft/include
        ${WORK}/overlay
        ${SRC}/src/takkernel/engine/src/main/cpp
        ${SRC}/src/takkernel/engine/src/test/cpp
    )
    target_compile_definitions(takengine-tests PRIVATE
        ${_takdefs}
    )
    target_link_libraries(takengine-tests PRIVATE takengine GTest::gtest_main)
    set_target_properties(takengine-tests PROPERTIES
        BUILD_RPATH "${CMAKE_BINARY_DIR}/engine-cpp;${WORK}/gles-stub"
    )
endif()
CMAKE
sed -e "s|@ROOT@|$ROOT|g" -e "s|@WORK@|$WORK|g" \
    "$WORK/wrapper-CMakeLists.txt.in" > "$WORK/CMakeLists.txt"
rm "$WORK/wrapper-CMakeLists.txt.in"

# --- configure + build ---------------------------------------------------
log "configuring (RelWithDebInfo, arm64, Makefiles)"
cmake -S "$WORK" -B "$WORK/cmake" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_RULE_MESSAGES=OFF

log "building libtakengine.dylib + test canary (-j$JOBS, ~4–5 min clean)"
cmake --build "$WORK/cmake" -- -j"$JOBS"

# --- canary --------------------------------------------------------------
log "canary 1/2: dlopen"
python3 - "$WORK/cmake/engine-cpp/libtakengine.dylib" <<'PY'
import ctypes, sys
ctypes.CDLL(sys.argv[1])
print("[g1] dlopen OK:", sys.argv[1])
PY

log "canary 2/2: engine C++ tests (GTest)"
"$WORK/cmake/takengine-tests" --gtest_output=xml:"$OUT/takengine-tests.xml"

# --- stage ---------------------------------------------------------------
log "staging → $OUT"
cp "$WORK/cmake/engine-cpp/libtakengine.dylib" "$OUT/"
cp "$WORK/gles-stub/libGLESv2.dylib" "$OUT/libGLESv2-stub.dylib"
file "$OUT/libtakengine.dylib"
otool -L "$OUT/libtakengine.dylib" | sed 's/^/  /'
ls -lh "$OUT"

log "G1.0 OK — dylib built, canary green, artifacts in g1/native-dist/"
