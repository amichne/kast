# Repository Intelligence Execution State

- Benchmark corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Active implementation commit: `6a873915c552ade53115a7f26bccbba31b4ab073` plus the current Phase 4 worktree
- Current phase: Phase 5 — repository context
- Last passing fast check: canonical `FAST_CHECK` passed for Phase 4 on 2026-07-26
- Last passing full check: canonical `FULL_CHECK` passed for Phase 4 on 2026-07-26 (`./gradlew test`, Rust format, clippy, and all-target tests)
- Last benchmark result: Phase 4 passes 36/42 cumulatively, including every Phase 1 through Phase 4 assertion; the six Phase 5 context assertions remain RED
- Current blocker, if any: none
- Next concrete action: commit and push the verified Phase 4 boundary, then add the focused Phase 5 context RED
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
