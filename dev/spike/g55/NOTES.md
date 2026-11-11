# Spike 1 record — the 5.5 engine on macOS arm64, without 3D Tiles

Step 1 of [docs/rebase-study-5.5.md](../../../docs/rebase-study-5.5.md)
§Next steps. Question: does takkernel 8.30.5
(`TAK-Product-Center/atak-civ` @ `9f6893d`, 5.5.1.10) build on macOS
arm64 with Homebrew GDAL 3, once 3D Tiles is left out, and with
macTAK's patch series carried over? Nothing here changes the build
macTAK ships: `src/`, `dev/g1/build-native.sh` and the 4.6 pin are
untouched. The re-pin is a later step.

## Result

RESULT_PLACEHOLDER

## Reproduce

```sh
brew install cmake ninja rsync proj geos gdal libspatialite minizip nlohmann-json libxml2 mdbtools glib assimp
git clone --filter=blob:none --no-checkout https://github.com/TAK-Product-Center/atak-civ ../atak-civ
git -C ../atak-civ sparse-checkout set takkernel/engine/src/main khronos takengine/thirdparty/tinygltfloader depends
git -C ../atak-civ checkout 9f6893dd657feacc35ec5de03dad721c2e44170e
git -C ../atak-civ lfs pull --include=depends/tinygltf-2.4.1-mod.tar.gz
bash dev/spike/g55/build-engine.sh ../atak-civ
# re-sort the 4.6 series against a 5.5 tree (writes clean + refreshed only):
python3 dev/spike/g55/refresh-patches.py ../atak-civ/takkernel/engine/src/main /tmp/out
```

CI: `.github/workflows/spike-55.yml` (dispatch, or a push to a
`spike/g55-*` branch that touches this directory) runs the same script
on `macos-26` and uploads `build.log` and `summary.txt`. It is not a
required check; a red run is the spike's answer, as G0's was
([docs/ci-qa.md](../../../docs/ci-qa.md) §7).

## What's here

| Path | Role |
|---|---|
| `refresh-patches.py` | Sorts the 4.6 series against a 5.5 tree, stacked in build order: clean, refreshed, review, upstream, failed |
| `patches/` | The 5.5 series: 34 patches, applying at `--fuzz=0` in `build-native.sh`'s order (`gdal-drift-*` first, then sorted) |
| `build-engine.sh` | The core-engine half of `build-native.sh` against a 5.5 checkout; `formats/c3dt` left out; `ninja -k 0` |
| `summarize-build.py` | Every failing source with its first error, plus the errors grouped by kind |

## The patch port

37 patches at the pin (34 in `dev/g1/patches`, 3 `gdal-drift-*` in
`dev/spike/g0/patches`). On 5.5:

| Outcome | Count | Patches |
|---|---|---|
| Unchanged (apply at fuzz 0) | 17 | `gdal-drift-GdalDatasetProjection2`, `glsl-es-version-on-angle`, the `ioproviderfactory-*`, `jglmapview-*`, `jglobe-*`, `jni-renderable-*`, `jnilayer2-*`, `jnilayerspi2-*` series, `managedrendercontext-sweep-guards`, `stlvectoradapter-bind-contents` |
| Refreshed by the tool (context intact, indentation drift) | 1 | `glmapview2-srid-capability` |
| Hand-ported, same hunks (whitespace, blank lines, offsets or upstream's own edits in the context) | 7 | `gdal-drift-GdalDatasetProjection`, `gdal-drift-OGR_Content2`, `common-onload-classloader-guards`, `gdal3-axis-order-cpp-default`, `glbatchshaders-dead-store`, `managedrendercontext-guards`, `vsi-jfile-gdal3-api` |
| Hand-ported, reworked for 5.5's code | 9 | below |
| Dropped: fixed upstream | 3 | `cpp17-legacy-headers`, `megatexture-sharetile-comma-operator`, `terraintile-ecef-ge` |

The nine reworked ports:

- **`attrib-location-guards`**: the `GLGlobe` and `GLMapView2` hunks
  carry over. `renderer/feature/GLBatchGeometryRenderer3.cpp` is gone in
  5.5, so its hunks are dropped. `GLBatchGeometryRenderer4` has the same
  unguarded `glEnableVertexAttribArray` calls but this patch never
  covered it; the render gate's `KHR_debug` count decides at the JNI
  iteration.
- **`gdal3-axis-order-traditional-gis`**: fuzz put
  `traditionalGisOrder()` inside an `if/else` chain of
  `GdalLibrary.java`; placed by hand after `ImportFromEPSG(4326)` and at
  the end of the class.
- **`jni-class-init-guards`**: 21 of 27 files carry over. Three
  class-init functions were rewritten upstream; they are guarded by the
  patch's own rule, every lookup checked, including the lookups 5.5
  added (`Collection.size`, the `RenderSurface2` class).
- **`megatexture-release-erase-while-iterating`**: 5.5 fixed the
  use-after-free its own way, draining with `while(!tiles.empty())`.
  That loop never ends once a tile is shared: `releaseTile` then drops
  one share and returns without erasing the entry. `shareTile` has no
  callers in 5.5 either, so the hang is latent. The port keeps
  macTAK's snapshot-of-keys loop in place of upstream's.
- **`projection-math-interop-guards`**: 5.5 moved
  `ManagedProjection::forward/inverse` onto `JNILocalRef` (fixing a
  local-reference leak) but still checks no exceptions; the four checks
  are re-added there.
- **`projection-sweep-hygiene`**: `Map.size` is gone from
  `model/Interop.cpp`; the remaining lookups are guarded by the same
  rule, including 5.5's new `ModelInfo.<init>`.
- **`quadtilenode4-resolvetexture-program-bind`**: fuzz put the program
  bind into a draw loop of another method; placed by hand at
  `resolveTexture`'s FBO block, which still binds no program in 5.5.
- **`terraintile-guard-missing-attribs`**: 5.5 guards `aNoDataFlag`
  (`!= -1`) but not `aNormals` or `aEcefVertCoords`; the no-data hunks
  are dropped as upstream, the rest ported.
- **`textformat2-class-init-consumer-guards`**: 5.5 deleted
  `TextFormatFactory_class`; the factory now calls the static
  `GLText.createTextFormat` and initialises inside `createTextFormat`
  as a function-local `static`. A guard placed before that line, where
  fuzz put it, would fail the first call and names a struct that no
  longer exists. Rewritten: the init guards the `GLText` lookups, and
  `createTextFormat` checks the method ID after the init.

Left for the JNI iteration (the guard series does not reach code 5.5
added): `FeatureDefinition` lookups moved to the new
`jni/interop/feature/ManagedFeatureDefinition.cpp`, and
`jni/interop/elevation/Interop.cpp` gained unguarded `HeightmapStrategy`
lookups.

## Traps found on the way

1. **`--fuzz=3` places hunks with no context.** GNU patch at fuzz N
   ignores N context lines at each end of a hunk; a 3-line-context hunk
   at fuzz 3 matches anywhere, and an addition-only hunk lands at its
   old line number plus the running offset. That put
   `traditionalGisOrder()` inside an `if/else` in `GdalLibrary.java`
   and the program bind into the wrong method of `GLQuadTileNode4`,
   with patch reporting success. The study's "9 fuzz-only" number
   came from these dry-runs. **Guard:** `refresh-patches.py` accepts a
   fuzzed result only when the regenerated hunks, context included,
   read the same as the original (whitespace and blank lines aside);
   anything else is `review`, rolled back and ported by hand.
2. **A reverse dry-run at fuzz 3 "finds" fixes that are not there.**
   `glbatchshaders-dead-store` reverse-applied at fuzz 3, so the study
   counted it as upstream; the dead store is still at line 447.
   **Guard:** the upstream check runs at `--fuzz=0`.
3. **`git log | grep -q` under `pipefail`** (G2 trap 8 again, in the
   port's own replay helper): `grep -q` exits on the first match, `git
   log` takes SIGPIPE, and the pipeline reports failure, so an applied
   patch read as not applied. Read the log into a variable first.

## Exit

EXIT_PLACEHOLDER
