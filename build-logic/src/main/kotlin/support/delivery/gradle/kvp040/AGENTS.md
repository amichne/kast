# KVP-040 exact-diff review proof policy

This package owns the graph-derived KVP-040 packet, KVP-039 predecessor admission, exact-head Git
diff observation, structured review evidence, named stale-review rejection, and content receipt.

- Review the merge-base-to-exact-head tracked diff; reject dirty worktrees, missing bases, empty
  diffs, or a head change during review.
- Bind exactly one authority for each of the seven required review areas: diff, generated
  projections, schemas, module edges, forbidden effects, public behavior, and installed evidence.
- Preserve findings as closed structured data. A valid finding remains unresolved until KVP-041;
  KVP-040 must not manufacture a resolved or clean state.
- Write only generated review/proof evidence under `build/reports`; never edit product sources.
