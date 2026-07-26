# Kast Repository Intelligence Final Report

Status: `surpasses Graphify on the frozen benchmark`

## Fixed identities

- Frozen corpus: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Implementation: `5ac947c059f25465721a721e447c38246c6006ac`
- Installed CLI and IDEA backend: `0.16.1-13-g5ac947c0`
- Kast graph generation: 1582
- Graphify: `graphify 0.9.22`, rebuilt from the same detached corpus

Both corpus worktrees were clean at the fixed SHA. The archived July 23
Graphify graph remained read-only at
`sha256:1d7c61b5aa1dfc2d8058bb424268fc50eb7eaa335c231bb8b8054b5807912470`
and is not used for the superiority claim.

## Benchmark result

Kast passes all 42 independently recorded questions, all hard assertions, and
all critical-failure gates. The two normalized runs are semantically
identical.

| Category | Kast assertions | Kast rubric | Graphify rubric |
|---|---:|---:|---:|
| Architecture | 6/6 | 84/84 | 36/84 |
| Repository context | 6/6 | 84/84 | 30/84 |
| Directional path | 6/6 | 84/84 | 12/84 |
| Discovery | 6/6 | 72/84 | 30/84 |
| Exact identity | 6/6 | 72/84 | 12/84 |
| Impact | 6/6 | 84/84 | 12/84 |
| Negative and ambiguity | 6/6 | 72/84 | 6/84 |
| **Overall** | **42/42** | **552/588** | **138/588** |

The exact-Kotlin aggregate—exact identity, directional path, and impact—is
240/252 for Kast and 36/252 for Graphify. Kast and Graphify are both 6/6
answerable on the selected discovery questions and 6/6 on architecture, so
Kast matches the required answerability gate while scoring strictly higher on
evidence quality.

The rubric scores all seven published dimensions for every question.
Architectural usefulness is zero for both systems when a discovery,
exact-identity, or negative question does not request an architectural result.
Graphify receives partial credit for relevant nodes, source locations, visible
depth, and truncation. Its rebuilt graph is undirected, and its query output
does not expose overload-safe selection, typed directed paths, relationship
occurrences, coverage-qualified negatives, explicit ambiguity, or
relation-specific findings. Those operations are recorded as unsupported
rather than silently treated as Kast wins.

## Coverage, identity, and evidence

- Coverage accounts for 1,131 Gradle-associated Kotlin files: 599 eligible
  compiler files indexed, 532 generated Kotlin-DSL accessors explicitly
  excluded as `GENERATED_SOURCE`, zero failed, zero stale, and zero pending
  updates.
- The clean complete rebuild produced 25,311 exact symbols and 40,644 compiler
  edge occurrences. Every one of the 599 requested files succeeded.
- All responses report generation, scope, coverage, filters, bounds, ordering,
  truncation, and continuation state. Identity collisions are zero.
- The final result contains 437 returned semantic edges and 61 context
  relations. Zero lack a source occurrence or explicit derivation.
- The permanent four-hop outgoing `CALLS` path from
  `semanticGraphOperation` to the exact
  `SemanticGraphSha256.parse(String)` declaration passes with one compiler
  occurrence per hop.

## Discovery and ambiguity

All six natural-language discovery targets rank first, giving 100 percent
top-five recall. Candidates retain exact canonical keys, compiler identity,
field-specific match reasons, and stable rank.

Both deliberate ambiguity cases are surfaced: bare `parse` returns 10 bounded
candidates and bare `sha256` returns seven. Complete negatives, module-scoped
negatives, the deliberately incomplete qualified negative, and the
wrong-direction empty result all pass without overstating scope.

## Architecture and repository context

Twenty-eight deterministic findings cover high-centrality internal
implementations, cross-boundary cycles, module boundaries, communities, thin
bridges, and public APIs consumed by unrelated components. They use explicit
runtime-call, symbol-reference, and type-dependency projections, exact
representative symbols, triggering metrics, and bounded evidence subgraphs.

Markdown and ADR, Gradle, schema, workflow, and Rust sources are scanned in the
required order without a second persistent graph. The six context questions
produce exact `DOCUMENTS`, `CONFIGURES_MODULE`, `IMPLEMENTS_PROTOCOL`, and
`CONSUMES_SCHEMA` links with extracted or named derived evidence. Across those
question-specific scopes, exact-link rates range from 0.50 to 60.71 percent
and orphan rates from 39.29 to 99.50 percent; unresolved and ambiguous selected
reference rates are zero. The high orphan rate is visible because the
denominator includes every scanned source in the selected source kinds, not
only files that mention the requested identity.

Stable JSON, compact TOON, human Markdown, and the committed Markdown benchmark
report are projections of one canonical `repository/query` result. They do not
rerun discovery, traversal, metrics, or context linking.

## Performance and rebuild cost

- Final query latency: median 640.018 ms, p95 2,739.255 ms, maximum 4,437.158 ms.
- Response size: median 25,074 bytes, maximum 226,601 bytes.
- Source-index database: 70,627,328 bytes.
- Exact-root compiler admission to `READY`: 33,660.661 ms.
- Complete 599-file semantic rebuild: 21,552.848 ms total, 19.901 ms median,
  84.523 ms p95, and 684.997 ms maximum per file.
- Complete native benchmark wall time: 58,391.831 ms.
- Final Graphify rebuild: 12,936 nodes, 26,314 edges, 730 communities, and an
  undirected result.

## Verification

All required commands pass:

```shell
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test agent_graph_smoke --test repository_intelligence_smoke \
  && python3 scripts/benchmark-native-graph.py --self-test \
  && ./benchmarks/repository-intelligence/run.sh --self-test \
  && python3 benchmarks/repository-intelligence/run_graphify.py --self-test

./gradlew test --no-daemon \
  && cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check \
  && cargo clippy --manifest-path cli-rs/Cargo.toml --locked \
    --all-targets --all-features -- -D warnings \
  && cargo test --manifest-path cli-rs/Cargo.toml --locked \
    --all-targets --all-features

./gradlew clean buildDevelopmentCli --no-daemon

python3 scripts/benchmark-native-graph.py \
  /Users/amichne/code/kast-repository-intelligence-corpus \
  --kast /Users/amichne/.local/share/kast/current/bin/kast \
  --database /Users/amichne/.local/share/kast/state/data/workspaces/git/github.com/amichne/kast/worktrees/kast-repository-intelligence-corpus--8add60ac3132/cache/source-index.db \
  --iterations 1 \
  --output-root benchmarks/repository-intelligence/results/native-graph-final

./benchmarks/repository-intelligence/run.sh --assert --repeat 2 \
  --output benchmarks/repository-intelligence/results/final.json

python3 benchmarks/repository-intelligence/run_graphify.py \
  --graph /Users/amichne/code/kast-repository-intelligence-graphify-corpus/graphify-out/graph.json \
  --database /Users/amichne/.local/share/kast/state/data/workspaces/git/github.com/amichne/kast/worktrees/kast-repository-intelligence-corpus--8add60ac3132/cache/source-index.db \
  --output benchmarks/repository-intelligence/results/graphify-final.json \
  --assert-kast-regression

./gradlew check \
  && cargo test --manifest-path cli-rs/Cargo.toml \
  && ./benchmarks/repository-intelligence/run.sh --assert --repeat 2
```

There are no remaining failed assertions or external blockers. The
authoritative evidence is:

- `benchmarks/repository-intelligence/results/final.json`
- `benchmarks/repository-intelligence/results/final.md`
- `benchmarks/repository-intelligence/results/graphify-final.json`
- `benchmarks/repository-intelligence/results/comparison.json`
- `benchmarks/repository-intelligence/results/native-graph-final/`
