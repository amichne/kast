# Repository Intelligence Execution State

- Benchmark corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Active implementation commit: `5ac947c059f25465721a721e447c38246c6006ac` plus the final evidence worktree
- Current phase: Phase 7 — final verification and superiority report
- Last passing fast check: canonical `FAST_CHECK` passed for Phase 7 on 2026-07-26
- Last passing full check: canonical `FULL_CHECK` passed for Phase 7 on 2026-07-26 (`./gradlew test`, Rust format, clippy, and all-target/all-feature tests)
- Last benchmark result: final Kast result passes 42/42 across all seven categories with deterministic normalized output and zero critical failures
- Current blocker, if any: none
- Next concrete action: commit and push the final evidence boundary
- Known scope exceptions: the frozen commit contains 599 Gradle-compilation-owned `.kt` files and 18 `.kts` scripts; the task's older observation of 1,145 discoverable Kotlin files is not present in this snapshot

## Canonical Commands

- `FAST_CHECK`: `cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_graph_smoke --test repository_intelligence_smoke && python3 scripts/benchmark-native-graph.py --self-test && ./benchmarks/repository-intelligence/run.sh --self-test && python3 benchmarks/repository-intelligence/run_graphify.py --self-test`
- `FULL_CHECK`: `./gradlew test --no-daemon && cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check && cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings && cargo test --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features`
- `BENCHMARK`: `./benchmarks/repository-intelligence/run.sh --assert --repeat 2`
- `BUILD_BRANCH_CLI`: `./gradlew buildDevelopmentCli --no-daemon`

## Phase 0 Evidence

- Branch CLI: `kast 0.16.1-6-g2c630d3d`; installed and branch binaries matched byte-for-byte.
- Kast frozen graph: 599 requested and successful files, zero failures, 25,311 symbols, 40,644 edge occurrences, generation 647.
- Exact regression: four outgoing `CALLS` hops from `semanticGraphOperation` to the one `SemanticGraphSha256.parse(String)` declaration, with one source occurrence per hop.
- Same-snapshot Graphify: commit `2c630d3d`, 13,476 nodes, 27,307 edges, 771 communities, undirected output.
- Archived July 23 Graphify hashes remain unchanged and are diagnostic only.

## Phase 1 Evidence

- `kast rpc` exposes generation-pinned `graph/coverage` and bounded `repository/query` requests through the existing local RPC passthrough.
- Frozen coverage accounts for 1,131 Gradle-associated Kotlin files: 599 compiler-graph indexed, 532 generated Kotlin-DSL accessors explicitly excluded as `GENERATED_SOURCE`, zero failed, and zero stale.
- All 15 compilation/source-set groups and seven Gradle project groups are reported; eligible coverage is complete at generation 647.
- Missing graph rows, stale content hashes, unavailable ownership metadata, module/source-set filters, complete negatives, and qualified negatives have focused executable coverage.
- Persisted Phase 1 result: `benchmarks/repository-intelligence/results/phase1.json`.
- Branch CLI `0.16.1-7-gb28b3a0e` built, installed with its matching IDEA plugin, and reached `READY` on the exact frozen root after a forced IntelliJ restart.

## Phase 2 Evidence

- `repository/query` resolves exact overloaded identities and projects compiler metadata, typed directions, relation filters, occurrence locations, counts, and explicit local-owner derivations from the existing semantic SQLite authority.
- Targeted paths select a deterministic question-relevant route within the requested depth; the permanent four-hop `semanticGraphOperation` to `SemanticGraphSha256.parse(String)` scope-hashing chain passes with one inspectable compiler occurrence per hop.
- Truncated per-edge occurrence samples return an identity-bound cursor. The focused proof retrieves a three-occurrence derived edge as one occurrence followed by the remaining two, with no terminal continuation.
- Every response exposes canonical workspace identity, generation 695, complete coverage, scope, filters, bounds, stable ordering, truncation, and continuation state.
- Two normalized frozen-corpus runs are deterministic and pass all 19 Phase 2 questions. Across the 22 cumulative Phase 1–2 questions, median latency is 239.814 ms, p95 latency is 292.906 ms, median response size is 10,158 bytes, and maximum response size is 226,406 bytes.
- Persisted Phase 2 result: `benchmarks/repository-intelligence/results/phase2.json` (`sha256:5f5e01e8b496031fc14f94561e95ec6eec2a156682241177e2b637011e0e2a3e`).
- Branch CLI and IDEA plugin `0.16.1-8-g4498ff90` were installed from the verified Phase 2 worktree after force-stopping PID 63967; the golden runtime reopened only the frozen root and reached `READY` on PID 77605.

## Phase 3 Evidence

- Natural-language resolve ranks exact compiler identities using the existing semantic tables, trigram FTS, compiler neighbors, declaration metadata, and bounded source text. No embedding dependency or additional persisted index was added; the read-only frozen database remains 70,369,280 bytes.
- Ranked candidates expose scores and field-specific match reasons. Exact `canonicalKey` lookup bypasses lexical ranking, and bare overloaded names return bounded ambiguity without a selected identity.
- All six frozen discovery targets are in the top five at ranks 1, 1, 4, 5, 1, and 1. Both deliberate ambiguity questions pass, and every Phase 1–2 assertion remains green.
- Two normalized frozen-corpus runs are deterministic and pass all 30 Phase 1–3 questions. Median latency is 1,510.277 ms, p95 latency is 2,697.827 ms, median response size is 24,709 bytes, and maximum response size is 226,428 bytes.
- Persisted Phase 3 result: `benchmarks/repository-intelligence/results/phase3.json` (`sha256:24ddcc256864a21d99d6f738a1e61745abe12a9afe54f4c741f05d0e6d5608de`).
- Branch CLI and IDEA plugin `0.16.1-9-g460487ec` were installed from the verified Phase 3 worktree after force-stopping PID 77605; the golden runtime reopened only the frozen root and reached `READY` on PID 93288.

## Phase 4 Evidence

- Six explicit directed projections preserve runtime calls, symbol references, type dependencies, interface and implementation relations, module dependencies, and containment or ownership without creating an undifferentiated graph.
- Deterministic Tarjan SCC and weighted Leiden implementations are reused from Kast's native graph authority. Hubs are direction-specific; boundaries, communities, bridges, and public-API consumers each expose their triggering metric or rule.
- All findings include exact representative symbols and bounded supporting compiler edges. The frozen cross-boundary SCC includes a four-edge directed cycle as its proof subgraph; all returned supporting edges carry occurrences or explicit derivations.
- Two normalized frozen-corpus runs are deterministic and pass all 36 Phase 1–4 questions. Median latency is 1,007.356 ms, p95 latency is 2,771.278 ms, median response size is 25,867 bytes, and maximum response size is 226,524 bytes.
- Persisted Phase 4 result: `benchmarks/repository-intelligence/results/phase4.json` (`sha256:d9f4711a69e0bc3bd8b63f83a176893626c8f8673985de2a8424a7f45a9df5dc`).
- Branch CLI and IDEA plugin `0.16.1-10-g6a873915` were installed from the verified Phase 4 worktree after force-stopping PID 93288; the golden runtime reopened only the frozen root and reached `READY` on PID 9409.

## Phase 5 Evidence

- Markdown and ADR, Gradle, JSON schema, workflow, and Rust sources are scanned in a closed deterministic priority without introducing another graph or persistent index.
- Context relations use the explicit `DOCUMENTS`, `CONFIGURES_MODULE`, `IMPLEMENTS_PROTOCOL`, and `CONSUMES_SCHEMA` vocabulary required by the frozen questions. Each relation carries a source path, source location, exact Kotlin target, direction, and extracted or named derived evidence.
- The prose-only ADR question reuses existing semantic discovery with a 200-declaration candidate bound; path evidence outranks incidental name mentions, and returned nodes are bounded by the selected relations.
- Responses report context node and link counts, exact-link and orphan rates, unresolved and ambiguous reference rates, evidence distribution, a complete relation vocabulary, and deterministic stale-document or public-API documentation-gap findings.
- Two normalized frozen-corpus runs are deterministic and pass all 41 Phase 1–5 questions. Median latency is 657.647 ms, p95 latency is 2,767.154 ms, median response size is 25,147 bytes, and maximum response size is 226,570 bytes.
- Persisted Phase 5 result: `benchmarks/repository-intelligence/results/phase5.json` (`sha256:c2b5464b5279a00d5139f8b09930ea3ce168869201ebda39e62fbe511c75f354`).
- Branch CLI and IDEA plugin `0.16.1-11-g0d65dbc8` were installed from the verified Phase 5 worktree after force-stopping PID 9409; the golden runtime reopened only the frozen root and reached `READY` on PID 29639.

## Phase 6 Evidence

- Every `repository/query` response explicitly identifies the canonical result model. Stable JSON, compact TOON, and human Markdown are direct projections of that same value through existing output infrastructure.
- The focused workflow query is 9,918 bytes as JSON and 8,730 bytes as TOON. Its human projection names both workflow source locations, the exact `semanticGraphOperation` target, graph generation, scope, bounds, ordering, and query plan.
- The benchmark runner derives one architecture and repository-context Markdown report from the same canonical RPC responses; it does not rerun semantic discovery, traversal, metrics, or linking.
- Two normalized frozen-corpus runs are deterministic and pass all 42 questions with every category at 6/6 and zero critical failures. Median latency is 660.995 ms, p95 latency is 2,775.652 ms, median response size is 25,175 bytes, and maximum response size is 226,598 bytes.
- Persisted Phase 6 structured result: `benchmarks/repository-intelligence/results/phase6.json` (`sha256:db37154fbbe41a793f05f3c27ff2a93981d84544e8c680bf4c0523b689a6ca27`).
- Persisted Phase 6 Markdown report: `benchmarks/repository-intelligence/results/phase6.md` (`sha256:efb2dfad92743c4b05a0a35682ec97a79c7c50052c63388421306a09445dc9a0`).
- Branch CLI and IDEA plugin `0.16.1-12-g7e705626` were installed from the verified Phase 6 worktree after force-stopping PID 29639; the golden runtime reopened only the frozen root and reached `READY` on PID 37973.

## Phase 7 Evidence

- The branch was clean before `./gradlew clean buildDevelopmentCli --no-daemon`; the clean final-commit CLI build passed.
- CLI and IDEA plugin `0.16.1-13-g5ac947c0` were installed with verified release digest `2b252c9c52d93c9950b9e75899909124e53b82f91283b815baeb136ea22e1d09` after force-stopping PID 37973. The exact frozen root reached `READY` on PID 39781.
- The complete native rebuild processed 599/599 Kotlin files with zero failures at generation 1582, 25,311 symbols, and 40,644 edge occurrences. Compiler admission reached `READY` in 33,660.661 ms; per-file build total was 21,552.848 ms; the database is 70,627,328 bytes.
- The forced Graphify 0.9.22 rebuild on the same detached corpus produced an undirected graph with 12,936 nodes, 26,314 edges, and 730 communities (`sha256:9ba3af31f2b0419f34b12956f1d25cd77ca4a4946464208bd8287ad465b80efc`).
- Final Kast results pass 42/42, every category is 6/6, all six discovery targets rank first, both deliberate ambiguities are explicit, identity collisions are zero, and normalized results are deterministic.
- Across returned proof, 437 semantic edges and 61 context relations have zero missing occurrences or derivations. Twenty-eight architecture findings cover all six required finding types on relation-specific directed projections.
- Final Kast median latency is 640.018 ms, p95 is 2,739.255 ms, and maximum is 4,437.158 ms. Median response size is 25,074 bytes and maximum is 226,601 bytes.
- Under the shared seven-dimension rubric, Kast scores 240/252 versus Graphify 36/252 on exact Kotlin and 552/588 versus 138/588 overall. Both systems are 6/6 answerable on discovery and architecture.
- Canonical `FAST_CHECK`, `FULL_CHECK`, the clean branch build, the two-run final benchmark, and the exact Green Proof all pass.
- Final result hashes: `final.json` `sha256:1dc6f04c86bf8ad495cd6a59751f7b0fffa07e4890de958d2167bce60de414b0`; `final.md` `sha256:63858fb6fb4ef4c7bf6b2eaffcd2b65c3f7ef9256f48bab7b939e95129d149a8`; `graphify-final.json` `sha256:0228b2bf56b96490b6e9529b06f2ec7ae0aaf79e784a0904d2ad43cea17d2156`.
