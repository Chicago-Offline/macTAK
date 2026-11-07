#!/usr/bin/env bash
#
# G1.2 — live CoT round-trip smoke: two atak-headless clients ↔ a TAK server.
#
# Client A listens; client B announces presence and drops a marker. PASS
# only when both interfaces came up, B's presence + marker went out, A
# received B's marker and saw B as a contact, and B received A's presence
# — the full tx + rx loop on the commoncommo stack, relayed by the server.
#
# G1.4: each client persists what it receives into its own tracks DB
# (--db, through the engine's DatabaseImpl JNI) and prints the latest fix
# per uid on exit. PASS additionally requires A's DB to hold B's presence
# and marker (per the exit demo and, when sqlite3 is on PATH, read back
# from the file independently) and B's DB to hold A's presence.
#
# Two clients because a TAK server does not reflect a client's own events
# back to it: the one-client form only passed on FreeTAKServer, which
# sends a welcome GeoChat and echoes the sender's marker. OpenTAKServer
# (and TAK Server) do neither. Verified against both.
#
# usage: run.sh [DURATION] [LAT] [LON]
# env:   TAK_HOST (default 127.0.0.1), TAK_PORT (default 8087 — FreeTAKServer
#        plaintext; OpenTAKServer uses 8088). FTS_HOST/FTS_PORT still honoured.
#        TAK_CERT + TAK_CA (PKCS#12 client cert + truststore from an ATAK data
#        package) switch both clients to SSL (OpenTAKServer 8089); passwords via
#        MACTAK_CERT_PASS / MACTAK_CA_PASS (default 'atakatak').
#        TAK_UID_PREFIX overrides the stable client uid prefix (default
#        g1-rt-<hostname>; clients are <prefix>-a and <prefix>-b).
#        e.g. FreeTAKServer: docker run -d --name freetakserver --network host \
#                                vigsecdrone/freetakserver:latest
# CI: also runs hermetically against dev/g1/headless/fake-cot-server.py (.github/workflows/cot-roundtrip.yml)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DURATION="${1:-12}"
LAT="${2:-37.7749}"
LON="${3:--122.4194}"
TAK_HOST="${TAK_HOST:-${FTS_HOST:-127.0.0.1}}"
TAK_PORT="${TAK_PORT:-${FTS_PORT:-8087}}"
SSL=()
if [[ -n "${TAK_CERT:-}" ]]; then
  [[ -n "${TAK_CA:-}" ]] || { echo "run.sh: TAK_CERT needs TAK_CA too" >&2; exit 1; }
  SSL=(--cert "$TAK_CERT" --ca "$TAK_CA")
fi
# Stable uids, not per-run ones: OpenTAKServer declares a durable RabbitMQ
# queue per EUD uid bound to its `cot` fanout exchange and never deletes it,
# so a fresh uid per run leaks two bound, unconsumed queues on the server
# (every CoT is copied into each; the broker eventually alarms and blocks all
# fan-out). Per-host so two developers' runs don't share an identity.
HOST_TAG="$(hostname -s 2>/dev/null | tr -c 'A-Za-z0-9\n' '-' || echo local)"
UID_A="${TAK_UID_PREFIX:-g1-rt-$HOST_TAG}-a"
UID_B="${TAK_UID_PREFIX:-g1-rt-$HOST_TAG}-b"
# Callsign = uid (upper-cased): OpenTAKServer's cot_parser drops every CoT from
# a uid whose callsign is already registered to a different uid — silently, the
# client sees nothing — so the pair must never collide with an older EUD row.
CS_A="$(echo "$UID_A" | tr '[:lower:]' '[:upper:]')"
CS_B="$(echo "$UID_B" | tr '[:lower:]' '[:upper:]')"
# One temp dir, not two `mktemp -t` files: those each created a file and the
# script then appended ".log" to the returned NAME — the created zero-byte
# files were never used and never removed (two fresh orphans in $TMPDIR per
# run). The trap cleans the dir on the way out; the FAIL paths disarm it
# first and keep the evidence on disk.
RTRUN="$(mktemp -d "${TMPDIR:-/tmp}/g1-roundtrip.XXXXXX")"
trap 'rm -rf "$RTRUN"' EXIT
LOG_A="$RTRUN/roundtrip-a.log"
LOG_B="$RTRUN/roundtrip-b.log"
DB_A="$RTRUN/roundtrip-a.sqlite"
DB_B="$RTRUN/roundtrip-b.sqlite"
# Mirror the evidence into the checkout: CI's cot-roundtrip workflow uploads
# this dir as an artifact on failure (the runner's TMPDIR dies with the job).
keep_failed() {
  local dest="$HERE/build/failed-runs"
  mkdir -p "$dest"
  cp -f "$LOG_A" "$LOG_B" "$DB_A" "$DB_B" "$dest/" 2>/dev/null || true
  echo "[g1-roundtrip] evidence mirrored into $dest" >&2
}

nc -z -G 2 "$TAK_HOST" "$TAK_PORT" >/dev/null 2>&1 \
  || { echo "run.sh: no TAK server on $TAK_HOST:$TAK_PORT — start one first (set TAK_HOST/TAK_PORT)" >&2; exit 1; }
echo "[g1-roundtrip] server reachable at $TAK_HOST:$TAK_PORT${TAK_CERT:+ (ssl)} — running two atak-headless clients ${DURATION}s"

# Warm the launcher's on-demand javac BEFORE the clients go out: they
# launch 1s apart, and the second one's freshness check finds the first
# one's half-written build/classes (or none yet), skips compiling and
# execs java into it — dead before its first line, exit 1 (observed
# 2026-11-06 in cot-roundtrip once ConnectOptions.java made the compile
# outlast the 1s offset; the race was latent since G1.4). A no-arg
# atak-headless compiles, loads the natives, prints usage and exits 2:
# anything else — 1 a compile failure, 3 a native/JVM failure — stops
# here loudly instead of failing eight assertions later.
set +e
bash "$HERE/atak-headless" >/dev/null 2>&1
warm_rc=$?
set -e
[[ "$warm_rc" -eq 2 ]] \
  || { echo "run.sh: launcher warm-up failed (exit $warm_rc — compile or native load problem)" >&2; exit 1; }

set +e
bash "$HERE/atak-headless" --connect "$TAK_HOST:$TAK_PORT" ${SSL[@]+"${SSL[@]}"} \
    --uid "$UID_A" --callsign "$CS_A" --duration "$DURATION" --db "$DB_A" \
    > "$LOG_A" 2>&1 &
pid_a=$!
# A is registered before B's first (and only) marker goes out. Wait for A's
# actual "iface-up" instead of a fixed 1s — the sleep raced the launcher's
# compile (latent since G1.4, observed 2026-11-06 in cot-roundtrip when
# ConnectOptions.java pushed the compile past the offset). Bounded: 90 s,
# then fail loudly with A's log rather than hanging or racing.
deadline=$((SECONDS + 90))
until grep -q 'headless: iface-up' "$LOG_A" 2>/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "run.sh: A never reached iface-up within 90s — A's log:" >&2
    tail -n 40 "$LOG_A" >&2 || true
    kill "$pid_a" 2>/dev/null || true
    trap - EXIT   # keep the evidence for inspection
    keep_failed
    exit 1
  fi
  sleep 0.2
done
bash "$HERE/atak-headless" --connect "$TAK_HOST:$TAK_PORT" ${SSL[@]+"${SSL[@]}"} \
    --uid "$UID_B" --callsign "$CS_B" --duration "$DURATION" --send-marker "$LAT" "$LON" --db "$DB_B" \
    > "$LOG_B" 2>&1
rc_b=$?
wait "$pid_a"
rc_a=$?
set -e

for side in A B; do
  log="LOG_$side"
  echo "--- client $side ---"
  grep -E '^headless: |commo\[ERROR\]' "${!log}" | grep -v 'presence-heartbeat' | cut -c1-200 || true
done

fail=0
check() {   # check LOG PATTERN MESSAGE
  grep -q -- "$2" "$1" || { echo "[g1-roundtrip] FAIL: $3" >&2; fail=1; }
}
check "$LOG_A" 'headless: iface-up'              "A: interface never came up"
check "$LOG_B" 'headless: iface-up'              "B: interface never came up"
check "$LOG_B" 'headless: presence-sent'         "B: presence never announced"
check "$LOG_B" 'headless: marker-sent'           "B: marker never sent"
check "$LOG_A" "headless: contact-added $UID_B"  "A: never saw B as a contact"
check "$LOG_A" "uid='$UID_B-m1'\\|uid=\"$UID_B-m1\"" "A: B's marker never arrived"
check "$LOG_B" "uid='$UID_A'\\|uid=\"$UID_A\""   "B: A's presence never arrived"
# G1.4 exit demo: the tracks DB (engine DatabaseImpl JNI -> sqlite) holds what was received.
check "$LOG_A" "headless: track uid=$UID_B-m1 type=a-f-G-U-C" "A: B's marker not in A's tracks DB"
check "$LOG_A" "headless: track uid=$UID_B type=a-f-G-U-C"  "A: B's presence not in A's tracks DB"
check "$LOG_B" "headless: track uid=$UID_A type=a-f-G-U-C"  "B: A's presence not in B's tracks DB"
[[ "$rc_a" -eq 0 ]] || { echo "[g1-roundtrip] FAIL: client A exited $rc_a" >&2; fail=1; }
[[ "$rc_b" -eq 0 ]] || { echo "[g1-roundtrip] FAIL: client B exited $rc_b" >&2; fail=1; }

# Independent witness: read A's DB file back with the sqlite3 CLI (macOS ships one).
if command -v sqlite3 >/dev/null 2>&1; then
  n_marker="$(sqlite3 "$DB_A" "SELECT COUNT(*) FROM tracks WHERE uid='$UID_B-m1'" 2>/dev/null || echo 0)"
  n_total="$(sqlite3 "$DB_A" "SELECT COUNT(*) FROM tracks" 2>/dev/null || echo 0)"
  echo "[g1-roundtrip] sqlite3 $DB_A: $n_total fix(es), $n_marker for B's marker"
  [[ "$n_marker" -ge 1 ]] || { echo "[g1-roundtrip] FAIL: sqlite3 finds no row for B's marker in A's DB" >&2; fail=1; }
else
  echo "[g1-roundtrip] sqlite3 not on PATH; on-disk DB check skipped"
fi

if [[ "$fail" -eq 0 ]]; then
  echo "[g1-roundtrip] PASS — both ifaces up, B's presence+marker relayed to A, A's presence relayed to B, both persisted in the tracks DBs"
  # logs + DBs go with the temp dir via the EXIT trap
else
  trap - EXIT   # keep the evidence for inspection
  keep_failed
  echo "[g1-roundtrip] client logs kept: $LOG_A $LOG_B (DBs: $DB_A $DB_B)" >&2
  exit 1
fi
