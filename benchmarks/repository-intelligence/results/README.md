# Repository Intelligence Results

The current admitted comparison proves Kast superiority on the frozen
repository benchmark.

- Corpus commit: `2c630d3d156574eb4548fd97df3bd61fe9deb1a6`
- Kast source commit: `f61514693b3cfb6558c8aeca61e77e7023429447`
- Kast graph generation: `1582`
- Benchmark result: 42/42 questions, deterministic output, zero critical
  failures
- Provenance admission: passed for both captured systems

| Score | Kast | Graphify | Maximum |
|---|---:|---:|---:|
| Exact Kotlin | 216 | 44 | 252 |
| Overall | 519 | 86 | 588 |

The performance comparison is eligible across all 42 questions. Kast records
15,783.057 ms total latency versus 35,310.500 ms for Graphify and wins the
declared total-latency comparison.

## Evidence authority

- `final.json` is the authoritative two-run Kast capture.
- `final.md` is its canonical Markdown projection.
- `graphify-final.json` is the same-snapshot comparison capture.
- `comparison.json` admits provenance and applies the shared rubric.
- `native-graph-final/` contains the clean compiler-backed graph rebuild
  evidence.

The comparison preserves exact identities, typed directional relationships,
source occurrences or explicit derivations, coverage-qualified negatives, and
explicit ambiguity. `latest.json` remains an uncommitted convenience output of
the default runner.
