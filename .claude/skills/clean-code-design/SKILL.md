---
name: clean-code-design
description: Clean Code general and design rules for macTAK — follow conventions, keep it simple, boy-scout rule within scope, fix root causes, configuration at high levels, polymorphism over long conditionals, separate threading, dependency injection, Law of Demeter. Use when designing a change, choosing where code goes, or deciding whether a cleanup belongs in the current PR.
---

# Clean Code: general and design rules (macTAK)

## General rules

- **Follow standard conventions.** Here that means the repo's, before
  any language's: CONTRIBUTING.md's "Where changes land" table, branch
  and commit naming, `-source 8 -Xlint:all -Werror` for the headless
  Java, `shellcheck -x` + `bash -n` clean shell, `set -euo pipefail`.
- **Keep it simple.** Prefer the smallest change that fixes the cause.
  The G1.12 axis-order fix was one `CPLSetConfigOption` line in
  `GdalLibrary::initImpl`, not edits at six call sites.
- **Boy-scout rule, within scope.** Leave the lines you touch cleaner.
  Do *not* widen a PR to tidy unrelated code: CONTRIBUTING wants one
  focused patch per fix, and `src/` is read-only. Open a separate
  cleanup PR instead (precedent: #66, #77).
- **Find the root cause.** A symptom fix is not a fix. Every trap entry
  in NOTES states what bit and *what now guards it*; if you can only
  patch the symptom, say so in the trap entry and leave it open.

## Design rules

- **Configurable data at high levels.** Paths, versions and tunables
  belong at the top of a script or class, not deep inside it:
  `VER="${TAKKERNEL_VER:-0.59.1}"` heads every runner; a Java constant
  heads its class.
- **Prefer polymorphism to long if/else or switch** when the branches
  are *behaviors* that will grow. A command-line `switch` over flags (as
  in `HeadlessCLI.main`) is fine: it is parsing, not behavior.
- **Separate multi-threading code.** Keep what runs on commo's receive
  thread obvious and small (`TrackStore.record` is `synchronized`; the
  sender loop lives in its own thread). Never call back into commo from
  its own listener (G1.2 trap: sends inside `interfaceUp` go nowhere).
- **Don't over-configure.** No flag, env var or option without a caller
  that needs it today.
- **Use dependency injection** where it buys testability: pass the
  thing in (`ConnectOptions.resolve(...)` takes the data package and
  env values as arguments, which is why `DataPackageSelfTest` can test
  the precedence without a network or natives).
- **Law of Demeter.** A class should know its direct collaborators
  only. Don't chain through other objects' internals
  (`a.getB().getC().doX()`); add the method where the knowledge lives.

## Apply it

Before writing code, answer: does this change belong in this PR? Is it
fixing the cause? Is there a simpler place to make it? If a design rule
would force touching upstream engine lines, stop: the engine changes
only through minimal patches.
