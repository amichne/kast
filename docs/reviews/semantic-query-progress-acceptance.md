# Progressive semantic read acceptance

Qualification is in progress. The PR remains draft while final review repairs, the complete product gate, and final installed-host measurements are completed. The claims below record established contracts and the required native checks; they do not yet establish end-to-end completion.

This change starts from fetched `amichne/kast` upstream `6b9d5e0d0d75e4632f8a023e6a9bc6a70212b24f`. The fixture and implementation are evaluated under exact compiler and workspace authority. Source, query, and diagnostic continuation ownership remain in their existing bounded stores.

## Architecture and invariant

Every supported bounded read preserves established semantic facts, detailed coverage, and retained unfinished work. Completion is a proof within a declared universe. Temporary page limits discharge only when producer progress proves that their unfinished input remains usable. Unsupported or indivisible input produces finite typed evidence, rather than an invented successor.

Unranked Kotlin source `ALL_DECLARATIONS` retains lexical source-root/VFS partitions, an active-file offset, constraints, authority, and a monotone input revision. Discovery admits cheap constraints before capacity and exact K2 refinement. A page can retain unopened input and pending candidate refinement. Its observations explicitly identify Kotlin declaration families and the source universe; library-inclusive all-declaration enumeration is a typed unsupported operation.

Relation enumeration collects one bounded authoritative locator inventory from `ReferencesSearch`, `DefinitionsScopedSearch`, or the selected declaration’s call traversal under the original admitted scope. Each successor restores only its remaining ordinal, confirms occurrences with K2, and retains exact inventory and provider identity. Imports and aliases have compiler-confirmed occurrence identity and file-level context; they require no fabricated declaration owner or call edge. An opaque inventory that cannot fit produces an exact finite stop.

Source reads retain snapshot-bound detached structural traversal positions, declaration phases, cumulative qualifications, and proven lookahead. A resumed request restores the next PSI node rather than revisiting every emitted declaration. No PSI element, K2 object, native iterator, or read permit survives the request.

Traversal and query composition retain detailed omission evidence, coverage witnesses, and non-expandable occurrences. Independent traversal payloads become bounded evidence units. Repeated progress fields are shared witnesses, not additive counts. Retained row presentation carries an admitted start/end window so output fitting cannot advance a result cursor past unreturned rows.

Each state owner admits exclusive request claims, hides staged allocations, and atomically commits the accepted fitted page after strict freshness, deadline, and cancellation drainage checks. Published query entries hold immutable replay pages and release superseded producer payloads. Cached successors have transitive authority and ownership checks. Initial reads and composition pin their exact retained inputs through final publication and release. Each token keeps its original creation time. A younger page expires when its oldest advertised dependency expires. Active claims preserve physical storage through drainage and cannot admit expired tokens to fresh requests. Capacity eviction may remove an unreferenced old replay page; retained pages protect their usable successors. Cancellation releases only its own allocations. Retention refusal preserves a useful fitting prefix and original coverage with a typed terminal reason, without advertising unavailable work.

Omission location samples carry a closed complete/truncated proof. The existing three-location cap bounds diagnostics; measured omission counts and semantic facts retain their full meaning. Admission rejects counts inconsistent with the observed sample proof.

Observations include closed phases, durations, outcomes, discovery/refinement/reference counters, and current/peak detached retention accounting for all three owners. These are quota estimates, not heap measurements; source payloads are excluded from instrumentation.

## Proven root causes

1. Upstream non-file `ALL_DECLARATIONS` used eager scoped file/PSI collection before its first projected page. The initial global-name enumeration hypothesis was incorrect for this path. Native receipts show 121/151/600 declarations collected to return 1/5/20. The query interpreter then discarded undiscovered input; rejected first candidates could produce false exhaustion.
2. Reference paging replayed the consumed native search prefix and sorted complete descriptors repeatedly. Declaration-only ownership discarded compiler-confirmed imports and aliases.
3. Traversal projection weakened detailed omissions to flags. Whole traversal metadata could become an indivisible byte-bound payload even when individual proven occurrences fit.
4. Checkpoint use lacked an exclusive publication claim and immutable page cache; the same input token could publish conflicting successor tokens. The baseline native facts were equal on replay, but the checkpoint token changed.
5. Eager output retention preceded final hosted acceptance, leaving attempt ownership vulnerable to rejected publication. Publication failures also collapsed exact causes into generic stale-request evidence.
6. Source ordinal paging revisited the consumed PSI prefix. A stable 121-declaration file with work 32 stopped after 4 declarations; 117 remained inaccessible under the same grant.
7. Retention capacity paths erased proven source/diagnostic facts or skipped unreturned retained rows. Consumed query producer payloads remained retained after publication.
8. Definition and callee producers still used the older prefix-replay collector. The shared provider remainder now carries their immutable inventory and exact advancing cursor; the legacy replay branch is removed.

Integration review of the new state owners exposed two additional gaps: initial retained reads and composition needed to pin their exact inputs before publication ownership could evict them; active claims and younger retained pages needed to preserve every dependency's original logical age while retaining physical storage for drainage. These gaps were identified during this implementation, not independently demonstrated as upstream defects. Focused owner tests cover input pinning, fresh admission, publication expiry, dependency age, and release accounting.

The earlier intermediate diagnostic 40-page failure was an integration regression, not an upstream defect: the equivalent upstream native baseline completes 40/40 diagnostics. The final qualification must retain that already-correct behavior.

## Compatibility

Canonical schema revisions change query2→3, source5→6, and diagnostic4→5. The public callable contract changes3→4. Generated provider catalogs, adapters, schemas, CLI projections, and Mintlify callable reference migrate together. Old eager discovery, native relation-prefix replay, ordinal source replay, and separate hosted output-page registries are removed rather than retained as aliases.

New output evidence includes declared discovery language/universe, independent reference occurrences, full traversal omissions, finite publication causes, and typed retention failure progress. Retained occurrence, traversal, symbol, and binding results are consumable through the canonical read-result action. Omission `samples` changes from a bare array to `{type:"complete"|"truncated",locations:[...]}`; the supported provider catalog removes the superseded V1 variants. Tokens remain authority/epoch-bound and expire or become unavailable under finite retention policy.

## Qualification environment

Owned disposable IDEA build262.10315.125 on macOS26.5.2/Apple M1 Max10cores/32GiB, with a real imported Gradle Kotlin+Java model. No user IDE installation is modified. The independently verified upstream plugin archive SHA-256 is `02ad56ce420243aa4d7b64464138bdd45cb416b14675c46b7b4c935ff63178ff`; all1868 source inputs match fetched upstream.

Comparable suites use the same641-source fixture manifest SHA-256 `37bf4f96870e17bb84cddf6a848bbd755e0bae9acd982df14236dd6fdda14afd`. Oracles are specified independently of product output:121/151functions,600single-file/600broad-file functions, rejected prefixes,87rich references including3file-level imports/aliases,1001dense references, and40compiler diagnostics. Separate declaration-family K2 smoke measurements use a separately identified manifest.

Raw requests, responses, bounded diagnostics, environment and runner manifests remain under `/private/tmp/kast-semantic-native-oals0m41`. Focused fixture tests establish platform traversal and pure contracts; installed-host measurements establish K2, lifecycle, and end-to-end behavior.

## Demonstrated limits and unrelated findings

Opaque reference inventory and VFS directory materialization can terminate when an indivisible partition cannot fit; no smaller native partition is claimed. The declared all-declaration universe covers supported Kotlin source families, not all languages or library declarations. Diagnostic file-index callbacks still consume replayed prefix work; compiler analysis and output pages remain retained, with explicit finite enumeration progress and increase-grant outcomes. Detached state accounting is conservative rather than a heap bound. Wall-clock observations include IDE scheduling and index warmth and do not establish a universal speedup.

An unrelated native `WORKSPACE_REFRESH` Gradle model reload invokes document save off the EDT and can return platform-unavailable. Its exception/JFR evidence is recorded separately; this semantic-read change does not repair that workflow.
