# ATAK-CIV → macOS Native Port: Feasibility Report (Pathway C)

**Verdict: FEASIBLE — with materially better engineering evidence than typical Android ports — but the effort lives almost entirely in the 492k-LOC Android app layer, not the engine.** A credible core TAK client on native macOS is a **12–18 month** effort for a 4–6 person team; full ATAK-CIV parity is a **multi-year** program. No prior macOS-native TAK client exists (verified — this would be the first), so there is no prior art to discount these estimates or solve the hard parts.

- Evidence base: full source clone of `deptofdefense/AndroidTacticalAssaultKit-CIV` @ `889eee2` (v4.6.0.5, committed 2024-10-18; takkernel 0.59.1), plus prior ecosystem research (see `pathway-c.md` §Prior art).
- Method: static inventory of the actual tree — LOC per module, `android.*` coupling counts, JNI/SWIG surface, GL/EGL call sites, platform-branch CMake analysis, build/CI toolchain audit. All numbers below are measured, not estimated.

## Headline numbers (measured)

| Metric | Value |
|---|---|
| Total code | ~1.55M LOC: **750,106 Java** + **795,397 C/C++** (14,297 files, ~1.8 GB incl. 556 MB LFS assets) |
| Android app layer (`atak/ATAK/app/src/main`) | **492,324 LOC Java** — 100% Android-coupled (`src/common` is only **453 LOC**) |
| Map/comms engine (`takkernel`) | **451,170 C/C++** + 221,308 Java — of which Android-specific Java is **only 5,424 LOC (12 files)** |
| Desktop-Java engine host (`engine/src/jre`) | **1,443 LOC already exists** — JOGL render surface, AWT (`jawt`) hosting; this is what TAK's Windows desktop client (TAKX) builds on |
| macOS scaffolding in C++ engine | **`elseif(APPLE)` branch + `MacOSSources.cmake` already exist** (GLESv2 via a "GLES-stub" slot, spatialite, gdal, geos, proj, las; `TE_GLES_VERSION=3`) |
| macOS scaffolding in 3rd-party build | **`takthirdparty/target-config/macos-64.mk` + Darwin host detection already exist** (Linux-confirmed only per BUILDING.md) |
| Apple support in comms (`commoncommo`) | **Objective-C bindings + `commoncommo.xcodeproj` ship in-tree** |
| Android coupling overall | 1,731 Java files with `import android.*` (8,592 imports): atak 1,429 · takkernel 220 · plugin-examples 60 |
| JNI surface | ~1,430 `native` methods; 186 JNI C++ files (engine); commo has JNI **and** ObjC paths |
| Legacy/toolchain debt | Java 8, NDK **r12b (2016)**, CMake 3.14.7, Conan 1.x, GPL-3.0 (viral), targetSdk 29 / minSdk 21 |

## Effort table

| Subsystem | Scope (measured) | Estimate | Confidence |
|---|---|---|---|
| **P0 — macOS arm64 build spike** (engine C++ + deps: gdal-mod 2.4.4, spatialite, geos, proj, las, ANGLE) | 795K C/C++ already have macOS CMake branch; work = toolchain modernization (clang ≥ current, CMake ≥ 3.14→current, Conan 1.x→vcpkg/Homebrew) + `macos-64.mk` revival | **1–2 person-months** | High (branch exists; unknowns are compile errors, not research) |
| **P1 — Headless engine on macOS** (takkernel C++/Java `java` target loads, CoT in/out via commoncommo) | engine `main` 186,804 LOC is platform-neutral; jre host 1,443 LOC proven on desktop (TAKX) | **2–3 person-months** | High-Med |
| **P2 — Map renders in a native macOS window** (ANGLE-on-Metal as the EGL/GLES provider in the existing GLES-stub slot) | Only **7 engine C++ files** include GLES headers; EGL appears in **zero** non-Android engine C++ — context creation is host-injected by design | **2–3 person-months** | Med-High (ANGLE Metal backend is production-proven in Chromium) |
| **P3 — CoT round-trip** (commoncommo 44.8K C++ w/ ObjC bindings + takproto protobuf + takcot XSD) | Protocol stack is portable and Apple-first-class; MIT-licensed Swift reference (`flighttactics/TAKTracker`) validates CoT-over-TLS on Apple APIs | **1–2 person-months** | High |
| **P4 — Core client subset** (map + tracks + chat + KML/shapefile + MRU tools) in native AppKit/Swift UI | This is where the 492K-LOC Android app tax begins: UI, services, permission model, import/export handlers must be rebuilt, not ported | **4–8 person-months** | Med |
| **P5 — Parity ladder** (rungs defined in `roadmap.md`) to full ATAK-CIV feature parity | 492K LOC of Android app behavior; plugin SDK compatibility is **out of scope** (Android-only artifacts) | **3–5 years** sustained | Low (feature-set drift; upstream is public-GPL and stale since Oct 2024) |

**Team shape for P0–P4:** 1 C++ build engineer, 2 graphics/engine engineers, 1–2 Swift/AppKit engineers, 1 QA/geospatial. ~**12–18 months** to a deployable core client; 3–6 people.

## Why this is de-risked (the five decisive findings)

1. **The engine was already ported off Android once.** `takkernel/engine` has four platform source files (`AndroidSources.cmake`, `LinuxSources.cmake`, `MacOSSources.cmake`, `WindowsSources.cmake`) and a dual Gradle target (`-PkernelBuildTarget=java|android`) — the `java` target is what builds **TAKX**, TAK's closed Windows desktop client. A macOS port is the third lap of a proven route, not a first ascent.
2. **macOS slots already exist in the build system** (`MacOSSources.cmake`, `macos-64.mk`, commo's Xcode project). They are stale/unmaintained, but they define the contracts to fill.
3. **The renderer risk is contained by architecture.** Engine C++ is context-agnostic (7 GLES-including files; 0 EGL outside Android); the macOS branch consumes GL ES through a `GLES-stub` link slot → substitute **ANGLE on Metal** (ES 2/3 + EGL 1.5, the same backend Chromium ships on macOS).
4. **The engine's Android surface is tiny** — 5,424 LOC of Android-specific Java vs 186,804 platform-neutral LOC. Desktop equivalents (jre: JOGL, AWT cert store, `AtakMapView`) already exist.
5. **Comms are portable and Apple-first-class** — commoncommo (C++, ObjC bindings, Xcode project), takproto (protobuf), takcot (CoT XSD schemas). CoT feasibility on Apple platforms is additionally validated by the MIT-licensed Swift `TAKTracker`.

## Document map

| Doc | Contents |
|---|---|
| `architecture.md` | Verified repo layout, LOC inventory, dependency graph, build/CI reality, third-party stack |
| `pathway-c.md` | Deep dive: host strategy (JVM vs native Swift), renderer mapping, risk register, prior art |
| `roadmap.md` | Gate-gated plan G0–G6 with exit criteria, spike checklist, fallback decision points |

`src/` contains the cloned evidence tree (do not modify; pin all citations to `889eee2`).

## Optional follow-ups (not started)

- **macOS arm64 build spike**: attempt `cmake` of `takkernel/engine` with `MacOSSources.cmake` + Homebrew/vcpkg deps; the single highest-information 2-week experiment.
- **Pathway A fidelity baseline**: run WinTAK-in-VM or ATAK-in-Android-emulator (VMPal) to capture the reference UX for parity rungs.

