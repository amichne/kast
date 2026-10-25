# Kast callback completion issues

The installed candidate fixes the nested-supplier failure. The scoped-caller failure did not recur in the two-module fixture.
The comparison ran on October 24, 2026, with baseline `0.0.3708` and candidate `0.20261024.195221`.

## 1. A nested supplier blocks a known callee

**Issue:** The baseline resolved `wrapper → sink`, but rejected `CALLEES` because the callback supplier sat inside another callback.
It rejected even when the outer `kotlin.run` had a known execution contract.

**Smallest reproduced structure:**

```kotlin
fun sink(value: String) = value
fun wrapper(f: () -> String) = sink(f())
fun entry() = kotlin.run { wrapper { "x" } }
fun plain() = sink("x")
```

**Query:** Select the exact `wrapper`, then expand `CALLEES` within its directory and production `main` sources.

**Before:** Both runs rejected with `COMPLETION_UNPROVEN`, `CALLBACK_GRAPH_UNPROVEN`, and `NESTED_CALLBACK_EXECUTION`.
The preview kept `sink` and `originalCoverage: COMPLETE`.
That coverage described the direct relation, not the full callback graph.

**After:** Both runs returned a complete result with `sink`.
The repair carries the exact anonymous owner and its execution proof through supplier admission, reuse, and serialization.
Default arguments also keep the exact enclosing caller identity.

**Controls:** `plain`, a direct supplier, and `wrapper` without a supplier completed in both versions.
An opaque supplier still rejected with `EXTERNAL_CALLABLE` and kept `sink`.
Thus, `sink(f())` alone did not reproduce this failure.

## 2. An external callback affects a scoped caller query

**Reported issue:** The photograph showed a caller query restricted to module A that rejected because of a callback outside A.
Its cause included `DEPENDENCY_CONTRACT_EFFECT_UNPROVEN`.

**Executed structure:** Module B depends on module A.

```kotlin
// Module A, package scoped
fun target(): Int = 1
fun caller(): Int = target()
```

```kotlin
// Module B, package outside
fun unrelated(): List<Int> = listOf(1).map { scoped.target() }
```

**Query:** Expand `CALLERS(target)` with `SOURCE_DOMAIN` restricted to module A.

**Result:** Both versions completed with `caller` in both runs.
The module-B and workspace queries still rejected with `DEPENDENCY_CONTRACT_EFFECT_UNPROVEN`.
A separate control preserved the caller when an external supplier connected to an invocation inside A.
The scoped-caller repair preceded the baseline in PR #1088. This run found no new failure in that case.

## Evidence and limits

| Installed version | Relation queries | Complete | Rejected |
| --- | ---: | ---: | ---: |
| `0.0.3708` | 22 | 14 | 8 |
| `0.20261024.195221` | 22 | 16 | 6 |

Each version ran eleven relations twice, plus 22 discoveries and eight compiler diagnostic queries.
All candidate oracles passed. The six candidate rejections were expected controls.
All 52 candidate calls had diagnostic joins. Thirteen fixture files and all request grants stayed unchanged.
Fresh discovery supplied new exact references for each relation.

The standard checkout installer installed both components. A terminal restart started IntelliJ `IU-262.10968.63` with the candidate.
All 39 installed plugin files matched the archive. All 71 captured class resources matched their installed jars.
DEBUG output remained enabled.

Eight native callee tests and five native scope tests passed with SDK `262.9437.185`.
They cover known nested owners, forwarding, defaults, and explicit rejection of unproved execution.
Contract and wire tests reject missing, duplicate, stale, and substituted owner proofs.

The full gate found a stale schema fixture and excessive schema duplication.
One shared definition and a regenerated fixture resolved both failures. All 28 selected schema tests passed.
The capability document occupies 1,025,211 bytes within the unchanged 1,044,480-byte threshold.
Host and Control need paired installation because the supplier evidence shape changed.

The install exposed two separate failures: a Gradle configuration-cache capture and rejection of stopped tool-session receipts.
Both repairs passed the standard installation. All 54 lifecycle tests passed, including live-process and held-lock rejection controls.

The original private workspace was unavailable. The earlier Spring, Ktor, and kotlinx.coroutines failures were not rerun with this candidate.
These results do not prove full callback coverage or resolve those corpus failures.
The native OpenWiki update expanded one requested page into 47 jobs. The narrow knowledge correction remains pending.

The [pull request](https://github.com/amichne/kast/pull/1090) contains the implementation and regression tests.
The private evaluation bundle keeps requests, replies, source hashes, traces, and installation receipts.
