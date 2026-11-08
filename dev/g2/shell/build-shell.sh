#!/usr/bin/env bash
#
# Build the M3.2 shell: dev/g2/shell/jni_boot.c (clang, against the JDK's
# jni.h) + dev/g2/shell/main.swift (swiftc -O -swift-version 5, jni_boot.o
# linked in, bridge.h imported) → dev/g2/build/mactak-shell, then print the
# binary's path (run.sh captures it). Rebuilt when stale.
#
# Building needs no GUI session and no engine — this is the CI gate; the run
# that opens the window is `make test-shell` on the dev Mac (same split as
# M3.0's compile-only smoke-window). Swift 6.4.2/arm64 verified locally.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # dev/g2/shell
G2="$(cd "$HERE/.." && pwd)"
OUT="$G2/build/mactak-shell"

# JAVA_HOME wins when the caller pins a JDK (CI's setup-java exports it);
# /usr/libexec/java_home -v 1.8 otherwise, loudly when neither has one.
if [[ -n "${JAVA_HOME:-}" ]]; then
  JDK="$JAVA_HOME"
  [[ -d "$JDK/include" ]] \
    || { echo "build-shell.sh: JAVA_HOME=$JDK has no include/ — JNI headers missing" >&2; exit 2; }
else
  JDK="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
  [[ -n "$JDK" ]] || { echo "build-shell.sh: JDK 1.8 not found (set JAVA_HOME)" >&2; exit 2; }
fi

command -v swiftc >/dev/null 2>&1 \
  || { echo "build-shell.sh: swiftc not on PATH — Xcode command line tools required" >&2; exit 2; }

# Staleness: any source edit, this script's flags, or a switched JDK (its
# jni.h differs) forces the rebuild; each check is < 2s apart from one.
rebuild=0
if [[ ! -f "$OUT" ]]; then
  rebuild=1
else
  for f in "$HERE"/main.swift "$HERE"/jni_boot.c "$HERE"/bridge.h "$0" "$JDK/include/jni.h"; do
    if [[ "$f" -nt "$OUT" ]]; then rebuild=1; break; fi
  done
fi
if [[ "$rebuild" = 1 ]]; then
  mkdir -p "$(dirname "$OUT")"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  clang -arch arm64 -O1 -Wall -Wextra \
    -I"$JDK/include" -I"$JDK/include/darwin" -I"$HERE" \
    -c "$HERE/jni_boot.c" -o "$WORK/jni_boot.o"
  swiftc -O -swift-version 5 \
    -import-objc-header "$HERE/bridge.h" \
    -Xcc -I"$JDK/include" -Xcc -I"$JDK/include/darwin" -Xcc -I"$HERE" \
    "$HERE/main.swift" "$WORK/jni_boot.o" \
    -framework AppKit -framework QuartzCore -framework Metal \
    -o "$OUT.tmp"
  mv "$OUT.tmp" "$OUT"
fi

echo "$OUT"