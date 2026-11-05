#!/usr/bin/env bash
# G1.5 canary: the --datapackage importer resolves an ATAK data package.
# Pure Java (DataPackage.java is JDK-only), so this needs no natives and no
# server: build the synthetic fixture, compile the importer plus
# DataPackageSelfTest.java, run the assertions. Runs on Zulu 8 (CI) and any
# JDK >= 8.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../src/mactak/headless"

WORK="$(mktemp -d /tmp/mactak-dp-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

DP="$WORK/fixture.dp"
"$HERE/fixtures/make-datapackage.sh" "$DP" DP-UNIT

CLASSES="$WORK/classes"
mkdir -p "$CLASSES"
javac -d "$CLASSES" "$SRC/DataPackage.java" "$HERE/DataPackageSelfTest.java"
java -cp "$CLASSES" mactak.headless.DataPackageSelfTest "$DP" DP-UNIT