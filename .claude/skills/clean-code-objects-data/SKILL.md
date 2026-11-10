---
name: clean-code-objects-data
description: Clean Code rules for objects and data structures in macTAK — hide internal structure, prefer plain data structures or objects but not hybrids, keep classes small and doing one thing, few instance variables, prefer non-static methods where state exists. Use when designing a Java class, Swift type or C struct in macTAK-owned code.
---

# Clean Code: objects and data structures (macTAK)

- **Hide internal structure.** An object exposes behavior, not its
  fields. `TrackStore` exposes `record`, `latest`, `count`, `summary`;
  the SQL schema and the `DatabaseIface` stay private.
- **Data structure *or* object, not a hybrid.** A value carrier with
  public final fields and no behavior (`CotEvent`, `ConnectOptions.Resolved`,
  `TrackStore.Track`) is fine; so is an object with private state and
  methods. A class with public mutable fields *and* behavior is the
  hybrid to avoid.
- **Small, doing one thing.** One reason to change per class:
  `CotXml` builds outgoing CoT, `CotEvent` parses incoming CoT,
  `TrackStore` persists, `LayerStore` opens layers.
- **Few instance variables.** If a class needs many, it is probably two
  classes.
- **Base classes know nothing about derivatives.**
- **Many small functions over passing code into a function to select
  behavior.**
- **Non-static methods, where there is state.** A stateful component is
  an instance (`TrackStore`, `LayerStore`, both `AutoCloseable`).
  Stateless helpers can stay static and package-private (`CotXml`,
  `CotEvent.parse`): Java 8 and a CLI host don't need instances for pure
  functions. Don't churn existing static utilities for style alone.

## Resource ownership

An object that holds a native handle (sqlite DB, feature store, JNI
global ref) implements `AutoCloseable` (or the language's equivalent),
closes idempotently, and refuses use after close (`TrackStore.closed`).
In JNI C++, every `NewGlobalRef` has its `DeleteGlobalRef` in the
destructor.
