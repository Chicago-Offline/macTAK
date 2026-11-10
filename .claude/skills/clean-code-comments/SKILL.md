---
name: clean-code-comments
description: Clean Code comment rules for macTAK, reconciled with CONTRIBUTING's house style — explain yourself in code, but keep the why at patch sites and trap references; comment intent, clarification and warnings; no redundant, noisy, closing-brace or commented-out code. Use when adding or reviewing comments in Java, C/C++ patches, Swift, bash or Python.
---

# Clean Code: comments (macTAK)

## The rule, and the house-style reconciliation

Clean Code says: try to explain yourself in code; a comment is a
fallback. macTAK's house style says: **the why goes in comments at the
patch site** and traps are referenced where they bite. These agree once
you separate *what* from *why*:

- **Never** comment *what* the next line does. Make the code say it
  (better names, an extracted function).
- **Always** comment *why* when the reason isn't visible in the code:
  an upstream quirk, a GDAL/JVM/ANGLE behavior, a trap. That is the
  "intent" and "warning of consequences" the gist allows, and it is
  mandatory here.

## Good comments (keep and write these)

- **Intent:** why this approach (`// Direct children only: <detail> may
  nest its own <point>…`).
- **Warning of consequences:** `// Do NOT broadcast here. commo fires
  interfaceUp listeners BEFORE…` (G1.2 trap).
- **Patch-site rationale:** an engine patch hunk carries its why in a
  comment at the site, so a reader of the patched engine finds the
  reason without the NOTES. New hunks use the
  `// macTAK (G…, trap #…): <why>` prefix (12 of the 34 patches already
  do); don't rewrite older hunks just to adopt it.
- **Clarification** of something you can't rename (an upstream API's
  odd contract, a `jsize` truncation).

## Bad comments (remove or don't write)

- Redundant: `// increment i` above `i++`.
- Noise and closing-brace markers: `} // end if`.
- **Commented-out code.** Delete it; git has it. (CONTRIBUTING also
  bans debug prints and scratch diagnostics at merge.)
- Comments that drift: if the code changes, the comment changes in the
  same commit. A stale comment is worse than none.

## Javadoc / doc comments

Public classes and non-obvious methods in `dev/g1/headless` carry a
short doc comment stating the contract (inputs, refusals, what is
returned), as `CotEvent` and `CotXml` do. No doc comment on a trivial
getter.
