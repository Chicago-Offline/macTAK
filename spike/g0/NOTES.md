# G0 spike record — engine builds on macOS arm64

**Done 2026-11-02.** Upstream pin `889eee2` (takkernel 0.59.1), evidence tree untouched — all fixes live in the build wrapper + a 3-file patch set applied to a throwaway copy.

**Toolchain:** AppleClang 21.0.0, CMake 4.4.4, Command Line Tools only (no full Xcode), macOS arm64.

## Result

Full engine C++ target compiles (~460 TU builds) and links into:

```
libtakengine.dylib — 9.6 MB, Mach-O 64-bit arm64 shared library
  links: libspatialite.8, libgdal.39 (3.13), libgeos.3.15, libproj.25 (9.9),
         libminizip.1, libz, libsqlite3 (system), libGLESv2 (throwaway stub)
```

Homebrew deps: `proj geos gdal libspatialite laszip minizip nlohmann-json` (all bottled, arm64). Khronos (`EGL`, `OpenGL` registries) and `stl-soft` come from the tree's own submodules.

## Reproduce

1. `brew install proj geos gdal libspatialite laszip minizip nlohmann-json`
2. `cd macTAK/src && git submodule update --init --recursive`
3. Copy the engine tree (`rsync -a --exclude .git`), apply `patches/*.patch` to the copy
4. Stand-in for tinygltf: copy nlohmann-json's `include/nlohmann/` into an overlay dir as `overlay/tinygltf/`
5. Configure with `wrapper-CMakeLists.txt` (points `add_subdirectory` at the patched copy), build with `-DCMAKE_OSX_ARCHITECTURES=arm64`

Clean build ≈ 4–5 min at `-j10`; link-only re-runs are seconds.

## What the wrapper does (and why)

| Wrapper action | Reason |
|---|---|
| `cmake_minimum_required(VERSION 3.5)` + `CMP0079 NEW` | Engine CMakeLists has **no** `cmake_minimum_required` — hard error under CMake ≥ 4.0 (CMP0000) |
| `/opt/homebrew/include` + `/opt/homebrew/lib` | Replaces the `ttp-dist` bundle the macOS branch expects (R3 mitigation: Homebrew deps) |
| `khronos/{OpenGL,EGL}/api` includes | `GLES3/gl3.h` etc. for the macOS branch's GLES-stub slot |
| `stl-soft/include` | `platformstl/*` headers used by `util/IO.cpp`, `util/IO2.cpp`, `raster/pfps/PfpsUtils.cpp` |
| `tinygltf/json.hpp` overlay | tinygltf isn't packaged; its bundled nlohmann `json.hpp` stand-in satisfies `formats/mbtiles/MVTFeatureDataStore.cpp`, `model/ZipCommentInfo.cpp` |
| 10 `libkml_unz*→unz*` defines + `-lminizip` | Engine uses libkml's renamed minizip; Homebrew minizip has identical signatures |
| Drop `SQLITE_HAS_CODEC` | Codec API needs SQLCipher; upstream's own `#else` branch degrades gracefully (warning + ignore passphrase). Revisit at the encryption rung via `brew sqlcipher` |
| Exclude `formats/las/LAS.cpp`, `model/LASScene*.{h,cpp}` TUs | libLAS is dead (no brew formula); LAS SPI is self-contained so the library links without it. Replacement strategy: GDAL's LAS/LAZ driver or a LASlib port — open decision |
| Link surgery: drop `las las_c charset`; `GLESv2` → generated stub; append `sqlite3` | ttp-dist libs don't exist; system sqlite3 is not transitively linked via brew spatialite |

## Source patches (6 lines, 3 files — all GDAL API drift)

| File:line | Error | Patch |
|---|---|---|
| `raster/gdal/GdalDatasetProjection.cpp:418,441,507` | `GetMetadata` now returns `CSLConstList` | `const_cast<char **>(...)` |
| `formats/gdal/GdalDatasetProjection2.cpp:35` | `GDALClose` returns `int`; unique_ptr deleter is `void(*)(void*)` | `reinterpret_cast<void (*)(void *)>(GDALClose)` |
| `formats/ogr/OGR_Content2.cpp:109` | `OGRLayer::GetSpatialRef()` const since GDAL 3.9 | `const_cast<OGRSpatialReference *>(...)` |
| `formats/ogr/OGR_Content2.cpp:745` | `OGRFeature::GetFieldDefnRef()` const since GDAL 3.9 | `const_cast<OGRFieldDefn *>(...)` |

Const-casts are spike-minimal (keep downstream untouched); the port can refactor properly.

## GLES-stub

Generated no-op definitions for the ~121 GLES functions the engine references (`gles-stub/stub.c`, harvested from the first failing link). This is throwaway link-glue for G0 only — **G2 swaps in ANGLE-on-Metal** at the same slot, which is the architecture's designed renderer boundary.

## Error catalog (classes seen, in order encountered)

1. CMP0000 (no minimum version) → wrapper
2. `GLES3/gl3.h` missing → khronos registry includes
3. `sqlite3_key` undeclared (`SQLITE_HAS_CODEC`) → define dropped
4. `platformstl/*` missing → stl-soft include
5. `tinygltf/json.hpp` missing → nlohmann overlay
6. `liblas/capi/liblas.h` missing → LAS TUs excluded
7. `libkml_unz*` undeclared → minizip name shims
8. GDAL drift: `CSLConstList`, const `GetSpatialRef`/`GetFieldDefnRef`, `GDALClose` deleter → 6-line patch set
9. Link: `GLESv2` missing → stub; `sqlite3_stmt_busy` undefined → explicit `-lsqlite3`

Every class matches a risk the docs predicted (R2 toolchain drift, R3 dependency substitution). **No new class of unknown surfaced.**

## Not done here / next

- **G0 step 4 (Java):** `gradlew publishToMavenLocal -PkernelBuildTarget=java` — wrapper is Gradle 6.9.1 (needs a JDK ≤ 16; upstream CI used 8; `openjdk@8` install pending).
- **Smoke load:** `dlopen`/JVM-load of the dylib is G1's entry test (real GL only needed when the renderer starts).
- **ANGLE** replaces the stub at G2.
- MrSID/proprietary raster distfiles: never reached — no such failure appeared.
