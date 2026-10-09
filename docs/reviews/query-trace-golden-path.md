# Query and TRACE: causes, changes, and proof limits

This change removes repeated work from the existing query interpreter and native
relation reader. The supported user path is: resolve one declaration, select its
issued reference, run TRACE, then follow completion or recovery evidence.
The [user tutorial](../public/search.mdx) and [agent skill](../../agent-tools/skills/kast/SKILL.md)
teach that same path. Advanced query forms remain available for specific questions.

## Evidence from the photographs

The supplied terminal photographs report Kast 0.8.0 and IntelliJ IDEA 2026.2 in
a large Kotlin repository. They contain observations from that environment;
they are not an execution receipt from this checkout.

| Observation | What it establishes |
| --- | --- |
| Exact location lookup found `CacheManager.execute` quickly. | The declaration was discoverable at its known position. |
| Scoped REFERENCES and CALLERS found `executeWithCache`. | Individual relation reads could establish the adapter link. |
| Implementations and overrides completed. | Those independent branches could establish their targets. |
| Scoped adapter CALLERS returned zero. | The requested directory excluded the client files. It does not prove workspace-wide absence. |
| Three method traces stopped near 30 seconds, including a 120-second request. | The observed host admitted less time than that caller requested. |
| Approximately 29.6–30 seconds accrued in `REFERENCE_INVENTORY`. | Native reference preparation dominated those requests. The aggregate cannot identify the expensive subject or branch. |
| A smaller `healthInfo` TRACE completed in 8.97 seconds. | TRACE could finish in that workspace for another subject. |
| Scoped name discovery for `execute` also stopped. | Name discovery had a separate incomplete execution. The photos do not identify its internal blocking stage. |

The reported 24 text matches are not a compiler-confirmed reference or caller
count. The photographs do not expose an exact installed source SHA, full requests,
complete host receipts, or the private repository needed for a matched replay.

## Causes confirmed in source

Investigation started from refreshed `origin/main` at
`7ec5427a664da2062a5cf0825749a22bee76d01b`.

The [host policy](../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt)
has a default 30,000 ms query ceiling. [Deadline admission](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDeadline.kt)
reserves completion time and clamps the semantic allowance to the remaining host
time. Asking for 120 seconds cannot remove that ceiling. The short default
semantic grant is 2,000 ms, so the trace example requests 30,000 ms explicitly.
The returned admitted budget remains authoritative.

The previous [TRACE scheduler](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt)
expanded a downstream callable separately for each reference occurrence and each
caller branch. Its method fixture dispatched nine relation reads. An adapter
reached through several paths repeated the same downstream question.

[REFERENCES and CALLERS](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRetainedRelationRead.kt)
also prepared separate native searches for the same subject and domain. Both use
the same complete reference inventory, then apply distinct shape and compiler
confirmation rules. Existing named-result reuse does not establish reuse between
these different relation meanings.

The [scope compiler](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt)
passes destination restrictions into native search. Provider sites are checked
before candidate capacity and locator detachment. Source inspection does not
support the claim that Kast ignores `SOURCE_DOMAIN`. It also cannot establish how
much internal work IDEA's native search performs before invoking our callback.

An inventory is resumable only after native search finishes and detaches a
complete bounded locator set. Cancellation during preparation cannot produce a
valid remainder. TRACE's ordinary symbol rows wait for terminal grouping, so
`PROGRESSIVE` can return an empty page with pending work. Current strict completion
can expose already grouped proof through qualified recovery evidence. That does
not guarantee proof publication after a hard host cancellation.

Exact-name [discovery](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijExactNameIndexes.kt)
uses indexes and a scoped completeness scan. A known-position lookup has a smaller
boundary. This explains why their work differs; it does not identify the specific
cause of the photographed name-search timeout.

## Implemented changes

TRACE gathers incoming paths before expanding the downstream caller layer. It
uses the existing grouping, checkpoint, budget, and terminal output owners.
The method fixture now dispatches six relation reads and preserves all six
independent occurrence offsets. Native grouping keys retain the complete read
authority and selector fingerprint. Output grouping still uses declaration
identity. Distinct scoped capabilities cannot become one broader read.

REFERENCES and CALLERS run adjacently for a callable. A [single reuse slot](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijReferenceInventoryReuse.kt)
holds their complete, immutable initial inventory within the admitted invocation.
The slot binds authority, exact subject, and requested domain. Each branch
restores locators and confirms its own meaning independently. No PSI, native
search, source text, or K2 session is retained. A new invocation starts empty;
execution continuations retain their existing ordinal state independently.

The slot retains at most one inventory. Its estimate plus 512 bytes must fit
`QUERY_CHECKPOINT_BYTES`; this is additional invocation-local storage alongside
active read/checkpoint state. A failed or interrupted preparation cannot populate
it. A reuse rejection preserves the receiving collector's finite failure and
does not retry native preparation. Host limits, work grants, result limits,
continuation quotas, and lifetimes retain their existing policies.

Diagnostics now distinguish reference, caller-reference, and type-use inventory
timings. Finite counters record native preparation starts, prepared/incomplete/
interrupted outcomes by relation meaning, successful reuse, and reuse rejection.
They record no symbol names, paths, tokens, or source payloads. These signals
permit the next receipt to distinguish repeated preparation from one slow search.

The generated tool description now leads with declaration selection and TRACE.
The generated trace example consumes one previously issued reference, uses an
explicit workspace domain, and preserves strict completion. The tutorial and
agent instructions distinguish execution continuation from retained presentation.

## Validation and remaining qualification

Focused tests exercise the production grouping and inventory rules with scripted
external observations. They prove six relation dispatches, unchanged incoming
occurrence evidence, bounded resumption, scoped-capability separation, reissued
capability equality, and preserved incomplete upstream coverage. Native inventory
tests prove one preparation and two independent confirmation passes for a
REFERENCES/CALLERS pair. They also cover authority/domain changes, separate owners,
single-slot replacement, byte/time rejection, unavailable preparation, cancellation,
and continuation consumption without prefix replay.

Run the focused selectors with the project toolchain:

```sh
mise exec -- ./gradlew :query:service:test --tests '*QueryTrace*' --tests '*QueryCheckpointTraceProofTest' :relation:intellij:test --tests '*RelationInventoryObservationTest' --tests '*ReferenceInventoryReuseTest' --tests '*ReferenceProgressTest'
```

Affected suites and guards:

```sh
mise exec -- ./gradlew :query:contract:test :query:service:test :relation:intellij:test :workspace:intellij-read:test :query:protocol:test :app-server:test --tests '*PublicToolContractTest' --tests '*PublicToolTraceContractTest' --tests '*PublicToolCompletionPolicyTest' --tests '*PublicToolCompletionRecoveryTest' verifyJsonContracts verifyKastArchitecture knowledgeImpact verifyKnowledgeBase :query:contract:spotlessCheck :query:service:spotlessCheck :relation:intellij:spotlessCheck :workspace:intellij-read:spotlessCheck
```

The focused selectors and affected suites passed. JSON contract, architecture,
formatting, and knowledge guards passed. Public examples are schema-validated
and all 23 generated tool projections remain in parity. The callable reference
was regenerated and verified with `:cli:generateMintlifyCallableReference` and
`:cli:verifyMintlifyCallableReference`. Mintlify validation passed using the
repository's documented `npx mint@4.2.841 validate` command.
The shared prose assessment reports `VALE_UNAVAILABLE`; prose metrics
and glossary consistency remain unverified. Knowledge impact identifies the
source-cited concepts for review; existing Claims do not describe TRACE grouping
or per-invocation inventory reuse.

These checks prove orchestration, contracts, and bounded state. They do not prove
latency improvement in the photographed repository or that its trace now finishes
under 30 seconds. That requires an installed candidate, the same exact method and
domain, and fresh host receipts showing the new branch and reuse signals.
