# dev/g1 — G1: JVM host + native JNI glue (macOS arm64)

## State (G1 Milestone 1 — JVM in-process load): DONE locally, CI pending

`dev/g1/build-native.sh` now produces **two** dylibs:

- `libtakengine.dylib` — the G0 core engine (9.7 MB, no JNI exports)
- `libtakenginejni.dylib` — the JNI glue (`engine/src/main/jni`, 1.2 MB,
  1114 `Java_*` exports + `JNI_OnLoad`)

and proves the G1 premise with canary 3/3 (`dev/g1/smoke/run.sh`): a real
JVM (Zulu 8) does `System.load` on both, then drives
`DatabaseImpl.openOrCreate → CREATE/INSERT/SELECT` through the full stack
Java → JNI glue → C++ Database2 → sqlite → Java and reads back **42**.

```
[smoke] java 1.8.0_512 (OpenJDK 64-Bit Server VM)
[smoke] libtakengine.dylib loaded (core, no JNI exports)
[smoke] libtakenginejni.dylib loaded (JNI_OnLoad ran)
[smoke] DatabaseImpl.openOrCreate ok (JNI -> C++ Database2 -> sqlite)
[smoke] SELECT 41+1 through the JNI stack = 42
[smoke] PASS: engine loads and runs in-process from the JVM
```

## How the JNI target is built (wrapper, inside build-native.sh's heredoc)

Upstream already ships a macOS JNI build config (`src/main/jni/cmake/
{Common,MacOSSources}.cmake` — the TAKX pattern: glue as a separate
`takenginejni` shared lib linking the core). The wrapper mirrors upstream's
APPLE assembly and then applies the same G0 substitutions:

- sources from upstream lists, minus **`c3dt/`** (3D tiles/GLTF — later
  rung) and minus **`vsi_jfile.cpp` + `jvsijfilefilesystemhandler.cpp`**
  (GDAL VSI API drift — overrides pre-drift `VSIFilesystemHandler`
  signatures; same drift family as the LAS exclusion; revisit at the
  ttp-dist rung)
- defs: upstream macOS (`RTTI_ENABLED`, `TE_GLES_VERSION=3`) minus
  `SQLITE_HAS_CODEC` (SQLCipher rung)
- includes: work-copy `cpp/` + `jni/`, Homebrew, khronos, stl-soft,
  tinygltf overlay, plus **`dev/g1/jni-include`** (pinned javah output)
  and **`dev/g1/jdk-include`** (pinned zulu-8 `jni.h`/`jni_md.h` — keeps
  the native build JDK-free)
- links: `takengine` + GLES stub + **`/opt/homebrew/opt/sqlite/lib/
  libsqlite3.dylib`** (full path — see the sqlite trap below) + gdal +
  minizip (+ libkml→minizip rename defs, same as core)

### Pinned javah headers

The jni TUs `#include` generated headers (`jdatabaseimpl.h`, …) that
upstream's gradle `javah` task emits. They are a **committed, pinned
artifact** here (`dev/g1/jni-include/`, 78 files) generated exactly like
upstream (`gradle/javah.gradle`: `javah -o <header> <classes...>` per
`javah.properties` line, so multi-class entries concatenate):

    dev/g1/gen-jni-headers.sh        # needs zulu-8 + mavenLocal jars

Regenerate ONLY at an engine version bump (bump `TAKKERNEL_VER`, publish,
run, commit the diff). This keeps the native build hermetic — no JDK, no
gradle, no mavenLocal in CI's native job.

## Traps found on the way (do not rediscover these)

1. **Two sqlite3 runtimes in one process = SIGSEGV.** Linking bare
   `sqlite3` resolves to `/usr/lib/libsqlite3.dylib` (system), while
   `libspatialite` (which core calls via `Databases_openDatabase →
   spatialite_init_ex` at every db open) is built against Homebrew sqlite.
   The mixed pair crashed in `findElementWithHash` (spatialite registering
   SQL functions on a foreign sqlite handle). Fix: pin
   `/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib` by full path in BOTH
   targets so the whole process shares one runtime. Symptom signature:
   crash on first `DatabaseImpl.open*`, stack
   `spatialite_init_ex → sqlite3_create_function_v2 → findElementWithHash`.
2. **`set -o pipefail` + `grep -q` is flaky**: grep exits on first match,
   nm eats SIGPIPE (141), pipefail turns a passing check into a failure.
   Use `grep ... >/dev/null` in pipeline guards.
3. **`System.load` needs absolute paths**; `run.sh` resolves its dist dir.
4. **rsync flattens `src/main/` into `build/engine/`** (trailing-slash
   copy): the glue lives at `build/engine/jni`, not
   `build/engine/src/main/jni`.
5. **Staging must precede the smoke canary** — run.sh loads from
   `native-dist/`.
6. **`EngineLibrary` is benign at `JNI_OnLoad` time**: the glue's
   `JNI_OnLoad` FindClasses `com/atakmap/map/EngineLibrary` (resolved from
   the engine jar on the smoke classpath); its `<clinit>` catches its own
   native-load failure, so class init never blocks the load. Its lib list
   (`takengine`, `takenginejni` last) is the desktop load order.
7. **Android-only paths in the glue are properly guarded** (`spatialite_init`
   and `__android_log` behind `#ifdef __ANDROID__`) — desktop-safe as-is.

## Smoke runtime contract (dev/g1/smoke/)

`run.sh` needs: JDK 8 (`JAVA_HOME` or `/usr/libexec/java_home -v 1.8`)
and, per jar, mavenLocal first, else the committed transplant:
`takkernel-engine/-port/-annotation 0.59.1` at `dev/g1/transplants/`
(built from the same pinned upstream commit as src/) and
`android-port-3.1.0` at its G0 home `dev/spike/g0/transplants/`. The
android-port jar provides `android.*` stub signatures the engine jar's
signatures reference; the happy path never constructs a stub body (any
engine-side failure would surface as a confusing "Stub!" — stay on the
happy path when diagnosing).

CI: `native-engine` gained only a Zulu-8 setup step (the build needs no
JDK; the smoke does). `build-native.sh` still runs verbatim.

## Follow-ups

- `atak-headless` CLI skeleton (`--connect`) — next milestone; FTS
  round-trip traps (port-role swap 8087/8089, handshake-before-
  registration, restart FTS container between runs) are catalogued in
  `dev/spike/g0/pathway-a.md`.
- c3dt + VSI TUs rejoin at the ttp-dist rung; SQLCipher at the encryption
  rung; core install_name stays build-absolute (native-dist is built and
  consumed on the same machine — revisit if dylibs ever ship cross-machine).
