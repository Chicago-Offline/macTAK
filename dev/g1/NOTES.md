# dev/g1 — G1: JVM host + native JNI glue (macOS arm64)

## State (G1): M1–M4 DONE — G1 exit criteria met: engine + comms in one JVM, CoT in/out persisted to the engine's tracks DB, offline layers open; witnessed on OpenTAKServer (TCP + SSL) and hermetically in CI

`dev/g1/build-native.sh` now produces **two** dylibs:

- `libtakengine.dylib` — the G0 core engine (9.7 MB, no JNI exports)
- `libtakenginejni.dylib` — the JNI glue (`engine/src/main/jni`, 1.2 MB,
  1114 `Java_*` exports + `JNI_OnLoad`)

and proves the G1 premise with canary 3/3 (`dev/g1/smoke/run.sh`): a real
JVM (Zulu 8) does `System.load` on both, then drives
`DatabaseImpl.openOrCreate → CREATE/INSERT/SELECT` through the full stack
Java → JNI glue → C++ Database2 → sqlite → Java and reads back **42**.

```text
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

```bash
dev/g1/gen-jni-headers.sh        # needs zulu-8 + mavenLocal jars
```

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

## Follow-ups (after G1.1)

- `atak-headless` CLI skeleton (`--connect`) — landed as G1.2 (below);
  extended by G1.3 (`--layer`) and G1.4 (tracks DB + exit demo). FTS
  round-trip traps (port-role swap 8087/8089, handshake-before-
  registration, restart FTS container between runs) are catalogued in
  `dev/spike/g0/pathway-a.md`.
- c3dt + VSI TUs rejoin at the ttp-dist rung; SQLCipher at the encryption
  rung; core install_name stays build-absolute (native-dist is built and
  consumed on the same machine — revisit if dylibs ever ship cross-machine).

## G1.2 — atak-headless: CoT round-trip (commoncommo build + streaming iface)

Build: `bash dev/g1/build-commo.sh` stages the comms stack into
`native-dist/` (`libcommoncommo.dylib`, `libcommoncommojni.dylib`,
`jcommoncommo.jar`) from the local commoncommo tree +
`patches/commo/*.patch` (-p1, relative to the copy root — keep separate
from the -p3 engine patches). Runtime: `dev/g1/headless/atak-headless`
(compiles HeadlessCLI.java on the fly, Zulu 8); live round-trip smoke:
`dev/g1/headless/run.sh` (two clients against any plaintext-CoT TAK
server; `TAK_HOST`/`TAK_PORT`, default 127.0.0.1:8087).

### Diagnostics (patches/commo/0001-diag-socketRead-errno.patch)

Five WARNING-level `[commo-diag]` probes in commo: socketRead
(errno + local port), both `::close(fd)` sites (local port), sendBroadcast
(type + ups), the io-tx queue check (q/blocked/doTx), and the io-tx write
(w/r). Regenerate from the generator repo `g1-commo-gen` (pristine
core/impl + insert.py; patch must be a scoped `git diff -- core/impl`).

### Traps found on the way (do not rediscover these)

Continuing G1.1's numbering (these are traps 8–12):

1. **commo fires `interfaceUp` BEFORE the context enters its sendable
   `upContexts` list** (streamingsocketmanagement.cpp: fireInterfaceChange,
   then insert). A broadcast sent inside the interfaceUp callback iterates
   zero contexts — broadcastCoT returns success while queueing nothing,
   and no byte ever reaches the wire. Diag signature: `sendBroadcast
   type=0 ups=0` and no `io-tx` lines. Fix in HeadlessCLI: never send
   from the callback; a sender thread announces presence on a 2 s
   heartbeat (mirroring ATAK's SA cadence) starting 500 ms after
   iface-up, and the marker rides the first send window.
2. **The generator must own its BASE path**: insert.py once pointed at a
   stale `/tmp/gt` copy while every retry reset the pristine repo —
   inserts piled up on already-patched files (the close-sites assert saw
   4 instead of 2). Keep generator + tree in one repo and
   `git checkout -- core/impl` before each run.
3. **brew protoc ≥27 no longer emits C++11** — pb objects are
   precompiled at -std=c++17 (build-commo.sh) so the 2016 core can stay
   C++11. Related: ld64 records only libs that resolved a symbol, so
   protobuf's absl deps are swept explicitly on the link line.
4. **install_name_tool invalidates the linker signature** — ad-hoc
   codesign after every install_name change (arm64 refuses unsigned
   dylibs).
5. **A TAK server does not reflect a client's own events back to it.**
   The original one-client `run.sh` passed only because FreeTAKServer
   sends a welcome GeoChat (`b-t-f`) and echoes the sender's marker;
   OpenTAKServer does neither (`received=0` → FAIL, while the bytes were
   on the wire and visible to other clients). A round-trip proof needs a
   second client: `run.sh` now runs A (listener) and B (presence +
   marker) and asserts each saw the other.

### Round-trip contract (verified)

`run.sh` passes when: both ifaces up, B's presence + marker tx'd (commo
diag shows full-size `io-tx write w=N r=N`), A logged
`contact-added <B>` and received B's marker (`uid="<B>-m1"`,
`a-f-G-U-C`), B received A's presence (`t-x-a-t-a-k`). Verified against
**OpenTAKServer** (`steintak`, tcp/8088, 2026-11-03): 12 s run → A
`received=7` (6 presence + marker), B `received=7`, each side
`contact-added` the other. Earlier one-client form verified against
FreeTAKServer (container, host network, tcp/8087): received=2 (welcome
chat + echoed marker). Restart the FTS container between sessions for a
clean contact list; OTS needs no reset.

## G1.3 — atak-headless --layer: offline vector layers (engine feature stack)

`atak-headless --layer FILE` is the roadmap's "offline map layer opens"
criterion, minus the GL: the engine's **native OGR feature provider**
parses the file, the **native `PersistentDataSourceFeatureDataStore2`**
persists it (feature-set databases under `--layer-db DIR`, exactly what
ATAK's overlay manager does on Android), and `FeatureDataStore2` cursors
read it back. Runs on the engine stack (`build-native.sh`), not the comms
stack — the launcher picks the entry point from the first flag
(`--layer` → `mactak.headless.LayerCLI`, else `HeadlessCLI`).

```text
dev/g1/headless/atak-headless --layer FILE [--layer FILE ...] [--layer-db DIR]
                              [--expect-features N] [--quiet]
```

Canary (`dev/g1/headless/test/layer.sh`, a `native-engine` CI step after
`build-native.sh`): the committed `test/fixtures/sf-landmarks.geojson`
(3 points, 2 lines, 3 polygons) plus, when `ogr2ogr` is on PATH (it is —
gdal is a build dep), the same features as **GPKG, KML and shapefile**.
All four PASS:

```text
layer sf-landmarks.geojson             8 feature(s) in 41 ms
set 1    OGR    GeoJSON      sf-landmarks.geojson             8 feature(s)  sf-landmarks.geojson
1 feature set(s), 8 feature(s) in store
  fid 4294967297   set 1   Ferry Building               Point              [37.79550 -122.39370 .. 37.79550 -122.39370]
  fid 4294967300   set 1   Golden Gate Bridge           LineString         [37.80600 -122.47880 .. 37.83240 -122.47500]
  fid 4294967302   set 1   Golden Gate Park             Polygon            [37.76400 -122.51070 .. 37.77420 -122.45310]
…
PASS: 8 feature(s) opened through FeatureDataStore2
```

### Design decisions

- **GDAL driver registration is a macTAK JNI shim.** On Android the
  engine's `GdalLibrary.init()` calls `gdal.AllRegister()` through the
  org.gdal SWIG bindings; Homebrew GDAL ships no Java bindings.
  `dev/g1/jni-extra/mactak_gdal.cpp` (compiled into
  `libtakenginejni.dylib` by `build-native.sh`, export checked in its
  canary gate) exposes the engine's own native
  `GdalLibrary::init(gdalDataDir)` as `mactak.headless.NativeGdal.init`.
  The launcher passes `-Dmactak.gdal.data=$(gdal-config --datadir)`
  (override: `GDAL_DATA`).
- **The provider set is the one ATAK registers**: `new
  OgrFeatureDataSource()` (native `OGR_FeatureDataSource2` + KML/GPX/SHP
  `OGRDriverDefinition2` SPIs); any other GDAL vector driver (GeoJSON,
  GPKG, …) falls through to `DefaultDriverDefinition2`. Nothing is
  registered by the C++ library itself — `FeatureDataSourceFactory` is
  empty until Java registers providers.
- **slf4j is on the engine-side classpath.** The `java`-target
  `com.atakmap.coremap.log.Log` logs through slf4j; `slf4j-api` +
  `slf4j-simple` 1.7.30 (upstream's pin, `gradle/versions.gradle`) are
  committed transplants (Maven Central, SHA-1 verified on fetch); default
  level `warn`, `MACTAK_LOG_LEVEL=debug` for engine chatter.
- **Engine-side Java keeps the `mactak.headless` package** (under
  `headless/src/`) because the JNI shim's symbol name is derived from it;
  the comms-side `HeadlessCLI` stays flat in the default package as
  G1.2 left it.

### Traps found on the way (do not rediscover these)

Continuing G1.2's numbering (these are traps 13–15):

1. **No GDAL drivers unless something calls `GDALAllRegister`.** Symptom:
   `ERROR 4: No driver registered` from OGR and
   `PersistentDataSourceFeatureDataStore2.add()` returning `false` with no
   exception. Nothing in `libtakengine` registers drivers on load; the
   shim above must run before the first provider touches GDAL.
2. **Never call `FeatureCursor.get()` / `Feature.getStyle()` on the JVM.**
   Materialising a `Feature` parses its style, and
   `FeatureStyleParser.<clinit>` builds an `android.util.LruCache` — a
   `"Stub!"` in the android-port transplant (the predicted trap from the
   G1.1 smoke contract, now met for real). Read through the cursor's
   `FeatureDefinition` view instead: `getRawGeometry()` +
   `getGeomCoding()` + `GeometryFactory.parseWkb/parseSpatiaLiteBlob`.
3. **The first engine code path that logs needs slf4j on the classpath**
   — `NativeFeatureDataStore2.finalize` threw
   `NoClassDefFoundError: org/slf4j/LoggerFactory` after a run that had
   otherwise succeeded. The G1.1 smoke never logged, so this stayed
   hidden until M3.

### Known-benign noise

- `WARN … Native FeatureDataStore2 leaked` on close: upstream's
  `PersistentDataSourceFeatureDataStore2.dispose()` destructs its own
  pointer and then calls `impl.finalize()`, which only checks the base
  view's (never-zeroed) pointer. Not a leak; the native object is gone.
- `SQLITE [SQL logic error] no such column: definition_12_063` on GPKG:
  GDAL 3.13 probes the optional WKT2 column of `gpkg_spatial_ref_sys`;
  files written without it log once and open fine.

## G1.4 — one JVM, both stacks: tracks DB + the exit demo

`atak-headless` is now a single entry point (`mactak.headless.HeadlessCLI`)
that loads **all four** dylibs into one process — `libtakengine` +
`libtakenginejni` (engine) and `libcommoncommo` + `libcommoncommojni`
(comms) — with `jcommoncommo.jar` and the takkernel jars on one classpath.
`--layer` dispatches to `LayerCLI.run` (G1.3); everything else is the CoT
endpoint (G1.2), which now persists every received `<event>` through the
engine's `DatabaseImpl` JNI (Java → glue → C++ Database2 → sqlite) into a
tracks DB and prints the newest fix per uid on exit — the roadmap's
"streams positions both ways and plots them in-memory":

```text
atak-headless --connect HOST:PORT [--db PATH] [--cert CLIENT.p12 --ca TRUSTSTORE.p12] ...
headless: track uid=g1-rt-m2uehlair-b     type=a-f-G-U-C callsign=G1-RT-M2UEHLAIR-B    lat=0.000000  lon=0.000000   time=… fixes=8
headless: track uid=g1-rt-m2uehlair-b-m1  type=a-f-G-U-C callsign=G1-RT-M2UEHLAIR-B-M1 lat=37.774900 lon=-122.419400 time=… fixes=1
headless: tracks uids=2 fixes=9 db=/tmp/a.sqlite
```

`--db PATH` persists to a file; without it the engine opens a private
temporary database (`DatabaseImpl.openOrCreate(null)` → `sqlite3_open_v2("")`),
so the exit demo always runs. `run.sh` gives each client its own DB and
asserts A's holds B's presence + marker (from the exit demo and, independently,
by reading the file back with the `sqlite3` CLI) and B's holds A's presence.

`--cert` + `--ca` (the two PKCS#12 files in an ATAK data package; passwords
via `MACTAK_CERT_PASS` / `MACTAK_CA_PASS`, default `atakatak`) switch commo's
streaming interface to SSL with a client certificate — OpenTAKServer's 8089.
`run.sh` passes them through as `TAK_CERT` / `TAK_CA`.

Verified 2026-11-04 against **OpenTAKServer** (`steintak`) on **both**
tcp/8088 and ssl/8089 (two distinct data-package certs also tried): A
`received=9 persisted=9`, B `received=8 persisted=8`, PASS. CI
(`cot-roundtrip.yml`) builds both stacks and runs the same `run.sh` against
`fake-cot-server.py`; `ci.yml`'s `native-engine` job now also builds commo
before the layer canary, since the launcher requires both.

### Design decisions

- **`HeadlessCLI` moved into `src/mactak/headless/`** (git rename) so one
  `javac` compiles one tree against one classpath and there is one `main`.
  `LayerCLI.main` became `LayerCLI.run(String[])`; native loading lives only
  in `HeadlessCLI` (`-Dmactak.native.dist`, engine before comms, core before
  glue per stack).
- **`CotEvent` / `TrackStore`** are the G1.2-era pure-Java pieces ported from
  the `archive/g1-m3-on-javatcp` branch: a DOM parser (XXE-hardened: no
  DTDs, no external entities) for `<event>`/`<point>`/`<contact callsign>`,
  and an append-only `tracks` table with a `(uid, id)` index; `latest()` is
  one `MAX(id) GROUP BY uid` join with per-uid counts. `TrackStore` methods
  are synchronized (commo's rx thread vs. main at exit) and `record` is a
  no-op after `close` so a late callback cannot hit a closed handle. Server
  pings (`t-x-c-t`) are not recorded.
- **The presence is a real self-SA** (`a-f-G-U-C` + `<takv>`, `<contact
  endpoint callsign>`, `<uid Droid>`, `<__group>`, `<status>`, `<track>`),
  not the G1.2 `t-x-a-t-a-k` ping, and `stale` is now in the future for both
  presence and marker (G1.2 had `stale == time`). See trap 17.
- **OpenSSL legacy provider shim** `dev/g1/jni-extra/mactak_ssl.cpp`
  (`NativeSsl.loadLegacyProvider`), compiled into `libcommoncommojni` by
  `build-commo.sh` (the jni Makefile globs `*.cpp`); export + single-libcrypto
  checks added to its canary. See traps 19–20.

### Traps found on the way (do not rediscover these)

Continuing G1.3's numbering (these are traps 16–21):

1. **OpenTAKServer registers an EUD only from a self-SA** — a parseable
   `<event>` carrying `uid`, `<contact callsign>` and preferably `<__group>`
   (`EudHandler.parse_device_info`). Until then the per-client RabbitMQ
   consumer is never started: the socket is up, bytes are accepted, nothing
   ever comes back. The G1.2 `t-x-a-t-a-k` ping with no group (and
   `stale == time`) stopped registering after the server was rebuilt; it had
   passed on 2026-11-03 by luck of server state.
2. **OTS declares a durable RabbitMQ queue per EUD uid, bound to its `cot`
   fan-out exchange, and never deletes it.** A fresh uid per `run.sh` run
   (`g1-rt-<epoch>-a/b`) leaked two bound, unconsumed queues per run; every
   CoT was copied into each until the broker alarmed and blocked all fan-out
   for every client. `run.sh` now uses stable per-host uids
   (`g1-rt-<hostname>-a/b`, `TAK_UID_PREFIX` to override). The `auto_delete`
   fix belongs in OTS; any new real device leaks the same way.
3. **OTS drops every CoT from a uid whose callsign is registered to a
   different uid** — silently (the `cot_parser` worker fails before
   `route_cot`; the sender sees nothing, receivers see nothing from it).
   Reusing `G1RT-A/-B` across different uids triggered it. `run.sh` derives
   callsigns from the uids (`G1-RT-<HOST>-A`). Bisected with a raw-socket
   sender while an `atak-headless` listener watched: identical bytes with a
   fresh callsign pass, with the colliding one fail.
4. **Data-package PKCS#12 is RC2-40** (`pbeWithSHA1And40BitRC2-CBC`, the
   OpenSSL ≤1.1 default OTS and TAK Server still emit); OpenSSL 3 decrypts it
   only with its legacy provider, so commo's `PKCS12_parse` fails with
   `Invalid format for client cert`. Java 8's `KeyStore` cannot re-wrap it
   either (its PKCS12 writer is RC2-40-only; the `keystore.pkcs12.*`
   properties are ignored). Fix: `NativeSsl.loadLegacyProvider()` before the
   first `addStreamingInterface` with a cert (loads `legacy` **and**
   `default` — explicitly loading one provider disables the implicit default).
5. **Two OpenSSL runtimes in one process** (the sqlite trap, again).
   `libcommoncommo` was linking **openssl@4** (`/opt/homebrew/lib/libcrypto.dylib`
   follows whichever `openssl@N` brew linked last) while the JNI glue linked
   the shim's openssl@3 — so the legacy provider loaded into a libcrypto commo
   never called. `build-commo.sh` puts `-L$WORK/shim/lib` first in
   `CORE_CXXFLAGS` (the Makefile's `-L$(TAKTHIRDPARTYDIR)/lib` comes after
   `CXXFLAGS` on the link line) and its canary asserts both dylibs resolve
   `openssl@3/lib/libcrypto`.
6. **commo frames on `</event>` and writes `<?xml …?>\n<event…/>` with no
   trailing newline**; the fake server framed on newlines and prefixed its
   own broadcasts with an XML prolog. Relayed events arrived glued to the
   next prolog (`Invalid CoT message received from stream`, pre-existing on
   main), and once that was fixed the leftover `\n` before the server's own
   prolog still broke libxml2 (a declaration must be byte 0).
   `fake-cot-server.py` now frames inbound on `</event>`, drops prologs, and
   sends its own events without one.

### Known-benign noise

- **Old CoT replayed on connect**: OTS's per-uid queue is durable and keeps
  accumulating while the client is away (until `OTS_RABBITMQ_TTL`), so the
  first seconds after connecting deliver whatever arrived since the last
  run. The tracks DB records them with their original `time`.
- `run.sh`'s `nc -z -G` reachability probe is a plain TCP connect, also fine
  for the SSL port.
- `build-commo.sh`'s EXIT trap kills its own process group; a `bash
  build-commo.sh > log` wrapper sees exit 143 even on success (the log ends
  with `comms stack OK`). Pre-existing.

## G1.5 — the `--datapackage` importer

`atak-headless --datapackage FILE.dp` resolves an ATAK data package instead
of hand-feeding flags: `host:port[:proto]` from `preference.pref`'s
`cot_streams` `address0`, the `location_units` callsign, and the PKCS#12
pair (firecrypt `ClientFile`/`CAFile` when present, else the trust-named
file and the other) extracted to a private temp dir. Precedence: explicit
flags > package > defaults; `MACTAK_CERT_PASS` overrides the package's
firecrypt `Password` (default `atakatak` unchanged). Failure → one line +
exit 2.

- `DataPackage.java` (+ `ConnectOptions.java`, the flag/package precedence
  merge described above) is pure JDK (java.util.zip + DOM) — no natives — so
  the canary (`test/datapackage.sh`, a step of the `jvm-tests` CI job)
  compiles them with Zulu 8 and runs javac+java assertions against a
  synthetic package (`test/fixtures/make-datapackage.sh`: openssl
  self-signed p12s + pref XML; the recipe is committed, no binary fixture),
  including every precedence rule. No JUnit, matching the repo idiom.
- The launcher src is compile-gated in the same job (`test/compile-gate.sh`,
  #29): atak-headless's own javac line (`-Xlint:all -Werror`, the same
  mavenLocal-first/transplants-fallback classpath), with the commo API
  classes javac'd from the checkout — CI previously never type-checked
  `HeadlessCLI.java` at all.
- Hardening matches the CoT side's posture: DOCTYPE disallowed and external
  entities off when parsing `preference.pref` (XXE); zip entry names with
  `..`, absolute paths or backslashes are refused before extraction
  (Zip-Slip), and extraction targets are re-verified canonically under the
  temp dir; every entry is size-capped while it is copied (#31) — 16 MiB for
  `preference.pref`, 64 MiB per PKCS#12 — so an oversize fails with a
  one-line `exceeds N bytes` message (exit 2) instead of filling the disk or
  the heap, and a refused cert leaves no partial file in the cert dir; the
  address line (#32) takes a bracketed IPv6 literal and stores it unbracketed
  (what commo's addStreamingInterface takes), range-checks the port to
  1–65535 on both the package and the `--connect` paths, matches the proto
  token exactly (`ssl`/`tls`/`tcp`; `dtls` is refused instead of mislabeled
  ssl), and refuses a client/truststore pair sharing one basename — the cert
  dir keeps one flat file per basename, so the two could not coexist; the
  resolved line never prints the password.
- **The key names are verified against the fixture only, not yet against a
  real OpenTAKServer package** (none existed on this machine). The parser is
  deliberately liberal (`addressN`/`callsign` wherever they appear, firecrypt
  by substring), but the first live-package run must confirm the
  `cot_streams`/`location_units`/`plugin_firecrypt` spellings before this
  replaces flag plumbing at a demo.

## Follow-ups (G1 done)

- OTS upstream: `auto_delete` per-EUD queues; silent drop on callsign
  collision. Worth filing.
- The `[commo-diag]` WARNING probes (patch 0001) are loud on every run; demote
  or gate behind `MACTAK_COMMO_DIAG` once the stack has been quiet for a while.

## G1.6 — wave 4: JNI exception hygiene + attrib-location guards (engine C++)

Four patches in `dev/g1/patches/`, cut from the wave-3 audit (F1–F4) after a
line-by-line smell test against the engine sources. Evidence: `make build`,
`make test-render`, `make test-render-window` all PASS with the full set
applied (`build-native.sh` applies `dev/g1/patches/*.patch` to the fresh
engine copy, -p3).

- `jnilayer2-exception-clears.patch` (F1) — `ManagedGLLayer2` clears (report
  once + `ExceptionClear`) a Java exception thrown by `getSubject()`,
  `draw()`, `release()`, `start()` or `stop()`; the ctor also skips its
  marshal/adapt pair while one is pending, leaving `csubject` null. Mirrors
  the draw-only fix the G2 wave already made to `ManagedGLMapRenderable2`.
  Hazard documented, not patched: `getSubject()` returns `Layer2&`
  (`GLLayer2.h`), so a null subject stays unguarded by design — callers of
  `ManagedGLLayer2::getSubject()` must test the `shared_ptr` before use.
- `jnilayerspi2-exception-clears.patch` (F2) — `ManagedGLLayerSpi2::create`
  maps a thrown Java `create()` to `TE_Err` (describe + clear) instead of
  `TE_InvalidArg` with the exception pending. Caller contract proven:
  `GLLayerFactory2.cpp:110` advances to the next SPI on any non-OK, so the
  pending exception used to poison the next SPI's JNI calls. A clean null
  return still keeps `TE_InvalidArg`.
- `attrib-location-guards.patch` (F3) — the two live per-frame bugs are in
  the terrain draws: the planar `TerrainTile*` shader variants declare no
  `aEcefVertCoords` (location -1) and both renderers select planar for
  non-ellipsoid geometry (`GLGlobe.cpp:2902`, `GLMapView2.cpp:3098`), so
  `drawTerrainMeshesImpl`'s unguarded enable/disable of the ECEF attribute
  raised `GL_INVALID_VALUE` on every 2D terrain frame. The
  `GLBatchGeometryRenderer3` AA-lines/point guards in the same patch are
  defensive only: a single cached program uses all 8 queried attributes, so
  -1 fires solely on a compile/link failure. CHANGELOG wording keeps the
  split honest (per-frame bug vs hardening).
- `terraintile-ecef-ge.patch` (F4) — one token: `GLTerrainTile.cpp:508`
  tested `aEcefVertCoords > 0` for the ECEF local frame, rejecting a
  legitimate location 0 while the same file's enable/pointer code (and the
  wave-2 `terraintile-guard` patch at :580) accepts `>= 0`.

Report-only observations (seen in the audit, deliberately not patched):

- `GLBatchGeometryShaders.cpp:179` stores into the wrong function-local —
  a dead store today (readers use the correct local), harmless but a
  foot-gun.
- `GLMapView2.cpp:3250` picks the terrain frame by SRID where `GLGlobe`
  picks by capability — divergent selection logic for the same shader
  family; fragile if a third path grows.
- The reference-returning `getSubject()` pattern (null → deref hazard) is
  endemic across the JNI bridge; wave 4 covers the `ManagedGLLayer2`
  surface only.

## G1.7 — wave 5: JNI bridge hygiene sweep (F5–F13) + two engine fixes

Seven patches in `dev/g1/patches/`, cut from the wave-5 smell test (F5–F13
plus the two wave-4 report-only engine observations, A and B) after a
line-by-line verification pass. Patch mechanics as wave 4: scripted edit of
a pristine tree staged at `/tmp/g1wave5/{a,b}/tmp/takeng-src/`, `diff -u`
with explicit `a/…`/`b/…` labels, applied by `build-native.sh` at -p3 in
glob order. The generator/verifier (`gen_patches.py`) and the roster audit
(`audit_roster.py`) live in `/tmp/g1wave5/`.

Engine fixes (the two wave-4 report-only items, now closed):

- `glbatchshaders-dead-store.patch` (A) — `GLBatchGeometryShaders.cpp:179`
  stored `aRotation`'s uniform location into `aColor`; the real `aColor`
  attrib lookup overwrote it lines later. Dead store removed, nothing
  else.
- `glmapview2-srid-capability.patch` (B) — `GLMapView2.cpp:3250` picked
  the terrain local frame by `tile.data_proj.srid == state.drawSrid`,
  while `GLGlobe.cpp:3048` picks by the active shader family
  (`shader.aEcefVertCoords >= 0` — the ECEF shaders consume the
  re-projected mesh). In planar mode with elevation data already in the
  draw SRID the planar pass concatenated the projected data's local
  frame. Aligned on capability; `shader` was already in scope and the
  wave-4 attrib guards use the same member three lines up.

JNI guards (F5–F13; the wave-4 house pattern throughout — describe once,
`ExceptionClear`, then a neutral return or a clean continue; the
`jioproviderfactory` sites use that file's own bare-`Clear` template):

- `jglobe-listener-guards.patch` (F5) — the nine `CallbackForwarder`
  dispatches; `onMapMoved` fires per camera frame (the wave-4 F2 poisoning
  pattern). `impl` is a strong global ref, so the missing guard was the
  only defect.
- `jglmapview-forwarder-guards.patch` (F6) — the two
  `CameraChangedForwarder` dispatches returned bare `TE_Ok` past an
  unguarded `CallVoidMethod`; a throw now maps to `TE_Err`. The rest of
  the file (weak-ref + cleared-check discipline) is untouched.
- `managedrendercontext-guards.patch` (F7) — all 16 sites: the scalar
  getters capture, guard and return their neutral value (a throw leaves
  garbage in the JNI call's return register), the dispatch methods map to
  `TE_Err`, the dtor's `destroyChildContext` clears (no return possible),
  `createChildContext` guards both the capability probe and the create
  call, and the `getRenderSurface` helper clears so a throw no longer
  escapes the `NOTHROWS` constructor with the surface left mid-init.
- `projection-math-interop-guards.patch` (F8/F9/F10, F11 riding) —
  `ManagedProjection::forward/inverse` (the per-frame projection marshal
  path) guard before each null check; the shared `Interop_create` GeoPoint
  helper clears its own throw so its null return is clean for every
  caller; core `Interop`'s per-frame `MapSceneModel` export no longer
  returns `TE_Ok` past a failed `NewObject`, and its in-place marshal's
  `init()` dispatch is guarded; the `math/Interop` marshals gain
  null-argument guards (F10 — a null `jobject`/`Rectangle2*` there is a
  JVM crash, not a catchable exception) plus `NewObject` guards; and the
  model resource-map marshal loop is guarded per call (F11, defensive —
  entry-check-gated today).
- `ioproviderfactory-guards-and-filecount.patch` (F13+F12) — `remove` and
  `mkdirs` join the file's canonical `ExceptionCheck → Clear → TE_Err`
  their 11 siblings already had (a `SecurityException` fed garbage into
  the jboolean — coin-flip result, exception pending), as does
  `createTempFile`'s `getAbsolutePath` follow-up (caught by the post-patch
  sweep, same pattern); and `getFileCount`'s second probe is
  `isDirectory`, not a duplicated `isFile` — the directory branch was
  unreachable, so every directory returned `TE_IllegalState` from the
  fall-through (F12, a real logic bug).

Roster validation (`audit_roster.py`, scripted re-sweep — validates, does
not discover): heuristic is JNI `Call*`/`New*Method` sites with no
`ExceptionCheck` in the following 8 lines. Pristine tree: 34 files / 151
sites (the planning pass pinned 18 files with a stricter result-used
filter; every planning-roster file appears here). Post-patch: 27 files /
102 sites, and the eight touched files drain to zero or to the residuals
below — jglobe 9→0, jglmapview 2→0, ManagedRenderContext 16→0, math
Interop 2→0, model Interop 4→0, jioproviderfactory 5→0,
ManagedProjection 9→6, core Interop 5→2.

Report-only residuals (deliberately not patched this wave):

- `ManagedProjection` scalar getters ×6 (`getSpatialReferenceID`, min/max
  latitude/longitude, `is3D`) — scene-setup cadence, not the per-frame
  marshal path; the F7 capture-and-guard shape applies verbatim when a
  later wave picks them up.
- core `Interop` `Interop_wrap` NativeProjection `NewObject` ×2
  (pristine `Interop.cpp:190`/`:200`) — adapter-construction path, not
  the per-frame export.
- The systemic `*_class_init` failure masking (returns `true`
  unconditionally) — unchanged since the smell test; a failed class init
  turns every later call on those method IDs into undefined behavior.
  Worth its own wave.
- `jnativegllayer3.cpp` remains clean; the `NewWeakGlobalRef` enum
  caching pattern is OK as found.
