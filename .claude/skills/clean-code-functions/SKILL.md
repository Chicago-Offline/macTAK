---
name: clean-code-functions
description: Clean Code function rules for macTAK — small, do one thing, descriptive names, few arguments, no side effects, no flag arguments. Use when writing or reviewing a Java method, Swift/C function, bash function or Python helper in macTAK-owned code.
---

# Clean Code: functions (macTAK)

- **Small.** A function should fit on a screen. When one grows past
  that, extract the steps that have names.
- **Do one thing.** `CotEvent.parse` reads an event; the number rules
  live in `coord`, `optional` and `decimal`; child lookup lives in
  `child`. Each does one thing and is named for it.
- **Descriptive names.** A long, exact name beats a short vague one
  (`resolveDataPackage`, `check_ferry`).
- **Few arguments.** Zero to two is ideal, three needs a reason. More
  than that usually means a value object is missing (see
  `clean-code-understandability`). `ConnectOptions.resolve` takes eight
  because it *is* the merge of every source; it returns one value
  object, and its test pins the precedence.
- **No side effects.** A function named for a query must not mutate
  state. If it must (`TrackStore.record`), its name says so.
- **No flag arguments.** `render(true)` hides two functions in one.
  Split it (`renderWithBasemap()` / `renderBare()`), or pass an enum.
  Bash exception: an optional *mode* word is acceptable when it reads
  as English (`run_one FILE N ferry`), but don't add a second one.

## macTAK specifics

- **One exit point for process exit.** `System.exit` never returns, so a
  `try`/`finally` around it does not run its `finally` (G1.11 trap).
  Compute the code, leave the `try`, then exit once.
- **JNI functions do one thing and check every call.** Each JNI call
  that can throw is followed by `ExceptionCheck`/`ExceptionClear`
  before anything else touches the env (waves 4–6 house rule).
- **Bash functions:** declare `local` variables, return a status, print
  the reason on failure, and keep `set -euo pipefail` semantics in mind
  (`|| ec=$?` to capture an expected failure).
