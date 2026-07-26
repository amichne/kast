# Repository Intelligence Execution State

- Benchmark corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Active implementation commit: `b28b3a0e89e4c491547ce27e3f90217f3d425997` plus the current Phase 1 worktree
- Current phase: Phase 2 — proof-carrying graph results
- Last passing fast check: canonical `FAST_CHECK` passed for Phase 1 on 2026-07-26
- Last passing full check: canonical `FULL_CHECK` passed for Phase 1 on 2026-07-26 (`./gradlew test`, Rust format, clippy, and all-target tests)
- Last benchmark result: Phase 1 passes 3/42, including all three Phase 1 assertions; later-phase assertions remain RED
- Current blocker, if any: none
- Next concrete action: commit and push the verified Phase 1 boundary, then add the focused Phase 2 relationship-evidence RED
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
