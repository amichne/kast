# KVP-016 detached-model receipt guide

This directory owns the generated KVP-016 detached-model report and its typed exact-head receipt
closure.

- Keep the report a closed generated-serialization document with canonical bytes, authority
  `OPEN_PROJECT`, exact root `/workspace/kast`, bounded model facets, rejected live capabilities,
  and zero forbidden stronger effects.
- KVP-016 does not define or retain a production epoch. KVP-017 solely owns epoch identity and
  freshness policy.
- Execute only the declared `DetachedModelNegativeTest` and `DetachedModelTest` selectors as fixed
  Gradle argument vectors.
- Reconstruct the complete KVP-015 closure and preserve its directly admitted KVP-014 completion.
  Every KVP-016 expectation binds both dependency receipt digests.
- RED binds the negative test, detached model and refinement sources, live adapter, fixtures,
  class and classpath-URL contracts, and module build. GREEN binds the same shared inputs plus the
  positive test and canonical report. The fixed GREEN selector invokes the shared classpath-URL
  contract directly.

Raw JSON, process, source, and receipt bytes stay at Gradle boundaries. Expected report and receipt
failures remain finite typed data until a task renders them.
