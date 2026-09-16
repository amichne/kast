# Qualified composition and inline-lambda ownership

This change implements the supplied bounded specification. Remote `main` resolved
on September 16, 2026 to `271835de2ce9f012e4585dbde5668b7085fa276c`, matching its
reviewed baseline. Local `main` was one commit behind; the task branch starts at
the remote SHA. Query and traversal production code is unchanged.

## Evidence matrix

| Gate | Status | Evidence |
| --- | --- | --- |
| V1: qualified non-leaf composition | passed | `QueryQualifiedCompositionTest`, `TraversalQualifiedCompositionTest`: recorded downstream reads, both connections/edges, retained terminal incompleteness and depth cutoff. |
| V2: qualified leaf composition | passed | Same classes: empty query result remains qualified; traversal retains depth 1 and records two reads. |
| V3: repeated targets and pagination | passed | Capacity-one pages match generous runs by ordered canonical facts/connections; child positions advance; later and empty terminal pages retain omissions. |
| V4: complete/recoverable controls | passed | Complete controls finish complete after recoverable child pagination; existing module regressions remain green. |
| V5: compiler-backed inline ownership | passed | Native CLI and provider: `firstNotNullOfOrNull`, explicitly supplied ordinary inline argument, nested inline arguments, repeated sites. Baseline omitted all supported sites; changed adapter emits exact original occurrences in both directions. |
| V6: conservative boundaries | passed | Native CLI and provider: returned/stored lambdas, ordinary and homonymous non-inline helpers, noinline, crossinline, unsupported outer boundary, local named function, unresolved and ambiguous mappings. Measured finite omissions remain; PSI tests separately prove lexical ownership. |
| V7: independent omission | passed | `mixedInline` retains its supported exact site and the stored-lambda omission in both native surfaces. |
| V8: ownership pagination | passed | Bounded native capacity-one resume loops preserve the full canonical endpoint/occurrence multiset and authority versus capacity 100, in both directions. |
| Focused module checks | passed | 27 query, 20 traversal and 22 relation tests, no failures or skips; formatting, file-size and static-analysis checks pass. |
| Repository gates | passed | `verifyJsonContracts`, `knowledgeImpact`, `verifyKnowledgeBase`, `productBuildGate` (544 tasks). |
| Native oracle tests | passed | 10 call-oracle tests and 163 existing read-harness tests. These validate harness behavior, not K2. |
| Installed-host two-hop composition | passed | Exact `stdlibInline → inlineTarget → inlineLeaf` query connections and traversal edges, witnessed depth 2, retained depth cutoff and shared authority, through CLI and provider. |
| Real-repository host readiness | failed | Isolated composite import timed out after 600 seconds: `MODEL_PENDING` / `MODEL_INCOMPLETE_PENDING`; semantic entry was `not-entered`. |
| Konditional occurrence and original Kast control | not run | Import readiness prevented these endpoint assertions. This installed-host acceptance gate remains explicitly open. |

The compiler-backed matrix used the existing `prepare_hosted_fixture`,
`prepare_read_fixture`, `NativeProcesses`, `HostedReadTransport` and
`run_inline_ownership_regression` harnesses in private profiles. The intentionally
invalid `ReadUnresolvedCalls.kt` is an opt-in native input supplied with
`unresolved_path`; it is not added to the ordinary compiled fixture or the daily
workspace. Native runs execute identical assertions through CLI and provider.
The final fixture run passed all 34 cases. No daily IDE plugin was replaced.
Merge and patch publication are separately authorized by the subsequent user request.

## Reproduction and artifact identity

Focused checks, from the repository root:

```sh
./gradlew :query:service:check :traversal:service:check :relation:intellij:check
./gradlew verifyJsonContracts knowledgeImpact verifyKnowledgeBase
./gradlew productBuildGate -PhostedIdeaHome='/Users/amichne/Applications/IntelliJ IDEA.app/Contents'
python3 packaging/test-hosted-kotlin-call-regression.py
python3 packaging/test-hosted-read-regression.py
```

The Python read harness requires `jsonschema`; this run used an isolated virtual
environment. The native artifact staging command was:

```sh
./gradlew stageKastControlProduct :runtime:hosted:hostedPlugin \
  :app-server:hostedChangeHarnessJar :change:intellij:nativeFixturePlugin \
  -PhostedIdeaHome='/Users/amichne/Applications/IntelliJ IDEA.app/Contents'
```

Before-change native evidence: source `6c6a5f938590030a28e9f661585b2f3a4934c280`,
plugin `0.42.0-4-g6c6a5f938`, archive SHA-256
`2c4a384ccf37dc0239ea82b51fdb447c73a90406498f092f3c976ff6ad10530d`.
Both surfaces failed the expected inline occurrence assertions and passed the
negative controls. This is K2 failing-before evidence, not a parser failure.

The final staged source is `08268a22585ab2e84cb2467fcc9c845fd4d7326e`.
The private host load log identifies `Kast Existing IDE Runtime
(0.42.0-6-g08268a225)`, IDEA `IU-262.10315.125`, and bundled Kotlin
`262.10315.125-IJ`. Its archive is
`kast-ide-hosted-v0.42.0-6-g08268a225-idea-262.zip`, SHA-256
`c05a09de1609756654966d4493dcdfa588828ae5d2febe180ebe5f61033ed15d`.
The launch selects only the private plugin directory; extracted JAR hashes are
recorded alongside the load log. These are installation/load observations,
separate from the source SHA and CLI version.

Bounded local receipts: `/tmp/kast-inline-red.json`,
`/tmp/kast-inline-green2.json`, `/tmp/kast-inline-final-complete.json`, and
`/tmp/kast-inline-final-native.json`. They retain assertion outcomes, artifact
identities, bounded readiness evidence and observed authority. The checked-in
oracle defines exact endpoints, occurrence ranges, request budgets, pagination,
qualification and progress assertions. Temporary orchestration scripts
`/tmp/kast-inline-native.py` and `/tmp/kast-inline-real-native.py` invoke the
existing harness without changing its installation or protocol behavior.

The failed real-workspace readiness attempt used tracked copies of Konditional
`f191bc264fb18b65d54f228233d7630589fbaf37` and Kast's staged source revision as
included builds. Its private host loaded the identified plugin, but the project
model never became admissible. Existing typed readiness observations recorded
339 `MODEL_PENDING` and 127 `MODEL_INCOMPLETE_PENDING` outcomes. No semantic
acceptance result is claimed for this run; its owned processes were retired.

The pending assertions must rediscover `evaluateTrace`, `evaluateCandidate` and
`executeNext`, compare the current inner occurrence in both directions, then
check the original Kast caller chain. Planned equal grants are 1,000 results,
100,000 work units, 10,000 ms and 2,000,000 bytes, draining continuations before
comparison. Fixture composition evidence does not close this real-repository gate.

See [separate unrelated observations](qualified-inline-unrelated.md).
