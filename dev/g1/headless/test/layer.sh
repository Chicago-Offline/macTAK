#!/usr/bin/env bash
#
# G1 offline-layer canary: atak-headless opens vector layers through the
# engine's feature stack (native OGR provider -> PersistentDataSourceFeatureDataStore2)
# and reads the features back through FeatureDataStore2 cursors.
#
#   dev/g1/headless/test/layer.sh            # committed GeoJSON fixture (+ SHP/GPKG/KML
#                                            # derived from it when ogr2ogr is on PATH)
#   dev/g1/headless/test/layer.sh FILE...    # your own layer file(s), each asserted
#                                            # at exactly 1 feature (FILE:N for N)
#
# PASS = every layer opens and yields the expected feature count.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # <repo>/dev/g1/headless/test
CLI="$HERE/../atak-headless"
FIXTURE="$HERE/fixtures/sf-landmarks.geojson"
FIXTURE_FEATURES=8
WORK="$(mktemp -d)"
# shellcheck disable=SC2317,SC2329  # invoked via trap (SC2317 on shellcheck < 0.10)
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

rc=0
run_one() {   # run_one FILE EXPECTED_FEATURES
  local f="$1" n="$2" log ec=0
  log="$WORK/$(basename "$f").log"
  "$CLI" --layer "$f" --layer-db "$WORK/layers-$(basename "$f")" --expect-features "$n" > "$log" 2>&1 || ec=$?
  if [[ "$ec" == 0 ]]; then
    echo "[layer-test] ok   $(basename "$f") ($n feature(s))"
  else
    echo "[layer-test] FAIL $(basename "$f") (exit $ec)"; rc=1
  fi
  sed 's/^/    /' "$log"
}

# One GDAL-conversion leg. An ogr2ogr failure must be LOUD: the old
# `ogr2ogr ... && run_one ...` form silently dropped the leg on a failed
# conversion, and the suite still printed PASS with quietly shrunk coverage.
convert_run() {   # convert_run FMT OUTFILE EXPECTED_FEATURES SRC [extra ogr2ogr args...]
  local fmt="$1" out="$2" n="$3"; shift 3
  if ogr2ogr -f "$fmt" "$out" "$@" >/dev/null 2>&1; then
    run_one "$out" "$n"
  else
    echo "[layer-test] FAIL ogr2ogr -f $fmt conversion of $* — leg cannot run"; rc=1
  fi
}

if [[ $# -ge 1 ]]; then
  # FILE          asserts exactly 1 feature (sanity-check a single-feature file)
  # FILE:N        asserts exactly N features (a 5-feature file with a bare
  #               FILE would FAIL — say so instead of a silent surprise)
  for f in "$@"; do
    if [[ "$f" == *:* ]]; then
      file="${f%:*}"              # % always strips at the LAST colon
      n="${f#"$file":}"           # literal prefix strip — ## can't match a ':'-leading pattern on absolute paths
      if [[ "$n" =~ ^[1-9][0-9]*$ ]]; then
        run_one "$file" "$n"
      else
        echo "[layer-test] (note) $f: FILE:N needs a numeric N — treating as a bare file"
        run_one "$f" 1
      fi
    else
      echo "[layer-test] (note) $f: asserting exactly 1 feature — pass FILE:N for the real count"
      run_one "$f" 1
    fi
  done
else
  run_one "$FIXTURE" "$FIXTURE_FEATURES"
  if command -v ogr2ogr >/dev/null 2>&1; then
    # same features through three more GDAL drivers; shapefiles are single-geometry,
    # so only the points survive the conversion (the OGR driver picks one layer type).
    convert_run GPKG "$WORK/sf-landmarks.gpkg" "$FIXTURE_FEATURES" "$FIXTURE"
    convert_run KML "$WORK/sf-landmarks.kml" "$FIXTURE_FEATURES" "$FIXTURE"
    convert_run 'ESRI Shapefile' "$WORK/sf-points.shp" 3 "$FIXTURE" -where "kind = 'poi'"
  else
    echo "[layer-test] ogr2ogr not on PATH; GPKG/KML/SHP legs skipped"
  fi
fi

if [[ "$rc" == 0 ]]; then
  echo "[layer-test] PASS: offline layer(s) opened through FeatureDataStore2"
else
  echo "[layer-test] FAIL"
fi
exit "$rc"
