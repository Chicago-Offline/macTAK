# dev/g2 — G2: map renders in a native macOS window (ANGLE on Metal)

## State (G2.3 — 2026-11-05): an offline MBTiles basemap renders under the vector layer

`make test-render` now also builds a synthetic graticule basemap
(`fixtures/make-basemap.sh` → `build/fixtures/sf-graticule.mbtiles`, zoom
10–14, EPSG:3857, Homebrew `gdal` CLI) and renders it under the GeoJSON
fixture through the engine's raster pipeline:
`PersistentRasterDataStore` → `MobileImageryRasterLayer2` → `GLQuadTileNode4`
textures on ANGLE. Zero GL errors; every pixel has content; the graticule's
coordinate labels line up with the vector layer's geometry. Section "G2.3"
below.

## State (G2.2 — 2026-11-04): first map content — a GeoJSON FeatureLayer renders through the engine on ANGLE/Metal

`make test-render` now renders `dev/g1/headless/test/fixtures/sf-landmarks.geojson`
into the pbuffer: polygons (Golden Gate Park, Presidio, Alcatraz), the two
bridge LineStrings through the engine's anti-aliased line shader, and point
labels ("Ferry Building") through native `GLLabelManager` → Java2D glyphs.
Zero GL errors. The Java-side engine (`GLBatchGeometryRenderer` et al.) runs
against a **generated `android.opengl.GLES20/GLES30` → ANGLE JNI shim** and
**Java2D-backed `android.graphics`** (`Bitmap`, `Canvas`, `Paint`, `Typeface`,
`BitmapFactory`…). Section "G2.2" below.

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
make test-render    # GLGlobe → EGL pbuffer → readback: bare, GeoJSON layer, MBTiles basemap, PASS/FAIL
# one-offs:
bash dev/g2/render/run.sh --layer FILE.geojson --at 37.7955,-122.3937 --res 1 --out /tmp/f.png
bash dev/g2/render/run.sh --basemap FILE.mbtiles --layer FILE.geojson --out /tmp/b.png
```

## What's here

| Path | Role |
|---|---|
| `fetch-angle.sh` | Stages `libEGL.dylib` + `libGLESv2.dylib` (arm64-thinned, `@rpath` install names, ad-hoc re-signed) into `angle-dist/` with `PROVENANCE.txt`. Source: `$MACTAK_ANGLE_SRC`, else an installed Electron app's bundled ANGLE (VS Code by default). Downloads retry through transient release-CDN failures (504s). |
| `smoke/egl-triangle.c` + `smoke/run.sh` | Pure C: Metal-platform display → pbuffer → ES3 context → shader → draw → readback. The "does ANGLE work on this box" gate, independent of the engine. |
| `jni-extra/mactak_egl.cpp` | `mactak.render.NativeEgl` — EGL context owner inside `libtakenginejni` (dlopen-based so stub-mode builds still link). `MACTAK_GL_DEBUG=1` installs a `GL_KHR_debug` callback; `=2` adds native backtraces for the first errors. |
| `render/src/mactak/render/` | `EglRenderContext` (the `RenderContext`/`RenderSurface` over `NativeEgl`, counterpart of upstream's `JOGLRenderContext`), `RenderSmoke` (the canary). |
| `render/src/com/atakmap/map/EngineLibrary.java` | macOS host bootstrap **shadowing** the engine jar's jre/JOGL one (see trap 4). |
| `render/src/android/{graphics,opengl,os}/` | Runtime implementations of `PointF/Point/Rect/RectF`, `Matrix`, `Environment` — the android-port transplant's bodies are `Stub!` (trap 5). |
| `render/run.sh` | Compiles the above ahead of the engine jar on the classpath and runs the canary. |
| `../g1/patches/glsl-es-version-on-angle.patch` | Engine emitted desktop `#version 330` on `__APPLE_OSX__`; ANGLE is real ES → `#version 300 es` (trap 2). |
| `../g1/patches/terraintile-guard-missing-attribs.patch` | `GLTerrainTile` enabled attrib `-1` for shader variants without normals/nodata/ecef → `GL_INVALID_VALUE` every frame (trap 3). |
| `../g1/patches/megatexture-release-erase-while-iterating.patch` | `GLMegaTexture::release()/clear()` erased map nodes through the iterator they were walking → SIGSEGV on `GLMapView.release()` (trap 10). |

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
10. **`GLMegaTexture::release()` is a use-after-free in upstream.** Both it
    and `clear()` loop `for (it = tiles.begin(); …; it++) releaseTile(it->first)`
    and `releaseTile` does `tiles.erase(entry)` — `it++` then reads a freed
    `std::map` node. Harmless whenever the allocator leaves the node intact
    (every local run), SIGSEGV in `GLMegaTexture::release()+0xb8` on the
    first `macos-26` CI run (Apple Paravirtual GPU, different alloc/free
    pattern) — *after* the smoke had already printed PASS-worthy numbers.
    Patch snapshots the keys before releasing.

## Known-benign noise

- `EGM96 file is not configured` on exit — geoid lookups unused by the smoke.
- `GdalGraphicUtils.cpp:1205 -Wmismatched-new-delete` — upstream, pre-existing.
- `[NativeFeatureDataStore2] leaked` in the layer canary — pre-existing (G1.3).

## Follow-ups (G2.2+) — planning status after G2.3 (2026-11-05)

M1–M2.3 are landed: ANGLE in the GLES slot (PR #17, `90aa443`), a GeoJSON
layer and an offline MBTiles basemap rendering beneath it (PR #18,
`71fcbce`), CI green on both; review follow-ups (issue #19) are landed in
PR #21 — shim drift gate, cache eviction on `glDeleteProgram`, debug-path
`FindClass` checks. The next gate is **M3, the window surface**; the rest
is ordered roughly by when it bites.

- **Window surface (M3 — next).** `CAMetalLayer` in an `NSView` →
  `eglCreateWindowSurface`; AppKit main thread vs JVM render thread (R4)
  decided here. Candidate: Swift/AppKit shell launching the JVM in-process
  via `libjli` (`JNI_CreateJavaVM`). The EGL/GL stack above the surface
  does not change (see "Pbuffer first, window second" above).
- **Real imagery (G2.4) pulls three engine patches** — latent upstream
  bugs documented but not yet exercised by the synthetic fixture, plus the
  native line renderer's integer attributes:
  - `GLMegaTexture::shareTile` has `other,shareWith = this;` (comma
    operator: sets `this->shareWith = this`, never `other.shareWith`), so
    after the first share every call from that megatexture returns
    `TE_IllegalState`. Still open after G2.3 — the synthetic graticule
    fixture never shares front/back tiles, and megatexture sharing is the
    first thing large raster sets hit. Fix in the megatexture patch once
    front/back tile sharing runs with real content (unchanged condition;
    keep it visible).
  - `GLQuadTileNode4.resolveTexture` composites child textures with
    `uColor` set on whatever program happens to be current (G2.3
    known-benign noise) — bind `core.shader.handle` before compositing.
    Same patch window; Android drivers hide both today, ANGLE surfaces
    both.
  - `GLBatchGeometryRenderer3/4` feed `in int` attributes through
    `glVertexAttribPointer` (trap 12's pattern, already redirected in the
    shim) — patch when the native line renderer is on the path.
  Real imagery also re-opens trap 17 (the `org.gdal` JNI for
  `GdalTileReader` once a GeoTIFF raster is on the path).
- **Shim/tooling debt (issue #19):** landed in PR #21 — a `make
  gen-gles-shim` target plus a `make check-gen-gles-shim` CI drift gate so
  the four GENERATED artifacts can't drift from the generator; cache
  eviction on `glDeleteProgram` (GL reuses program ids; note programs
  created and deleted by native engine code bypass the shim — they only
  enter the cache if a Java GL call runs while one is current, which none
  does today); `FindClass` null-checks in the two debug paths
  (`attribMinusOne`, `mactak_egl.cpp` KHR_debug). Still open:
  `RenderSmoke.countNonClear`'s r,g,b < 8 black threshold vs. a future
  dark basemap (the graticule fixture cannot trip it) — switch to a
  sentinel-vs-cleared comparison or a luminance histogram when the first
  dark imagery lands. (`--settle`'s 3000 ms wall-clock margin did race the
  async layer query once hosted runners slowed — 2026-11-05: the layer smoke
  failed with content=0 on a ~520 ms/frame rerun and on a milliseconds-per-frame
  PR run alike; the smoke now polls the readback and keeps rendering until
  content appears, capped at 5× settle / min 15 s.)
- **CI ops — hosted macOS runners (2026-11-05):** pool contention
  queued jobs up to ~16 min. Queued is *waiting*, not stuck — GitHub
  neither fails nor cancels a job for queue time. The day's two
  cancellation events (one queued job; later both remaining queued
  jobs, killed in one instant) match a manual run-level cancel, and
  `gh run rerun <id> --failed` recovered each time — rerun jobs got
  runners immediately and passed. Leave queued jobs alone.
- ~~First pixels with content~~ — done, G2.2 below. ~~Still open: a
  **basemap**~~ — done, G2.3 below (MBTiles raster fixture generated
  offline by the gdal CLI).
- Render goldens in CI (`ci-qa.md` tier 2): the pbuffer path is exactly the
  offscreen harness that plan assumed, and hosted `macos-26` already runs
  it on ANGLE/Metal (Apple Paravirtual device, ~170 ms/frame vs ~3–5 ms on
  an M-series — budget frame counts accordingly). CI uploads the frames as
  artifacts today; a pixel-level golden diff is still open. A self-hosted
  runner is only needed for speed, not for GL.
- Engine jar for a `macos` host source set (retire the shadow classes).
- Build ANGLE from source at a pinned commit (retire the Electron source;
  roadmap has this at G5).

## G2.2 — first map content: a GeoJSON FeatureLayer through the engine on ANGLE

`dev/g2/render/run.sh --layer FILE` opens the file through the G1.3 feature
stack (`LayerStore` → `PersistentDataSourceFeatureDataStore2`), wraps it in
`FeatureLayer3` (via `Adapters.adapt`), adds it to the `Globe`, frames the
camera on the store's bounds (`GLMapView.lookAt`) and renders until the
asynchronous layer query has settled. PASS = zero GL errors **and**
non-clear pixels. The default fixture gives Golden Gate Park / Presidio /
Alcatraz polygons, the two bridges as anti-aliased lines, and a labelled
point ("Ferry Building") drawn by native `GLLabelManager` through Java2D
glyph rendering.

### What's new

| Path | Role |
|---|---|
| `gen-gles-shim.py` → `render/src/android/opengl/GLES20\|GLES30.java` + `jni-extra/mactak_gles.c` | **Generated** runtime for `android.opengl.GLES20/30`: 342 JNI bodies forwarding to the linked `libGLESv2`, derived from `javap` of the transplant jar (API surface) × `gl3.h` prototypes (C types). Android `Buffer` semantics (direct or array-backed, `position`-relative), `(array, offset)` pairs, String in/out. 11 unused shapes are `UnsupportedOperationException` stubs — loud, not silent. Regenerate with `make gen-gles-shim`; `make check-gen-gles-shim` (run by the `render-angle` CI job) fails on drift between generator and committed output. |
| `render/src/android/graphics/*` | Java2D-backed `Bitmap` (`BufferedImage TYPE_INT_ARGB`), `BitmapFactory`/`BitmapRegionDecoder` (ImageIO), `Canvas`, `Paint`, `Typeface`, `Color`, `Matrix` — exactly the 116 members the engine jar's bytecode references (extracted with `javap -c`). |
| `render/src/android/opengl/GLUtils.java` | `texImage2D/texSubImage2D(Bitmap)` → RGBA8 upload through the shim. |
| `render/src/android/util/{Pair,LruCache}.java` | Native `JNIPair_create` calls `Pair.create`; `FeatureStyleParser` uses `LruCache` (the G1.3 "Stub!" trap, now gone). |
| `render/src/com/atakmap/map/AtakMapView.java` | Shadows the jar's jre stand-in: `getDefaultTextFormat()` returns `GLRenderGlobals`' default instead of throwing (point labels call it). |
| `EglRenderContext extends GLMapSurface` | `GLMapView.getSurface()` casts the context to the abstract `GLMapSurface`; the async layer renderers call it. |
| `../g1/patches/jni-renderable-draw-clears-exception.patch` | `ManagedGLMapRenderable2::draw` now reports + clears a Java exception thrown by a layer's `draw()` (trap 13). |
| `LayerStore.dataStore()/bounds()` (G1.3) | For the layer + camera framing. |

### Design decisions

- **Generate the GLES binding, don't hand-write it.** The Android surface is
  353 methods; the generator maps each to its C prototype by name, collapsing
  `(T[] arr, int offset)` and `java.nio.Buffer` to one pointer and treating a
  Java `int` where C wants a pointer as a buffer offset. Three shim-level
  behaviours encode what Android drivers do and ANGLE/Metal does not: skip
  attribute index `-1` (inactive attribute; `MACTAK_GL_DEBUG` dumps the Java
  call site once), route integer-typed shader attributes through
  `glVertexAttribIPointer`, and hand Metal the **signed** twin type for
  signed `int` attributes fed with unsigned data (trap 12).
- **Java2D, not CoreGraphics, for `android.graphics`.** The JDK ships a
  complete headless 2D stack (fonts, antialiasing, ImageIO); upstream's own
  desktop android-port takes the same route. CoreGraphics is the window-era
  option if text quality or performance demands it.
- **`EngineLibrary.initialize()` is called first, explicitly.** See trap 11
  — nesting it inside native class-init deadlocks.

### Traps found on the way (continuing G2 numbering, traps 11–15)

1. **`ATAKMapEngineJNI_findClass` self-deadlock.** It holds a non-recursive
   mutex while `FindClass` runs Java `<clinit>`s. `GLLayerFactory.register`
   → `NativeGLLayerSpi2.wrap` → `Interop_class_init` → `findClass` →
   `GLMapView.<clinit>` → `EngineLibrary.initialize` → `GLText.<clinit>` →
   `setTextFormatFactory` (native) → `findClass` again → hang in
   `PThreadsMutexImpl::lock` (found with `sample`, not `jstack` — the Java
   side only shows RUNNABLE in a native method). Fix: the host calls
   `EngineLibrary.initialize()` before any interop.
2. **Metal is strict about integer vertex attributes.** `in int a_factor`
   fed via `glVertexAttribPointer(GL_UNSIGNED_BYTE)` → ANGLE:
   `CreateRenderPipelineState … Vertex attribute _ua_factor(7) of type int
   cannot be read using MTLAttributeFormatUChar4`, the whole line pipeline
   fails, GL_INVALID_OPERATION every frame. Needs `glVertexAttribIPointer`
   **and** a signed format for a signed `int`. The Java engine renderer
   does this in 3 places (fixed in the shim); the native
   `GLBatchGeometryRenderer3/4` have the same pattern (`a_pattern`,
   `a_factor`) — not on our path yet, patch when it is.
3. **A pending Java exception poisons the rest of the native frame.**
   `ManagedGLMapRenderable2::draw` called Java `draw()` without checking
   for an exception; one layer threw (trap 14), the exception stayed
   pending, every later JNI call in the frame returned `TE_Err`
   (`ExceptionCheck()` true) — including the text-format factory — so
   `GLText2_intern` returned null and `GLLabelManager::draw` dereferenced it
   (`SIGSEGV GLText2::getTextFormat()+0x0`). The crash site was three
   layers away from the cause. Patched: describe + clear after the call.
4. **`AtakMapView.getDefaultTextFormat()` throws in the jar's jre class**
   (`UnsupportedOperationException`); `GLBatchPoint$LabelState` calls it
   for every labelled point. Shadowed.
5. **`Layers.registerAll()` is the host's job.** Without it
   `GLLayerFactory.create4` returns null and a `FeatureLayer3` is silently
   never rendered — the frame stays black with no error anywhere. (ATAK's
   `MapView` does it in its constructor.)

### Known-benign noise

- `[Unsafe]: Unsafe.allocateDirect lifetime management not supported` —
  the JVM `Unsafe` falls back to plain `allocateDirect`; harmless here.

## G2.3 — an offline raster basemap under the vector layer

`dev/g2/render/run.sh --basemap FILE.mbtiles` registers the dataset through
`DatasetDescriptorFactory2` (`TilesetLayerInfoSpi`), adds the file to a
`PersistentRasterDataStore` under the scratch layer DB, wraps the store in a
`MobileImageryRasterLayer2` and adds it to the `Globe` *before* the feature
layer. The fixture is deterministic and offline: `fixtures/MakeBasemap.java`
paints a 0.01° checker with a graticule and coordinate labels as a PNG +
`.pgw` world file; `fixtures/make-basemap.sh` warps it to EPSG:3857, writes
MBTiles (PNG tiles) and builds overviews — 372 KB, zoom 10–14 over San
Francisco. No network, no third-party imagery licence.

### What's new

| Path | Role |
|---|---|
| `fixtures/MakeBasemap.java`, `fixtures/make-basemap.sh` | Synthetic basemap → `build/fixtures/sf-graticule.mbtiles` (`gdalwarp` → `gdal_translate -of MBTILES` → `gdaladdo`). |
| `RenderSmoke --basemap FILE` (repeatable) | Raster layer path above; prints the dataset's type/provider/resolution/coverage as the store sees it. |
| `render/src/android/net/Uri.java` | `IOProviderFactory.createDatabase` goes through `Uri.fromFile` (trap 16). |
| `render/src/android/os/SystemClock.java` | `elapsedRealtime()` is a native in the transplant (trap 18). |
| `render/src/gov/tak/platform/commons/opengl/GLES20\|GLES30.java` | Shadow the port jar's JOGL-delegating classes with subclasses of the generated `android.opengl` shim (trap 19). |
| `gen-gles-shim.py`: `uniformIsUnsigned` | `glUniform1ui` on a non-`uint` uniform is redirected to `glUniform1i` (trap 20). |
| `jni-extra/mactak_egl.cpp` | `MACTAK_GL_DEBUG=2` now dumps the **Java** stack next to the native backtrace — attributes a GL error to the engine call site in one run. |

### Traps found on the way (traps 16–23)

1. **`android.net.Uri` is `Stub!`** and `IOProviderFactory.createDatabase`
   → `Uri.fromFile` runs on the first raster-store open. Re-implemented
   (`parse/fromFile/getScheme/getPath/getHost/getQueryParameter/
   getFragment/decode/Builder`).
2. **`GdalTileReader.SPI` probes every URI** and the jar's `GdalTileReader`
   binds the `org.gdal` Java bindings, which the transplant doesn't ship →
   `UnsatisfiedLinkError` from inside the SPI chain for *any* dataset.
   `Layers.registerAll()` registers it; the host unregisters it right after
   (`TileReaderFactory.unregisterSpi(GdalTileReader.SPI)`). The engine's own
   native GDAL (`NativeGdal`) is unaffected. Revisit when a GDAL-backed
   raster (GeoTIFF) is on the path — that needs the `org.gdal` JNI, not a
   shim.
3. **`SystemClock.elapsedRealtime()` is a native method in the transplant**
   (`UnsatisfiedLinkError`, not `Stub!`). Shadowed with `System.nanoTime()`.
4. **`gov.tak.platform.commons.opengl.GLES20/30` in the port jar delegate to
   JOGL** (`javax.media.opengl.GL`) → `NoClassDefFoundError` the first time
   the raster renderer touches them (`GLQuadTileNode4` uses that package,
   the feature renderer uses `android.opengl`). Shadowed as
   `extends android.opengl.GLES20/30 implements GLES20Constants/…`.
5. **`glUniform1ui` on a sampler/int uniform** — `GLQuadTileNode4` sets
   `uTexture` with the unsigned variant; Android drivers accept it, ANGLE
   raises `GL_INVALID_OPERATION` and the texture unit stays unset (black
   tiles). The shim queries the active uniform type once per program and
   routes non-`uint` uniforms to `glUniform1i`.
6. **`gdalwarp` needs `-s_srs` for a PNG + world file.** The PNG driver
   ignores a sidecar `.prj`; without `-s_srs EPSG:4326` the warp has no
   source CRS and the MBTiles driver fails later with the misleading
   `Could not find an appropriate zoom level` (there is no `ZOOM_LEVEL`
   creation option to "fix" it with).
7. **The G0 GLES stub only covered what the *engine* linked** (80 symbols,
   harvested from a failing link). The generated JNI shim references 240
   `gl*` entry points, so every `MACTAK_GLES=stub` build — i.e. the two CI
   jobs that don't stage ANGLE — failed at `libtakenginejni` link while
   `render-angle` stayed green. `gen-gles-shim.py` now also emits
   `jni-extra/mactak_gles_stub.c`, a typed no-op for all 246 `gl3.h`
   prototypes; `build-native.sh` compiles that as the stub slot.
8. **A mid-transfer drop kills the ANGLE staging download.** `curl` exit
   56 (a release-CDN 504, 2026-11-05) is outside `curl`'s default
   transient-error set, so a plain `curl -fsSL` fails a green CI build
   on a retryable blip. `fetch-angle.sh` now downloads with
   `--retry 5 --retry-delay 5 --retry-all-errors --retry-max-time 600`;
   the zip's SHA256 pin is untouched (verified before the swap).

### Known-benign noise

- `[GLQuadTileNode4]: asynchronous read error` with **no stack trace** —
  `MBTilesTileReader.getTileImpl` returns `ReadResult.ERROR` (throwable
  `null`) when a requested tile is simply absent from the sparse pyramid
  (edges of the fixture). Not a decode or I/O failure; a real one logs
  `[MBTilesTileReader]: error` with a trace first.
- KHR_debug `glUniform4f: Uniform size does not match uniform method`,
  once per composited tile, from `GLQuadTileNode4.resolveTexture`
  (child-texture FBO compositing). That path runs from a queued render-thread
  event, never calls `glUseProgram`, and sets `core.shader.uColor` on
  **whatever program happens to be current** — a latent upstream bug that
  Android drivers don't report. The composite is discarded as soon as the
  tile's own read lands, so the picture is unaffected; it also happens
  outside `view.render()`, which is why the frame-loop `glGetError` count
  stays 0. Fix (bind `core.shader.handle` before compositing) belongs in an
  engine patch once the tile-sharing paths are exercised with real imagery.
