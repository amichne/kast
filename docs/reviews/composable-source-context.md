---
type: Review
title: "Query source context"
description: "Records structural source reads, strict reference reuse, native rejection controls, and two matched protocol workflows."
resource: "repo://kast/reviews/composable-source-context"
tags: [kast, query, source, evidence]
timestamp: "2026-10-22T08:10:37Z"
source_revision: 527308fc70b493221ceba437ce97ed7fa1ac4d59
code_sources:
  - path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolSourceMapping.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceRequestAdmission.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/NativeSourceRegion.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTextProjection.kt
  - path: runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeSourceContextTest.kt
  - path: runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeSourceContextFixture.kt
  - path: runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeSourceContextWorkflow.kt
---

# Query source context

Public contract 15 exposes structural source reads from issued exact references, occurrence candidates, and source selectors.
The existing `RUN` coordinator owns discovery, relationship expansion, stage order, and exact output.
Successive `READ_SOURCE` requests select a region and text size without caller-supplied coordinates.
The [public procedure](../public/reference/query-pipelines.mdx#read-source-around-an-occurrence) gives the request choices.

Exact reads restore the original reference and reject stale authority without first-page reacquisition.
Source selectors preserve their snapshot and source scope through re-entry.
`EXCERPT` accepts `maxLines` from 1 to 1000, with a default of 40.
Its text selector has kind `WINDOW`, separate from the selected structural region.
A later request can read that region through its issued selector without query replay.
The existing `SOURCE` output keeps its prior window behavior.

A native control reproduced a wrong callable body when a window spanned sibling declarations.
The source owner now needs full-range containment.
Its ancestor walk stops at the Kotlin file.
The control now returns `REGION_ABSENT` for callable-body, enclosing-body, and enclosing-declaration requests.
Bounded counters distinguish selected and absent regions.
The fixture also keeps the existing typed evidence for unexpected adapter failures.

The source baseline was `ebc805014ada9e3eb1b85ef611e41bd176af8d80`.
The measured producer was `527308fc70b493221ceba437ce97ed7fa1ac4d59`, with a clean checkout at entry.
The native suite started at the timestamp in this report.
Both strategies ran through that producer, rather than separate old and new binaries.

The fixture used real K2 and indexed PSI with an authored source-root model.
Its source had two overloads, a nested namesake, three distinct references, and 200 unrelated comment lines.
Both strategies used source set `main`, directory `src`, production sources, and excluded generated sources.
The grants were 100 results, 100,000 work units, 15,000 milliseconds, and 524,288 returned bytes.
The source cap was 65,536 bytes.
The [fixture and independent assertions](../../runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeSourceContextTest.kt) own the exact input and expected bodies.

The declaration task used discovery, `CALLEES`, and symbol output in one `RUN`.
The occurrence task used discovery, `CALLEES`, `DISTINCT`, `REFERENCES`, and occurrence output in one `RUN`.
The file strategy read the whole file to answer the initial and successive questions.
The structural strategy read a two-line excerpt, then the issued body selector.
The assertions found the expected declaration or nested body in both responses.
Only the fixture oracle used offsets to identify expected results. Requests used issued references.

| Task and strategy | Protocol calls | Source reads | Response bytes | Source text bytes | Elapsed milliseconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| Declaration, file | 2 | 1 | 35,896 | 15,656 | 95 |
| Declaration, structural | 3 | 2 | 23,967 | 99 | 15 |
| Occurrence, file | 2 | 1 | 42,850 | 15,656 | 178 |
| Occurrence, structural | 3 | 2 | 29,886 | 73 | 22 |

Response bytes include the canonical query and source projections, including issued selectors.
They exclude requests, schemas, transport frames, and model context.
The compact public schema grew from 47,963 to 49,919 bytes.
These results prove less returned text and smaller responses for the two fixtures.
They do not prove fewer calls or lower total model cost.
The fixed file-first order, caches, and scheduling prevent a latency-improvement claim.

Recorded discovery and relationship counters matched within each pair.
Each strategy used one discovery page and one compiler refinement.
The declaration task used one relation page with two facts.
The occurrence task used two relation pages with five facts.
Structural selection increased from one read to two reads.
These counters do not sum every budget charge or native effect.

The exact native command passed five source cases and two existing scoped TRACE cases:

```sh
mise exec -- ./gradlew :runtime:hosted:nativeFixtureTest --rerun \
  --tests '*NativeSourceContextTest' \
  --tests '*NativeScopedCacheManagerTraceTest' --console=plain
```

The native cases cover overload identity, distinct occurrences, nested owners, region re-entry, stale exact/source references, oversized text, and windows across siblings.
Focused checks also passed schema admission, canonical excerpt encoding, zero/excess line rejection, blank-line counting, source service admission, and strict reference restoration.
`verifyJsonContracts`, `verifyArchitecture`, `knowledgeImpact`, and `verifyKnowledgeBase` passed.
The JUnit XML under `runtime/hosted/build/test-results/nativeFixtureTest` keeps the complete counter maps.
The pull request records the final delivery gate for its exact head.

No installed-host or observed model-iteration result forms part of this evidence.
The installed query rejected with `daemon-operation-lifecycle-model-reload-required`, and installed status returned `EPOCH_UNAVAILABLE`.
Native fixture success does not prove an installed plugin, a real Gradle import, or Tool RPC composition.
Public schema admission and native canonical execution have separate evidence here.

The OpenWiki refresh did not complete.
Its lifecycle expanded the two-page plan to 28 pages with historical drift.
No generated page changed, and the unfinished run metadata was restored.
Two public-contract claims still need refresh. Current source and public documentation remain the authority for this change.
