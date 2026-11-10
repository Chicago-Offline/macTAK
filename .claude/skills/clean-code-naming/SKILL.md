---
name: clean-code-naming
description: Clean Code naming rules for macTAK — descriptive, unambiguous, meaningful distinctions, pronounceable, searchable names; named constants instead of magic numbers; no encodings or type prefixes. Use when naming variables, functions, classes, files, branches, patches, test checks or NOTES traps.
---

# Clean Code: naming (macTAK)

- **Descriptive and unambiguous.** A name says what the thing is or
  does: `sendDelayMs`, `sendPeriodMs`, `FIXTURE_FEATURES`,
  `check_ferry`.
- **Meaningful distinctions.** Don't number or noise-word names apart
  (`data2`, `infoObj`). If two things differ, the names say how:
  `certPath` vs `caPath`, `markerLat` vs `FERRY_LAT`.
- **Pronounceable and searchable.** Prefer `receivedAtMillis` to `rcvTs`.
  A name you can grep for across Java, bash and NOTES is worth more than
  a short one.
- **Replace magic numbers with named constants.** `CotXml.MAX_LAT`,
  `MAX_BUF = 1 << 20`, `FERRY_LAT=37.7955`. Exit codes, ports, timeouts
  and tolerances are named at the top of the file. Units go in the name
  (`…Ms`, `…Millis`, `…Deg`).
- **No encodings.** No Hungarian notation, no `m_`/`str`/`i` prefixes in
  code macTAK owns. (Upstream engine C++ uses `m_` members; inside a
  patch, match the surrounding upstream style rather than fighting it.)

## Names beyond code

The house style names other things too; keep them consistent:

| Thing | Convention |
|---|---|
| Branch | `<area>/<kebab-topic>` (`g1/ogr-axis-order`) |
| Commit subject | `<area>: <topic> — <summary>` |
| Engine patch | `<what>-<fix>.patch`, kebab-case (`gdal3-axis-order-cpp-default.patch`) |
| Self-test check | the behavior pinned, as a sentence (`"missing lat refused"`) |
| NOTES section | `§G<n>.<m> — <topic> (<date>, branch …, PR #…)` |
