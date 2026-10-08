# Enterprise cache feedback: local validation record

Recorded on 2026-10-08 for the working-tree implementation based on
`0e69f48053328941bd94ecf8a3ea483fff79d4fb` (`origin/main` at task refresh).
The full build and the disposable live-IDE litmus checks below passed their stated
assertions. The candidate ran in a private IntelliJ profile; the daily installation
was not replaced. This record does not establish enterprise latency.

## Behavior proved locally

| Case | Production change | Executable proof |
| --- | --- | --- |
| Common trace | Terminal `TRACE` selects seeds, implementations/overrides, direct interface members, callers and one further caller layer. Grouped symbols retain occurrence evidence and scope qualifications. | `QueryTraceTest`, `QueryTraceMembersTest`, `DirectInterfaceMemberSourcePsiTest`, `PublicToolTraceContractTest`. Engine tests use explicit semantic observations; the member test uses physical Kotlin PSI. |
| Exact-name discovery | Authoritative indexed candidates are detached before the scoped completeness scan. A stopped fallback preserves candidates and incomplete coverage. | `ExactIndexedDiscoveryPhasesTest` proves indexed-phase ordering and candidate preservation under controlled observations. It does not measure enterprise search time. |
| Incomplete-query recovery | Strict rejection presents bounded proven facts, the original question/coverage and a copyable retained read. Pending TRACE groups remain recoverable without replay or inflated execution counts. | `CompleteOnlyQueryTest`, `CompleteOnlyCheckpointRecoveryTest`, `QueryTraceGroupedRecoveryTest`, `CompletionRecoverySchemaTest` and `PublicToolCompletionRecoveryTest`. These cover complete-envelope fitting, retained reads, six output families and progressive/strict separation. |
| Vocabulary and location | `CLASS` is the accepted interface family; `INTERFACE` remains invalid. An offset inside generic parameters selects the containing supported declaration. | Canonical schema negative cases, `IntellijContainingDeclarationPsiTest`, and the independent UTF-16 fixture oracle. Physical PSI establishes containment; it does not prove live K2 resolution. |
| Reference freshness | Compact responses preserve workspace/host/epoch/content-view/version authority. Reacquisition records finite unavailable/stale-authority reasons. | `ReacquiringQueryReferencesTest`, `CompactToolPresentationTest`, `ReadAcquisitionSchemaTest`. Unchanged signature/location cannot establish historical handle reuse. |

Callback proof storage retains its existing bound. `CallbackFlowRetentionObservationTest`
proves actual allowance, admitted accounting, required accounting, result-limit
precedence and coherent byte-rejection snapshots. `HostedCallbackProofDiagnosticsTest`
proves encoded diagnostic receipts. Accounting estimates detached proof storage;
it does not measure heap use or encoded response bytes. Numeric storage accounting
is available in `kast_semantic_read`, not the public query result. Presentation
paging and strict recovery preserve useful facts without claiming complete execution.

## Build repairs and executed checks

The follow-up validation used an independent checkout under
`/private/tmp/kast-litmus-czkf4t1h`, with private Gradle caches, temporary files,
external source checkouts and IDE state. All tests in this follow-up ran there.
The validated source fixes were copied back only after all 77 destination
preimages matched; byte comparison then confirmed all 186 changed source paths
matched the tested snapshot.

App Server test cleanup resolved the previous 206 lint findings and 31
compiler-analysis errors. The latter were 27 generated serializer-member calls
and four cascading inference errors; typed reified serializer calls preserve the
same DTOs and independent encoded-shape assertions. Source review verified the
same 343 test names and 1,464 JUnit assertion calls across the 76 cleanup paths.
No suppression, quality baseline, disabled check or relaxed assertion was added.

The full build exposed one further defect: `:distribution:cli:sourcesJar` consumed
`generateManagementVersion` output without a producer dependency. The shared
source collection now carries `builtBy(generateVersion)`. A standalone archive
build scheduled the generator and contained the exact generated version source
once at its expected path.

| Executed selector | Terminal evidence |
| --- | --- |
| `build knowledgeImpact verifyKnowledgeBase --continue` | Success; 595 tasks, 100 executed and 495 up-to-date. Across the ordinary repository suites: 3,552 tests, zero failures/errors, three installed-Codex opt-in skips. |
| `:app-server:detektTest :app-server:check --continue` | Success; zero ordinary/type-resolved findings and zero compiler-analysis errors. 615 tests, zero failures/errors, three installed-Codex skips. These tests are included in the repository total above. |
| `:distribution:cli:sourcesJar` | Success; generated archive entry and source bytes independently inspected. |
| `:distribution:cli:test :distribution:cli:nativeCompile` | Success; GraalVM 25 native executable built with a 3 GiB heap and two build threads. |
| `verifyKastArchitecture` | Success; 74 tasks, including three executed tasks. |
| `-p build-logic check` | Success; 112 ordinary tests and 18 JSON parser/scanner tests, zero failures/errors/skips. |
| CLI socket/deadline `nativeTest` | Four tests passed; this proves the owned OS adapters. |
| Representative project `compileKotlin compileTestKotlin` | Success with Kotlin 2.4.20, Gradle 9.7.1, JDK 21, Spring Data Commons 4.0.1 and kotlinx.coroutines 1.11.0. |

The successful full-build command was:

```sh
run=/private/tmp/kast-litmus-czkf4t1h
GRADLE_USER_HOME="$run/gradle" TMPDIR="$run/t" mise exec -- \
  "$run/kast/gradlew" -p "$run/kast" \
  build knowledgeImpact verifyKnowledgeBase --continue \
  --init-script "$run/test-isolation.init.gradle" \
  --no-configuration-cache --max-workers=2 \
  -Dkotlin.daemon.runFilesPath="$run/t/kotlin-daemon"
```

The temporary init script assigned every `Test` task an owned `java.io.tmpdir`
and `TMPDIR`. The type-resolved analysis also used a temporary init script enabling
Detekt debug output, so the absence of analysis errors was checked directly.
JSON, architecture, generated-contract and knowledge guards passed. The root
host-observation suite passed 216 Python tests. Knowledge impact identified
source-bound concepts for the scheduled wiki refresh; generated wiki pages were
not hand-edited.

## Live compiler litmus

A private IntelliJ IDEA 262.10968.63 process imported and indexed an 11-file
Kotlin project. Six actual loaded class hashes matched unique entries in the
current candidate archive. The checks used the candidate public `query_symbols`
RPC, validated request/result schemas, newly issued references and independent
source expectations.

The RPC candidate came from `:cli:installDist`. Its required checked-in
`one-shot-observation-v1` marker was staged into its private `share/kast` directory,
and `KAST_INSTALL_IDEA_HOME` selected the actual IDE. This is candidate composition
evidence; it is not full managed-installer qualification.

| Pinned upstream source | Selected shapes |
| --- | --- |
| [Ktor Attributes at f76c50da](https://github.com/ktorio/ktor/blob/f76c50da18f6b284ce4ae7b4e84bed4c9aea5ce0/ktor-utils/common/src/io/ktor/util/Attributes.kt) | Generic interface/default methods, two adapted implementations, an authored inline/reified/noinline adapter, ordinary/inferred/test consumers. |
| [Spring Kotlin guide at a599cbd4](https://github.com/spring-guides/tut-spring-boot-kotlin/blob/a599cbd44f5ec108c402e9967309ead7008b2212/src/main/kotlin/com/example/blog/Repositories.kt) | Repository interfaces with real Spring Data inheritance, ordinary/inferred/test callers and a zero-source-implementation control. |
| [kotlinx.coroutines at bd2e9a1b](https://github.com/Kotlin/kotlinx.coroutines/blob/bd2e9a1b90400fb7b2fa4f8731e7d8148799ab73/kotlinx-coroutines-core/common/src/flow/terminal/Collect.kt) | Suspend functional interface, anonymous callback owners, inline/crossinline/reified adapters and noinline forwarding into a real library dependency. |

These were selected source slices with package relocation and explicitly authored
support/consumers. They do not establish completeness for full upstream projects.
The preparation recorded 37 upstream anchors. Oracle revision 2 corrected one
source-derived omission: the copied `Attributes.take` body itself calls `get`.
No fixture source was changed to make a query result match.

| Exact callable | CODE occurrences | IMPORT occurrences | REFERENCES / CALLERS |
| --- | ---: | ---: | --- |
| `Attributes.get` | 4 | 0 | Complete; includes the default `take` method. |
| `getWithAttributes` | 3 | 2 | Complete; ordinary, inferred and test consumers. |
| `ArticleRepository.findBySlug` | 3 | 0 | Complete. |
| `UserRepository.findByLogin` | 1 | 0 | Complete; test consumer. |
| `collectIndexed` | 3 | 2 | Complete; ordinary, inferred and test consumers. |
| `observeLatest` | 1 | 0 | Complete. |
| `filterIsInstance` | 2 | 0 | Complete; production and test consumers. |

All 21 occurrence rows matched exact file, UTF-16 range, CODE/IMPORT context,
declaration owner and target identity, with `k2-authored-source` and
`exact-compiler-confirmed` evidence. All 11 source hashes matched the oracle.
Seven `READ_RESULT` cursor-1 reads preserved the exact remaining rows and coverage.
Four generic-list, method-name and implementation-body `AT_LOCATION` probes
returned the expected containing declarations. `INTERFACE` kind and object-shaped
`RETAIN` inputs correctly rejected with `INVALID_ARGUMENTS`.

Spring interface `TRACE` completed with 12 symbols, including both declared
methods and their inferred/test consumers. Implementation enumeration completed
with zero source implementations; it did not invent runtime proxies. Ktor
implementation enumeration completed with exactly the two authored implementations.

Ktor `TRACE` retained 29 qualified rows, including all ten direct interface
members. Its enumeration completed, but strict callback proof rejected with
`CALLBACK_GRAPH_UNPROVEN` / `OUTSIDE_DOMAIN` at standard-library `also` callbacks.
Coroutine `TRACE` retained 20 qualified rows and explicit `UNSUPPORTED_ITEM`
omissions for anonymous implementation/override/caller identities. Recovery
preserved these proof gaps and `exhaustive: false`. Neither case is a complete
call-graph claim. A depth-one `collectIndexed` CALLEES walk completed the proven
`Flow.collect` edge under the photographed 15-second/100-result grant.

The [remaining TRACE work plan](TRACE_COMPLETENESS_PLAN.md) defines separate
acceptance gates for these callback and anonymous-identity gaps. Its proposed
checks are not additional results of this validation run.

## Native freshness and byte accounting

A controlled appended comment and real VFS refresh changed saved-PSI epoch 1 to 2
within the same host. Reusing the old exact reference returned the same compiler
signature and location under the current basis, with explicit previous/current
references and `REFERENCE_UNAVAILABLE` reacquisition evidence. Reusing its old
retained result rejected with `execution-rejected` / `result-unavailable` and
`restart_read`. A fresh read after restoring the exact file preimage completed at
epoch 3. All 11 fixture hashes were restored.

The initial test assumption that every old reference must reject was too strong:
the production contract permits proven reacquisition. The corrected assertion
checks preserved identity, current authority and explicit reacquisition evidence;
it does not silently treat the old lease as current.

Actual native callback diagnostics recorded an allowance of 524,288 bytes,
required and retained estimates of 3,162 bytes, and two admitted retention events.
These are conservative proof-storage accounting, not encoded response bytes or
measured heap. Native `BYTE_LIMIT_REACHED` was not reproduced; controlled boundary
tests remain the evidence for exhaustion and diagnostic consistency.

## Evidence identity and limits

The candidate archive SHA-256 was
`d0002fc528ab6bf25b71d0872d697479b161693228e8946b66f38ec24c3e009d`.
The validated changed-file snapshot before this report update had SHA-256
`b42be3f4f3b3484bbc687d9b166119a0fa4d84cc5b598bd2577e7d311439c97e`
over sorted path/NUL/content/NUL records. Oracle revision 2 had SHA-256
`4cb8f81491aa625432d16cd786c286d997c80d9b6ad1541fe3c392368926b47f`.

Enterprise two-second latency, scale/concurrent-load behavior, runtime callback
activation, Spring proxy execution and full upstream repository completeness
remain unverified. This litmus supports the selected static semantic shapes,
qualified recovery and freshness behavior.

## Cleanup

The private IDE processes and their JCEF/Kotlin children were verified stopped.
The private Gradle daemons were stopped, and a final process inspection found no
process referencing the test root and no survivor in any of the seven recorded
IDE process groups. The complete `/private/tmp/kast-litmus-czkf4t1h` directory and
its `/tmp/kast-active-litmus-root.txt` pointer were deleted; absence was verified.
The source fixes and this concise record are the intended retained outputs.
