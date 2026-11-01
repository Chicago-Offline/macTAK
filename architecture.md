# ATAK-CIV Architecture & Measured Inventory

Source of truth: `src/` = `deptofdefense/AndroidTacticalAssaultKit-CIV` @ `889eee2` (v4.6.0.5, 2024-10-18; takkernel 0.59.1). All figures measured on this tree (method notes in Appendix).

## 1. Top-level layout

| Directory | Size | Contents (verified) |
|---|---|---|
| `atak/` | 141 MB | **The Android app.** Gradle project at `atak/ATAK`; flavors: `civ`, mil, fms; `app/src/{main,common,civSmall,small,test,androidTest}` |
| `takkernel/` | 37 MB | **The map engine + shared platform code.** "Java and C++ code shared across TAK applications" |
| `takengine/` | 29 MB | Vendored third-party only (`tinygltf`, `tinygltfloader`); STLSoft expected at `thirdparty/stlsoft`. 264,985 C/C++ LOC |
| `takthirdparty/` | 470 MB | Makefile build system + distfiles for all C++ deps; targets: android-{arm64-v8a,armeabi-v7a,x86}, ios-*, **macos-64**, linux-amd64, win32/64 |
| `depends/` | 282 MB | Pre-modified source tarballs + prebuilts: `gdal-2.4.4-mod`, `assimp-4.0.1-mod`, `libLAS-1.8.2-mod`, `LASzip-3.4.3-mod`, `tinygltf-2.4.1-mod`, `tinygltfloader-0.9.5-mod` |
| `commoncommo/` | 5.9 MB | **Comms library (C++)**: `core`, `jni`, **`objc`**, `win32`, `support`, `test`, **`commoncommo.xcodeproj`** |
| `takcot/` | 4.2 MB | **CoT XSD schemas + examples only** (58 .xsd) — no code. (Corrects earlier assumption of a portable CoT C++ lib here) |
| `takproto/` | 60 K | Protobuf protocol definitions |
| `plugin-examples/` | 1.2 MB | Plugin SDK examples (11,865 LOC Java) + `pluginsdk.zip` |
| `libunwindstack-ndk/` | 1.4 MB | Android-only crash unwinder (33,550 C/C++) — dropped in any macOS port |
| `jnicrash/`, `scripts/`, `atak-gradle-takdev/` | small | Crash glue; CI build scripts; plugin-dev Gradle tooling |
| Submodules (3, all public) | — | `stl-soft` (STLSoft-1.9), `khronos/OpenGL`, `khronos/EGL` registries |

**LOC totals (measured):** 750,106 Java (excl. `depends/`) + 795,397 C/C++ = **~1.55M LOC**.

## 2. Per-module inventory

| Module | Java LOC | C/C++ LOC | Notes |
|---|---|---|---|
| `atak/ATAK/app` | **492,324** (`src/main`) + **453** (`src/common`) | 0 | The `common` split is negligible — the app is ~100% Android UI/logic |
| `takkernel/engine` | 186,804 (`main`, 770 files) · 5,424 (`android`, 12 files) · 1,443 (`jre`, 10 files) | in takkernel total | Engine is platform-neutral at its core |
| `takkernel/{shared,port,annotation}` | 14,599 + 12,856 + 182 | — | `port` = "Portability API to reduce platform specific dependencies" (60 files) |
| `takkernel/engine/src/main/cpp` | — | 451,170 (takkernel total) | Includes `port/` (thin: String/Platform/collections), `renderer`, `raster`, `feature`, `model`, `formats`, `db`, `elevation`, `spi` |
| `takengine/` | — | 264,985 | Vendored third-party glue only |
| `commoncommo/` | 5,305 | 44,828 | JNI + **ObjC** + win32 bindings |
| `plugin-examples/` | 11,865 | 0 | SDK surface sample |
| `libunwindstack-ndk/` | 0 | 33,550 | Android-only |

## 3. takkernel dependency graph (from its README)

```
annotation → port → engine → shared → aarbundle / controlled
```

Gradle builds each sub-project for **either** Android (AAR) **or** desktop Java (JAR) via `gradlew publishToMavenLocal -PkernelBuildTarget=java|android`. The README states the `java` target exists "for TAKX" — TAK's closed-source Windows desktop client. **Consequence: a desktop (non-Android) engine host is a shipped, supported configuration, not a research project.**

### Engine source sets (`takkernel/engine/src/`)

| Set | Purpose | Evidence |
|---|---|---|
| `main/` | Platform-neutral engine (Java 186,804 + C++ 451,170 under `main/cpp`) | C++ organized: `core, currency, db, elevation, feature, formats, math, model, port, raster, renderer, simd, spi, thread, util` |
| `android/` | Android bindings | Java only, **5,424 LOC / 12 files** |
| `jre/` | Desktop-Java bindings: `JOGLRenderSurface`, `JOGLRenderContext`, `GLMapSurface`, `EngineLibrary` (loads `jawt`), `AtakCertificateDatabase` | **1,443 LOC / 10 files** — the TAKX host path |

### C++ platform branching (`engine/src/main/cpp/CMakeLists.txt`)

`AndroidSources.cmake` · `LinuxSources.cmake` · **`MacOSSources.cmake`** (`elseif(APPLE)` branch) · `WindowsSources.cmake` · `CommonSources.cmake`.

`MacOSSources.cmake` (verbatim substance): `DEFS = RTTI_ENABLED, TE_GLES_VERSION=3, SQLITE_HAS_CODEC`; `LIBS = GLESv2` (via a **`GLES-stub`** lib-dir slot) `+ spatialite, gdal, geos, geos_c, charset, proj, z, las, las_c`. I.e., the macOS engine build already exists on paper, consuming GL ES 3 through a replaceable stub library.

**Renderer coupling measured:** only **7 files** in `main/cpp` `#include <GLES…>`; **zero** non-Android engine C++ references EGL. Rendering contexts are host-injected (JNI/JOGL side). This is what makes the ANGLE-on-Metal strategy an interface substitution, not a rewrite.

### JNI surface

~**1,430 `native` methods** across the tree; engine JNI = **186 files** (`main/jni`, incl. `atakJNI.cpp`, per-class bindings `j*.cpp`). Commo offers JNI and ObjC bindings.

## 4. Android coupling (measured)

| Where | Files with `import android.*` | Total `android.*` imports |
|---|---|---|
| `atak/` app | 1,429 | ~8,000 |
| `takkernel/` | 220 | ~400 (mostly engine `android` set + shared UI-neutral helpers) |
| `plugin-examples/` | 60 | — |
| Total tree | **1,731 files** | **8,592** |

Hot app packages (files importing android): `preferences` (45), `gui` (42), `util` (40), `maps` (33), `importfiles/sort` (33), `widgets` (29), `user` (27), `routes` (25), `layers` (21). This is the rewrite surface of P4/P5.

## 5. Build & CI reality

- **Toolchain pins:** Java 8 (CI container `openjdk:8-jdk`), Android NDK **r12b (2016)**, CMake **3.14.7**, Conan **1.59.0**, Gradle + AGP (targetSdk 29 / minSdk 21).
- **CI (`.github/workflows/assemble-sdk.yml`, `release-sdk.yml`):** Ubuntu container → LFS checkout → `scripts/prebuild.sh` (extracts the `depends/*.tar.gz` mods; `make -C takthirdparty TARGET=android-{armv7,arm64,x86}` for spatialite/commo/gdal/assimp) → conan `export-pkg` → `gradlew generateJniHeaders assembleCivSdk` → publishes a **plugin SDK** (`pluginsdk/atak.apk`, `main.jar`, javadoc, `atak-gradle-takdev.jar`).
- **takthirdparty:** Makefile-based, per-target `target-config/*.mk`; `host-detect.mk` recognizes Darwin (`PLATFORM=darwin`); **`macos-64.mk` exists** (references a MrSID `macos-64` distfile). BUILDING.md warns: "only confirmed to work on Linux."
- **takkernel build** additionally expects TAK maven/conan artifact repo credentials (`takRepoMavenUrl` etc.) in `local.properties` — the Android APK path avoids this by building everything from source; a standalone engine build may need artifact substitution (risk R5 in `pathway-c.md`).

## 6. Licensing & versions

- **License: GPL-3.0** (root `LICENSE.md`) — any macOS port derived from this tree must be GPL-3.0 and source-published. Version history: ATAK 4.6.0.5 (repo effectively static since Oct 2024); takkernel 0.59.1 (semver'd).

## Appendix — inventory method

Commands (run at `889eee2`): per-module LOC via `find … -print0 | xargs -0 cat | wc -l` per extension class; coupling via `grep -rl '^import android\.'` counts; JNI via `grep -rn ' native '`; GL surface via `#include <GLES` / `egl[A-Z]` file counts; platform branches via CMake inspection. A `find` precedence bug in the first pass was caught and corrected (C++ LOC initially under-reported; all figures above are from the corrected pass). Clone kept unmodified for citation.

