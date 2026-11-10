---
name: clean-code-source-structure
description: Clean Code source-structure rules for macTAK — vertical separation of concepts, related code dense and together, variables declared near use, dependent functions close and top-down, short lines, whitespace to group, consistent indentation. Use when laying out a new file or reorganising Java, Swift, C, bash or Python in macTAK-owned code.
---

# Clean Code: source structure (macTAK)

- **Separate concepts vertically.** A blank line between concepts;
  none inside one. In bash, a comment header per stage (`# --- work tree
  ---` in `build-native.sh`).
- **Related code reads densely.** Keep the lines of one idea together;
  don't scatter a constant from the code that uses it.
- **Declare variables close to their use.** In Java, at first use and
  `final` when not reassigned. In bash, `local` at the top of the
  function that uses them.
- **Dependent functions close together.** A helper sits next to its
  caller (`check_ferry` right above `run_one`).
- **Similar functions together.** Group overloads and siblings
  (`coord` / `optional` / `decimal` in `CotEvent`).
- **Top-down order.** Callers above callees, public API first, private
  helpers after; a script reads in execution order (preflight → work →
  checks → verdict).
- **Short lines.** Keep code lines short enough to read without
  scrolling: the headless Java mostly stays under ~110 columns (a few
  run to ~125). NOTES and CHANGELOG prose wraps around 72–76.
  markdownlint's MD013 is off, so this is house practice, not a lint
  rule.
- **Don't break indentation; use whitespace for association.** Follow
  the file's existing indentation (Java 4 spaces; shell 2 in most dev
  scripts). Do not realign upstream engine code inside a patch.
- **Configuration at the top.** Paths, versions and tunables open the
  file (see `clean-code-design`).

## Docs are source too

NOTES sections follow the same structure rules: State first, then what
changed, traps (each with its guard), known-benign noise, and evidence
last, quoting real log lines.
