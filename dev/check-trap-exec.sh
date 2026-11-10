#!/usr/bin/env bash
#
# Fail when a script under dev/ arms an EXIT trap and then `exec`s: exec
# replaces the shell, so the trap never runs and whatever it was to clean
# up (a mktemp scratch dir, a background server) is left behind on every
# run. dev/g2/shell/run.sh did this with its scratch MACTAK_HOME
# (dev/g2/NOTES.md §G2.5, trap #54). A script that execs must own nothing
# to clean up; a script with a trap runs its last command normally and
# exits with that command's status.
#
# Used by `make lint` and CI's lint job.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bad=0
while IFS= read -r script; do
  if grep -Eq '^[[:space:]]*trap[[:space:]].*EXIT' "$script" \
     && grep -Eq '^[[:space:]]*exec[[:space:]]+[^0-9<>]' "$script"; then
    echo "[check-trap-exec] FAIL $script: sets an EXIT trap and execs; the trap never fires" >&2
    bad=1
  fi
done < <(git ls-files 'dev/*.sh' 'dev/**/*.sh' dev/g1/headless/atak-headless)

if [[ "$bad" -ne 0 ]]; then
  exit 1
fi
echo "[check-trap-exec] OK: no script execs past an EXIT trap"
