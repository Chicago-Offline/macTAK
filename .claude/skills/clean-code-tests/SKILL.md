---
name: clean-code-tests
description: Clean Code test rules for macTAK, reconciled with the repo's canary style — one behavior per check, readable, fast, independent, repeatable, and proven to fail against the bug (A/B or before/after CI). Use when writing or reviewing self-tests (CotEventSelfTest, DataPackageSelfTest), shell canaries (layer.sh, cot.sh, run.sh), GTest pins (jni-tests) or gate scripts.
---

# Clean Code: tests (macTAK)

## The rules, adapted

Clean Code asks for one assert per test, readable, fast, independent and
repeatable tests. macTAK's self-tests are plain `main` programs that run
many `check(cond, "what")` calls, with no JUnit (repo idiom: shell
orchestrates javac + java). Keep that style, and apply the rule as
**one behavior per check**:

- Each `check(...)` pins one behavior, and its message names that
  behavior as a sentence (`"missing lat refused: " + msg`). A failing
  run then reads like a list of broken behaviors.
- **Readable:** arrange, act, assert in that order; build fixtures
  inline when short.
- **Fast:** pure-JDK canaries (`cot.sh`, `datapackage.sh`) need no
  natives and no network; keep them that way.
- **Independent:** no check relies on another's side effects; each
  shell leg gets its own work dir (`--layer-db "$WORK/layers-$(basename "$f")"`).
- **Repeatable:** temp dirs via `mktemp -d` with an EXIT trap; no
  wall-clock or network dependence in assertions; fixed fixtures
  (`sf-landmarks.geojson`).

## macTAK additions (house style)

- **A test must fail without the fix.** Show it: A/B the new checks
  against `main`'s sources, or run CI on the guard-only commit first
  (G1.12: `fb844ba` red, the patch green). Quote both in NOTES.
- **Assert values, not just counts.** A count-only canary passed with
  every coordinate flipped (G1.12 trap). Pin a known value.
- **Fail loudly with the reason.** `[layer-test] FAIL <file>: Ferry
  Building at lat/lon …, expected …`, never a bare non-zero exit.
- **Gates can't lie.** No sentinel output that passes on absence; check
  that the artifact was produced this run (CONTRIBUTING's gate ladder).
- **Never skip, disable or quarantine a test to get green.** Flaky
  twice means a fix issue, not a retry.
