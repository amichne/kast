# Composable tooling reproduction

This change is reproduced with authored synthetic declarations, Gradle module
models and deterministic relation graphs. No external repository, session
transcript, proprietary source or private session link is part of the fixtures.

| Boundary | Regression reproduced | Evidence after correction |
| --- | --- | --- |
| Scoped lexical discovery | Unrelated declarations consumed candidate capacity before a scoped typo match could reach compiler projection. | `ScopedFuzzyScaleTest` models 200 Gradle modules and 10,005 declarations. Cheap eligibility and bounded ranking retain the intended main-source declaration; the semantic projection port receives that eligible candidate. |
| Exact identity | Discovery scope changed equality, and transport repeated long selectors. | `SymbolSelectorContractTest` and `HostedReferenceStoreTest` retain canonical equality across scopes, 31-character exact handles, repeat lookup, scope restrictions and stale/collision rejection behavior. |
| Ordered query | An intermediate result exhausted final-output bytes; a partial expansion lost unfinished stages. | `QueryPaginationTest` resumes stage tasks and relation cursors with stable distinct history; byte-limited pages drain in order without replaying the prefix. |
| Serialized query output | The encoded page retained only a prefix with no accessible suffix. | `HostedQueryResponseTest` drains the actual encoded suffix under the byte ceiling and reports indivisible oversized output explicitly. |
| Multi-hop traversal | A fast relation read was charged its entire granted time allowance. | `TraversalServiceTest` follows cheap multi-depth chains, proves cumulative checkpoint progress over renewed per-request budgets, and rejects changed depth/strategy. |
| Large fan-out | Breadth-first expansion could spend successive pages at one root. | Bounded fan-out reaches deeper fixture nodes and returns explicit partial-node evidence for unexamined neighbors. |
| Provider coverage | Earlier unsupported inputs disappeared from final-page completeness. | `RelationRetainedCoverageTest` preserves the gap through final resume; omission fixtures distinguish observed page counts from unmeasured inherited coverage. |
| Tool presentation | A summary plus JSON required ad hoc parsing; early broker failures were plain strings. | Provider and terminal-reply tests decode one JSON document, preserving rejection codes and cancellation uncertainty. Native display schema tests admit structured content only on the supported display boundary. |
| Readiness | A failed model observation could be confused with permanent semantic failure. | `HostedReadinessTest` observes unavailable then admission-ready state from fresh model evidence without creating semantic read authority during the poll. |
| Provider startup | Expanded schemas plus JVM startup stderr exceeded the shared qualification output bound. | `InstalledServerProjectionTest` reserves 4 KiB of headroom. Reused definitions preserve all 13 expanded output schemas, and native qualification is explicitly admitted. |

## Reproduce the focused checks

```shell
./gradlew :symbol:intellij:test :symbol:contract:test \
  :query:service:test :query:protocol:test :traversal:service:test \
  :relation:intellij:test :protocol:wire:test :runtime:hosted:test \
  :app-server:test :cli:test :workspace:intellij-read:test
./gradlew productBuildGate
```

The deadline regression sweeps 72 admission boundaries. Separate traversal
overrun cases retain an observed six milliseconds under a five-millisecond grant,
stop further effects, and preserve resumable or terminal time qualification.
The output-store regression charges both the retained request and output.

These deterministic fixtures prove ordering, capacity isolation, identity,
continuation and encoded-contract behavior. A synthetic imported-model port is
not a benchmark of a live 200-module IDE import. The readiness seam establishes
admission state; it does not claim compiler/index readiness or prove desktop
rendering.

Clean native acceptance at `ebca5b094890d0194c50693d82d6d6f28441eedf` used
immutable copied artifacts and an isolated IDEA `262.10315.125` environment.
All 116 read assertions passed across CLI and provider surfaces, with synthetic
sources unchanged during reads. All 30 change, approval and recovery cases
passed afterward. Both report paths retained admitted provider qualification;
the run qualified and removed its successful fixture after retiring owned
processes. The controller was scripted against locally generated Codex schemas.
This does not qualify stock Codex UI rendering or a live 200-module import.

## Contract and tuning changes

- Exact items add snapshot-local `symbol_id`; capabilities remain opaque
  `symbol_ref` tokens that retain original scope and freshness requirements.
- `query_symbols` accepts optional `continuation`; qualified pages include the
  next token or a finite terminal reason. Distinct retains the first occurrence
  and its connections, including across page boundaries.
- Traversal results add cumulative progress, selected strategy and page-local
  partial expansions. Tokens bind depth and strategy; old traversal tokens that
  lack these witnesses must be restarted. Tool requests explicitly include
  `strategy`; the direct CLI decoder retains its breadth-first default.
- Relation results add exact-fact soundness and bounded provider omission
  evidence. Counts describe observations on one page, never an estimated total.
- Discovery file enumeration and project continuation retention have declared
  settings in the generated installation catalogue. See
  [read configuration](../hosted-read-configuration.md).

All checkpoints fail closed on stale state, mismatch, expiry or capacity loss.
The implementation supports bounded work in large workspaces; these tests do not
establish exhaustive coverage or fixed latency for arbitrarily large inputs.

## Integrated validation

The local `productBuildGate` and knowledge checks passed all 545 tasks after the changes, including
Kotlin compilation, focused and integration tests, formatting, structural limits,
architecture, generated public/configuration schemas, JSON contracts, knowledge
validation and installation acceptance. The host-observation suite used its
pinned test dependencies in an isolated Python environment.
