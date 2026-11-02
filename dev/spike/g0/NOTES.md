# G0 spike record — engine builds on macOS arm64

**Done 2026-11-02.** Upstream pin `889eee2` (takkernel 0.59.1), evidence tree untouched — all fixes live in the build wrapper + a 3-file patch set applied to a throwaway copy.

**Toolchain:** AppleClang 21.0.0, CMake 4.4.4, Command Line Tools only (no full Xcode), macOS arm64.

## Result

Full engine C++ target compiles (~460 TU builds) and links into:

```text
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

## G0 step 4 (Java publish) — DONE 2026-11-02

`./gradlew publishToMavenLocal` on Zulu 8 (`1.8.0_512`, arm64-native) with Gradle 6.9.1 (wrapper runs Apple-Silicon-native; `kernelBuildTarget` defaults to `java`, no flags). **BUILD SUCCESSFUL — all five modules published** to `~/.m2/repository/gov/tak/kernel/`: `takkernel-{annotation,port,shared,engine,all}-0.59.1` (jar + sources + pom; engine 3.7 MB / 1,847 classes incl. vendored commons-lang; `all` is a 0-class aggregate as designed).

### What had to change (all in-repo, minimal, flag-gated)

1. **`gov.tak.gradle.cmake:gradle-cmake-plugin` (root `build.gradle` buildscript classpath):** TAK-artifactory-only — no public repo resolves it (Maven Central: no `gov.tak`; `repo.tak.gov`/`artifactory.tak.gov`: NXDOMAIN). Now gated behind `-PuseTakRepo=true` (upstream-restorable); without it the desktop build skips the native hook — the dylib comes from this spike's wrapper instead.
2. **`settings.gradle` upstream bug:** `if(new File(settingsDir,'controlledaarbundle'))` — a `File` is always truthy in Groovy, so public-source builds died evaluating the nonexistent `:controlledaar` project. Added `.exists()` (the sibling `controlled` guard already had it; upstream ships those dirs so never noticed).
3. **`gov.tak.support:android-port:3.1.0` — compile-time, not runtime-only as first guessed** (port `FontMetrics` uses `android.graphics.Paint/Typeface`; port `JOGLGLES` delegates to `android.opengl.JOGLGLES`; engine instantiates `android.content.Context`). Unobtainable publicly: not on Central, not in the ATAK-CIV 5.5.1.8 SDK zip (plugin-dev only — its `atak.apk` carries 6.x-era classes whose `android.maps.*` namespace no longer matches 0.59.1). **Transplant installed to mavenLocal** (`gov/tak/support/android-port/3.1.0/`; mavenLocal is first in repo order):
   - `android.jar` from the public Google platform-33 zip — framework signatures (Paint/Typeface/Bitmap/Uri/GLES/sqlite; all Java-8 bytecode);
   - TAK classes from the public ATAK-CIV 5.5.1.8 `atak.apk` via dex2jar v2.4 (com.atakmap.* etc., 18.6k classes);
   - hand-written concrete compile stubs where the kernel outgrew the framework: `android.opengl.JOGLGLES` (init only), `android.content.{Context,ContentResolver}`, `android.content.pm.{PackageManager,ApplicationInfo}`, `android.content.res.{Resources,AssetManager}`, `android.util.DisplayMetrics`. Compile-time only — bodies return null; **not for runtime use**; real desktop runtime needs TAK's android-port from the gated artifactory.
   - POM declares `slf4j-api:1.7.30` so port's `GLES30` resolves LoggerFactory (upstream got it transitively).
   The `com.atakmap.android.maps.*` classes shared/engine import are **in-tree** (engine/src/main/java) — no android.maps stubs needed.
4. **Engine's takRepo-only deps** (gles-jogl-c, takthirdparty-rt, gdal, gdaldata, jassimp, libLAS): still behind `-PuseTakRepo=true` — runtime natives, zero compile-time imports in kernel java.

### Verification

- `~/.m2/repository/gov/tak/kernel/takkernel-{annotation,port,shared,engine,all}/0.59.1/` — jars + `-sources` + poms ✓
- **G1 entry test already passes:** `ctypes.CDLL('/tmp/takeng/engine-cpp/libtakengine.dylib')` → **dlopen OK, headless** (9.6 MB Mach-O arm64; dyld resolves everything — GLES stub + system libs suffice; no GL context needed at load). The dylib lives in the volatile `/tmp/takeng` cmake dir — rebuild via `wrapper-CMakeLists.txt` (recipe above) when it evaporates.

### Ops lessons (runbook)

- Backgrounding gradle under this agent's shell gets the client process SIGTSTP'd when a command batch ends — launch via a `start_new_session=True` double-detach (python Popen), never plain `nohup … &`.
- Gradle serves mavenLocal in place (no modules-2 copy): after overwriting an installed artifact, run once with `--refresh-dependencies` (or bump the version).
- jogamp's `-main` jars are thin manifests; compile against `jogl-all` / `gluegen-rt`.

### Still open

- **ANGLE** replaces the GLES stub at G2.
- MrSID/proprietary raster distfiles: never reached — no such failure appeared.
