---
name: clean-code-understandability
description: Clean Code understandability tips for macTAK — be consistent, explanatory variables, encapsulate boundary conditions, value objects over primitives, no hidden logical dependencies, no negative conditionals. Use when code works but is hard to follow, or when reviewing logic for clarity.
---

# Clean Code: understandability (macTAK)

- **Be consistent.** If you do something one way, do all similar things
  that way. Example of drift to avoid: `LayerCLI` names its exit codes
  (`EXIT_OK`, `EXIT_FAIL`, `EXIT_USAGE`) while `HeadlessCLI` returns
  literal `2`/`3`/`4`. New code follows the named form.
- **Use explanatory variables.** Name an intermediate result instead of
  nesting expressions: `final double d = decimal(attr, v);` then test
  `d`, rather than re-parsing inline.
- **Encapsulate boundary conditions.** Put range and edge handling in
  one place. `CotEvent.coord(pt, "lat", 90.0)` owns "present, plain
  decimal, finite, within ±limit"; callers never repeat it.
- **Prefer value objects to primitives.** Group values that travel
  together (`ConnectOptions.Resolved`, `DataPackage.Resolved`,
  `TrackStore.Track`) instead of passing parallel strings and ints.
- **Avoid hidden logical dependencies.** If one call must precede
  another, make the code enforce it, not a comment. `LayerStore`'s
  constructor calls `NativeGdal.init` before registering providers, so
  callers cannot open a layer first (G1.12 relies on this ordering).
- **Avoid negative conditionals.** `if (isValid)` reads better than
  `if (!isInvalid)`. In bash, prefer `[[ -n "$x" ]]` over
  `! [[ -z "$x" ]]`.

## Bash specifics

- Quote every expansion; prefer `[[ … ]]`; one purpose per helper
  function (`check_ferry`, `run_one`, `convert_run` in `layer.sh`).
- Make failure loud: a step that can fail must say *what* failed
  (`[layer-test] FAIL … expected exactly N`), never pass quietly with
  shrunk coverage (the old `ogr2ogr … && run_one` form did).
