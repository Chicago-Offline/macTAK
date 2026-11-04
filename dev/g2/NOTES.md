# dev/g2 — G2: map renders in a native macOS window (ANGLE on Metal)

## State (G2.1 — 2026-11-04): ANGLE occupies the GLES-stub slot; the engine's native renderer draws through it

The G2 branch point (H2 native/ANGLE vs H1 Swing/JOGL) is **decided for H2**
on evidence, not estimate: `libtakengine` + JNI glue, rebuilt with
`MACTAK_GLES=angle`, link against ANGLE's `libGLESv2` (EGL 1.5, GL ES 3.0,
Metal backend), and `GLMapView.render()` driven from a Zulu 8 JVM renders
frames into an EGL pbuffer with **zero GL errors** at ~3–5 ms/frame (512²,
incl. `glFinish`, no layers). R2 (ANGLE ≠ GLES-stub contract) is retired:
all 80 GL symbols the engine imports resolve against ANGLE's 829 exports;
the three contract issues found were on *our* side (below), not ANGLE's.

```sh
make angle          # stage ANGLE → dev/g2/angle-dist, run EGL triangle smoke
make build-angle    # engine + glue with ANGLE in the GLES slot (+ commo)
make test-render    # GLGlobe → EGL pbuffer → readback, PASS/FAIL
```

## What's here

| Path | Role |
|---|---|
| `fetch-angle.sh` | Stages `libEGL.dylib` + `libGLESv2.dylib` (arm64-thinned, `@rpath` install names, ad-hoc re-signed) into `angle-dist/` with `PROVENANCE.txt`. Source: `$MACTAK_ANGLE_SRC`, else an installed Electron app's bundled ANGLE (VS Code by default). |
| `smoke/egl-triangle.c` + `smoke/run.sh` | Pure C: Metal-platform display → pbuffer → ES3 context → shader → draw → readback. The "does ANGLE work on this box" gate, independent of the engine. |
| `jni-extra/mactak_egl.cpp` | `mactak.render.NativeEgl` — EGL context owner inside `libtakenginejni` (dlopen-based so stub-mode builds still link). `MACTAK_GL_DEBUG=1` installs a `GL_KHR_debug` callback; `=2` adds native backtraces for the first errors. |
| `render/src/mactak/render/` | `EglRenderContext` (the `RenderContext`/`RenderSurface` over `NativeEgl`, counterpart of upstream's `JOGLRenderContext`), `RenderSmoke` (the canary). |
| `render/src/com/atakmap/map/EngineLibrary.java` | macOS host bootstrap **shadowing** the engine jar's jre/JOGL one (see trap 4). |
| `render/src/android/{graphics,opengl,os}/` | Runtime implementations of `PointF/Point/Rect/RectF`, `Matrix`, `Environment` — the android-port transplant's bodies are `Stub!` (trap 5). |
| `render/run.sh` | Compiles the above ahead of the engine jar on the classpath and runs the canary. |
| `../g1/patches/glsl-es-version-on-angle.patch` | Engine emitted desktop `#version 330` on `__APPLE_OSX__`; ANGLE is real ES → `#version 300 es` (trap 2). |
| `../g1/patches/terraintile-guard-missing-attribs.patch` | `GLTerrainTile` enabled attrib `-1` for shader variants without normals/nodata/ecef → `GL_INVALID_VALUE` every frame (trap 3). |

`dev/g1/build-native.sh` grew `MACTAK_GLES=stub|angle` (default `stub`; CI
has no ANGLE). In `angle` mode the engine links `angle-dist/libGLESv2.dylib`
by path, gets `@loader_path` in its rpath, and `native-dist/` receives
`libEGL.dylib` + `libGLESv2.dylib` as siblings.

## Design decisions

- **ANGLE source for the spike = Electron's bundle.** Every Electron app
  ships upstream ANGLE (BSD-3) with the Metal backend; it is universal,
  hardened-runtime-signed, and dlopens its own `libGLESv2` from its directory.
  Provenance (app, Chrome/Electron version, sha256) is recorded per stage.
  The port builds ANGLE from source (gn/ninja, pinned commit) at G5 — a
  `make angle` consumer never notices. `MACTAK_ANGLE_SRC=dir` already takes
  a self-built pair.
- **EGL lives in the JNI glue, not the engine.** Upstream's engine C++ never
  touches EGL (0 references outside Android); context creation is the host's
  job. `mactak_egl.cpp` is that host piece, so `libtakengine` stays identical
  between stub and ANGLE modes except for which `libGLESv2` it binds.
- **Pbuffer first, window second.** The exit criterion for G2 is a window,
  but the risk was the engine↔ANGLE contract. A pbuffer isolates it; G2.2
  swaps the surface for a `CAMetalLayer`-backed window surface
  (`eglCreateWindowSurface` on a CALayer is supported by ANGLE's Metal
  backend) — the EGL/GL side above it does not change.
- **Shadow classes over a forked jar.** The engine jar is a pinned,
  checksummed transplant; putting `com.atakmap.map.EngineLibrary` and the
  `android.*` runtime types ahead of it on the classpath keeps the jar
  byte-identical and makes the macOS-host delta auditable (one directory).
  The proper fix is an engine built with a `macos` host source set instead
  of `jre`; tracked as a follow-up.

## Traps found on the way (do not rediscover these)

1. **The G0 GLES stub was silently stubbing libxml2.** `stub.c` carried 11
   `xml*` no-ops (`xmlReaderForFile`, `xmlTextReaderRead`, …) harvested
   from the first failing link — so `KMLDriverDefinition2::getStyle` has
   been a no-op in every G0/G1 build. Replacing the stub with ANGLE made the
   link fail honestly. Fix: link `/opt/homebrew/opt/libxml2/lib/libxml2.dylib`
   by full path (same runtime libgdal already loads), stubs removed, formula
   added to preflight + CI. Lesson: a symbol-harvested stub hides *every*
   missing dependency, not just the one you meant.
2. **`#version 330` on macOS.** `renderer/GL.h` picks desktop GLSL under
   `__APPLE_OSX__` (set by `port/Platform.h` for any non-iOS Apple build),
   assuming a desktop-GL-backed shim. ANGLE is real ES 3.0 and rejects it
   (`invalid version directive`, `'in' … GLSL ES 3.00 and above only`).
   Patch gates the desktop string behind `TE_GLSL_DESKTOP` (kept for an H1
   host). Symptom: every native shader program fails to compile, frames
   render only the clear.
3. **`glEnableVertexAttribArray(-1)` every frame.** `GLTerrainTile_begin/
   _end` enable/disable `aNormals`, `aNoDataFlag`, `aEcefVertCoords`
   unconditionally; the color shader variant lacks them so
   `glGetAttribLocation` returns `-1` → `GL_INVALID_VALUE` ×6 per frame.
   Android GLES drivers are lax; ANGLE validates. Patch guards on `>= 0`.
   Found via `MACTAK_GL_DEBUG=2` (KHR_debug + backtrace) in minutes — keep
   that switch.
4. **`EngineLibrary.initialize()` is the jre/JOGL host and it runs from
   `Globe.<clinit>`.** It (a) references `BouncyCastleProvider`
   (NoClassDefFoundError — `bcprov-jdk15on:1.67` now fetched sha256-pinned
   by `render/run.sh`), (b) tries to unpack natives through the android-port
   `Context` (throws, caught), and (c) its last statement registers a JOGL
   hook whose anonymous class references `javax.media.opengl.GL`
   (NoClassDefFoundError, *not* caught). Shadowed with a macOS version that
   keeps the `ConfigOptions` platform defaults and nothing else.
5. **android-port transplant bodies throw `Stub!` at runtime** (known from
   G0 — compile-time only). The render path hits `PointF`, `RectF`,
   `Matrix.setIdentityM`, `Environment.getExternalStorageDirectory`
   (`FileSystemUtils.<clinit>`). Re-implemented from the public API
   contract under `render/src/android/`. `Bitmap`/`Canvas`/`Paint`/
   `Typeface` (48/9/4/5 import sites) are *not* yet needed — no layers, no
   text — and will be the G2.2/G3 item (CoreGraphics-backed).
6. **`GLSLUtil.loadShaderSource` resolves Android raw resources.** The jar
   carries them at `resources/raw/*`; the macOS `EngineLibrary` unpacks that
   prefix to a temp dir and sets
   `glslutil.additional-shader-source-paths`. Handing it the stub `Context`
   instead NPEs in `Resources`.
7. **`EGL_DEFAULT_DISPLAY` + `eglGetPlatformDisplayEXT`**: the pinned Khronos
   header's macro is `EGL_CAST(EGLNativeDisplayType, 0)` → clang
   `-Wnon-literal-null-conversion` for the `void*` parameter; cast
   explicitly. The pinned `eglext.h` also predates
   `EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE` (0x3489) — defined locally.
8. **`strings | grep -m1` under `pipefail`** → SIGPIPE false failure (G1
   trap 2 again, different pipeline). `awk … {print; exit}`-style instead.
9. **ANGLE's `libEGL` is a thin loader**: it `dlopen`s `libGLESv2` from its
   own directory, so the pair must be staged side by side. `install_name_tool`
   invalidates Electron's signature — ad-hoc re-sign or dyld refuses to load.

## Known-benign noise

- `EGM96 file is not configured` on exit — geoid lookups unused by the smoke.
- `GdalGraphicUtils.cpp:1205 -Wmismatched-new-delete` — upstream, pre-existing.
- `[NativeFeatureDataStore2] leaked` in the layer canary — pre-existing (G1.3).

## Follow-ups (G2.2+)

- Window surface: `CAMetalLayer` in an `NSView` → `eglCreateWindowSurface`;
  AppKit main thread vs JVM render thread (R4) decided here. Candidate:
  Swift/AppKit shell launching the JVM in-process via `libjli` (`JNI_CreateJavaVM`).
- First pixels with content: basemap tiles + one `LayerStore` vector layer
  (G1.3) through `GLMapView.setBaseMap` / layer renderers → needs
  `android.graphics.Bitmap`/`BitmapFactory` runtime (CoreGraphics).
- Render goldens in CI (`ci-qa.md` tier 2): the pbuffer path is exactly the
  offscreen harness that plan assumed; a self-hosted macOS runner can stage
  ANGLE. Hosted CI stays on `MACTAK_GLES=stub`.
- Engine jar for a `macos` host source set (retire the shadow classes).
- Build ANGLE from source at a pinned commit (retire the Electron source).
