# Baselines

This directory stores normalized Kast and Graphify results for the immutable
commit in `../manifest.json`. The July 23 Graphify archive is diagnostic input
only; it cannot establish same-snapshot superiority.

- `kast-initial.json`: the expected 0/42 RED before the public `kast rpc`
  surface exists.
- `native-graph/`: frozen-corpus graph build, traversal, latency, response-size,
  and generation evidence. The 10 MB symbol topology/community payloads are
  omitted; their counts, timing, and artifact paths remain in the summary.
- `graphify-initial.json`: raw Graphify 0.9.22 responses to all 42 shared
  questions plus the automated pre-change Kast four-hop `CALLS` regression.
