# Repository Intelligence Execution State

- Benchmark corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Active implementation commit: Phase 0 baseline commit pending at current branch `HEAD`
- Current phase: Phase 1 — Kotlin coverage as a correctness contract
- Last passing fast check: canonical `FAST_CHECK` passed on 2026-07-26
- Last passing full check: canonical `FULL_CHECK` passed on 2026-07-26 (`./gradlew test`, Rust format, clippy, and all-target tests)
- Last benchmark result: expected RED, 0/42 Kast questions because `kast rpc` is absent; same-snapshot Graphify raw answers captured for 42/42 questions
- Current blocker, if any: none
- Next concrete action: add the focused `kast rpc` and `graph.coverage` RED, then expose the existing exact-root inventory and source-index coverage authorities through the canonical machine surface
- Known scope exceptions: the frozen commit contains 599 Gradle-compilation-owned `.kt` files and 18 `.kts` scripts; the task's older observation of 1,145 discoverable Kotlin files is not present in this snapshot

## Canonical Commands

- `FAST_CHECK`: `cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_graph_smoke && python3 scripts/benchmark-native-graph.py --self-test && ./benchmarks/repository-intelligence/run.sh --self-test && python3 benchmarks/repository-intelligence/run_graphify.py --self-test`
- `FULL_CHECK`: `./gradlew test --no-daemon && cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check && cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings && cargo test --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features`
- `BENCHMARK`: `./benchmarks/repository-intelligence/run.sh --assert --repeat 2`
- `BUILD_BRANCH_CLI`: `./gradlew buildDevelopmentCli --no-daemon`

## Phase 0 Evidence

- Branch CLI: `kast 0.16.1-6-g2c630d3d`; installed and branch binaries matched byte-for-byte.
- Kast frozen graph: 599 requested and successful files, zero failures, 25,311 symbols, 40,644 edge occurrences, generation 647.
- Exact regression: four outgoing `CALLS` hops from `semanticGraphOperation` to the one `SemanticGraphSha256.parse(String)` declaration, with one source occurrence per hop.
- Same-snapshot Graphify: commit `2c630d3d`, 13,476 nodes, 27,307 edges, 771 communities, undirected output.
- Archived July 23 Graphify hashes remain unchanged and are diagnostic only.
