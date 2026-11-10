---
name: clean-code
description: Entry point for applying Clean Code principles (Robert C. Martin, via the wojteklu summary gist) to macTAK. Use when writing, reviewing, refactoring or auditing code macTAK owns — Java under dev/g1/headless and dev/g2, Swift/C under dev/g2/shell, bash under dev/, Python tools, and the engine patch files. Sets the scope, the precedence against CONTRIBUTING.md, and routes to the topic skills (clean-code-design, -understandability, -naming, -functions, -comments, -source-structure, -objects-data, -tests, -smells).
---

# Clean Code for macTAK

Code is clean when anyone on the team can read it, and readable code is
what stays changeable. This skill set adapts the *Clean Code* summary
(<https://gist.github.com/wojteklu/73c6914cc446146b8b533c0988cf8d29>) to
this repository. It does not replace CONTRIBUTING.md; it works inside it.

## Precedence (read first)

1. **CONTRIBUTING.md and the reading-order docs win.** Where a Clean Code
   rule and the house style disagree, follow the house style and say so.
   The known conflicts are resolved in the topic skills:
   - comments: the repo *requires* the why at the patch site and trap
     references (`clean-code-comments`);
   - tests: the repo's self-test `check()` style is kept
     (`clean-code-tests`);
   - "leave it better than you found it" never overrides "one focused
     patch per fix" or "`src/` is read-only" (`clean-code-design`).
2. **Correctness first.** A cleanup is never a reason to skip a gate.
   Run the affected `make` targets (or say honestly that they could not
   run) before pushing.
3. **Scope.** Apply these rules to code macTAK owns:

   | Path | Apply? |
   |---|---|
   | `dev/g1/headless/**`, `dev/g2/render/src/**`, `dev/g2/shell/**` | Yes, fully |
   | `dev/**/*.sh`, `dev/**/*.py`, `Makefile` | Yes, fully |
   | `dev/g1/patches/*.patch`, `dev/g2/render/engine-shadows/**` | Only to the lines the patch adds. Never restyle upstream lines: patches must stay minimal and apply at `--fuzz=0`, shadows must equal upstream + patch byte for byte |
   | `src/**` (the ATAK-CIV pin) | Never. Read-only |
   | `dev/g1/jni-include/**`, generated GLES shim output | Never by hand. Regenerate with the generator |

## How to use the set

- **Writing new code:** skim the topic skill for what you are writing
  (a function → `clean-code-functions`, a test → `clean-code-tests`).
- **Reviewing or auditing:** walk `clean-code-smells` first, then the
  topic skills for whatever smell you found.
- **Refactoring:** only inside the change you are making, or as its own
  focused PR with its own CHANGELOG entry. Never mix a drive-by
  refactor into a bug-fix PR; reviewers and `git bisect` both pay for it.

## Quick checklist

- [ ] Names say what the thing is or does; no magic numbers.
- [ ] Each function does one thing; few arguments; no flag arguments.
- [ ] Comments explain intent, a trap, or a consequence, never what
      the next line does.
- [ ] Related code sits together, top-down, declarations near use.
- [ ] No duplicated logic you were about to copy a third time.
- [ ] Tests are fast, independent and repeatable, and each check names
      the behavior it pins (and fails against the bug).
- [ ] Root cause fixed, not the symptom; a trap entry names its guard.

## Topic skills

| Skill | Gist section |
|---|---|
| `clean-code-design` | General rules + design rules |
| `clean-code-understandability` | Understandability tips |
| `clean-code-naming` | Names rules |
| `clean-code-functions` | Functions rules |
| `clean-code-comments` | Comments rules |
| `clean-code-source-structure` | Source code structure |
| `clean-code-objects-data` | Objects and data structures |
| `clean-code-tests` | Tests |
| `clean-code-smells` | Code smells |
