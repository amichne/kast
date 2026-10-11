# Exact classlike AST runtime qualification

Exact-name source discovery can reduce explicit PSI requests while preserving full AST traversal. It requires a trusted bundled parser and platform runtime whose relevant factory and AST semantics are not modified at runtime. Archive hashes, defining-loader origins and fixed-factory probes provide compatibility evidence under this precondition. They do not attest arbitrary transformed in-memory definitions or prove the absence of later agent attachment.

The qualifier conservatively rejects a positively observed startup `-javaagent:` before enumerating any class origins or hashing archive entries. The check precedes every supported loader path. The retained binding reason selects the existing PSI visitor and emits `DECLARATION_AST_STARTUP_AGENT_FALLBACKS` once per eligible source file, including subsequent reads using cached metadata. `DECLARATION_AST_LEGACY_FALLBACKS` also records that fallback. Neither counter asserts that an agent actually changed semantic behavior. An absent counter is unmeasured; an observed zero is not proof that no later instrumentation exists.

An exact audited parser variant, audited IDEA defining-loader implementations, exact bootstrap-defined JDK application loader, bounded class fingerprints and fixed PSI mappings remain necessary. The base parser and final IDE K2 parser have separate qualification caches. The [K2 compatibility audit](classlike-ast-k2-parser-qualification.md) records the additional exact class and fingerprint. Arbitrary subclasses remain unsupported. Unknown loaders, active PathClassLoader transformers, multi-release archives and unsupported factories use the original visitor. Error, lazy and unproven AST nodes retain per-node PSI classification. No JVM attach/security setting is changed, and the product does not install or attach instrumentation.

`DECLARATION_AST_PARSER_FALLBACKS` records an unaudited active parser once per eligible file. `DECLARATION_AST_BINDING` times the complete binding boundary, including parser admission, cache lookup and first-use source/factory qualification. It excludes subsequent source traversal, but remains nested inside `DECLARATION_PSI_SCAN`. A warm cache lookup is still a binding call. The existing byte and mapping counters identify which calls perform qualification work.

An owned startup-agent diagnostic changes the actual bundled parser's script-suffix field while retaining its original archive hash. It demonstrates why the compatibility evidence cannot be called unconditional loaded-definition attestation. A second owned witness shows that JVM instrumentation can also change a class defined by an audited IDEA URL loader; loader restriction alone does not resolve that boundary. These are source-binding demonstrations, not reproduced discovery omissions.

`ClasslikeAstStartupAgentTest` launches only disposable JDK children with an owned no-op agent and a normal SDK control. It runs actual binding and dispatcher rules, verifies repeated fallback diagnostics, compares the entire finite outcome with the old visitor, and proves zero AST/hash/mapping work in the agent path. The normal child retains supported AST traversal. Existing parser differential, cold traversal, cancellation/retry and multi-release fixtures remain required.

```sh
./gradlew :symbol:intellij:test --tests '*ClasslikeAst*Test'
./gradlew :symbol:intellij:check :workspace:intellij-read:check verifyJsonContracts knowledgeImpact verifyKnowledgeBase
```

The dense 6,025-to-four result measures newly cached source PSI on a pre-expanded AST. It does not measure cold parsing or native discovery latency. Installed qualification must bind the actual Host/source/runtime, request, grants and cache state, then preserve candidate order, coverage and independent work counters. Native pre-first-callback acquisition remains a separate measurement.
