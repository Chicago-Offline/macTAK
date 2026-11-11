#!/usr/bin/env bash
#
# Shell exit-code canary: a render that fails must make the shell exit
# non-zero. The basemap named here does not exist, so WindowApp stops with
# its usage/environment code 2 and the shell must pass that on. Before the
# fix the shell exited 0 on every run: AppKit's terminate ends the process
# itself, and the code that read WindowApp's exit code never ran
# (dev/g2/NOTES.md §G2.5, trap #56).
#
# Opens a real window for a moment, so it needs a GUI session: a dev-Mac
# manual gate, the second leg of `make test-shell`. Needs what
# dev/g2/shell/run.sh needs (make build-angle, make gdal-java).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # <repo>/dev/g2/shell/test
LOG="$(mktemp "${TMPDIR:-/tmp}/shell-exit.XXXXXX")"
trap 'rm -f "$LOG"' EXIT

set +e
bash "$HERE/../run.sh" --basemap /nonexistent.mbtiles > "$LOG" 2>&1
rc=$?
set -e
grep -E '^(window-app: no such basemap|mactak-shell: (render failed|exiting))' "$LOG" | sed 's/^/    /' || true

if [[ "$rc" -eq 2 ]] && grep -q '^window-app: no such basemap' "$LOG"; then
  echo "[shell-exit] PASS: a failed render exits $rc"
else
  echo "[shell-exit] FAIL: expected exit 2 after 'no such basemap', got exit $rc" >&2
  exit 1
fi
