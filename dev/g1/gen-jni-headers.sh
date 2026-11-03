#!/usr/bin/env bash
#
# Regenerates the javah JNI headers committed in dev/g1/jni-include/ from the
# published takkernel jars in mavenLocal.
#
# The JNI glue TUs (engine/src/main/jni/j*.cpp) #include these generated
# headers. Upstream generates them via a gradle javah task; here they are a
# pinned, committed artifact of the takkernel version below so the native
# build stays JDK- and gradle-free. Regenerate ONLY at engine version bumps
# (bump TAKKERNEL_VER, publishToMavenLocal, run this, commit the diff).
set -euo pipefail

DEV="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # <repo>/dev
ROOT="$(cd "$DEV/.." && pwd)"
OUT="$DEV/g1/jni-include"
VER="${TAKKERNEL_VER:-0.59.1}"
M2="${HOME}/.m2/repository"
CP="$M2/gov/tak/kernel/takkernel-engine/$VER/takkernel-engine-$VER.jar"
CP="$CP:$M2/gov/tak/kernel/takkernel-port/$VER/takkernel-port-$VER.jar"
CP="$CP:$M2/gov/tak/kernel/takkernel-annotation/$VER/takkernel-annotation-$VER.jar"
CP="$CP:$M2/gov/tak/support/android-port/3.1.0/android-port-3.1.0.jar"

for j in $(echo "$CP" | tr ':' '\n'); do
  [[ -f "$j" ]] || { echo "FATAL: missing jar: $j" >&2
    echo "       run: ./gradlew publishToMavenLocal (engine, port, annotation)" >&2; exit 1; }
done

JAVAH="${JAVA_HOME:-$("/usr/libexec/java_home" -v 1.8)}/bin/javah"
command -v "$JAVAH" >/dev/null 2>&1 || { echo "FATAL: javah not found (need JDK 8)" >&2; exit 1; }

CLASSES_OK=0
while IFS= read -r line; do
  hdr="${line%%=*}"            # e.g. jdatabaseimpl.h or c3dt/jgltf.h
  classes="${line#*=}"         # comma-separated FQCNs
  mkdir -p "$OUT/$(dirname "$hdr")"
  # shellcheck disable=SC2086
  "$JAVAH" -classpath "$CP" -o "$OUT/$hdr" \
    $(echo "$classes" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  CLASSES_OK=$((CLASSES_OK+1))
done < <(grep -E '^[A-Za-z].*\.h=' \
  "$ROOT/src/takkernel/engine/src/main/jni/javah.properties")

echo "[g1-javah] OK: $CLASSES_OK headers ($(find "$OUT" -name '*.h' | wc -l | tr -d ' ') files)"
