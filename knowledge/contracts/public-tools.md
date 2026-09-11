---
type: API Contract
title: Public intent tools
description: Schema-bound search and diagnostics presentations lower into existing canonical operations without transferring compiler authority.
resource: file://app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
tags: [tools, query, protocol, agents]
timestamp: 2026-09-11T00:00:00Z
code_sources:
  - path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - path: packaging/generate-public-query.py
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
    symbols: [PublicToolContract, AdmittedPublicTool, PublicToolCanonical]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastQueryInput.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexSessionProjection.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalQueryCliDocuments.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt
---

# Public intent tools

The authored tool bundle generates Kotlin request DTOs, concrete normalization defaults, closed presentation identities, full admission schemas, Codex registration schemas and separate Responses strict registrations. The same production generator retains the explicit legacy `query run` CLI grammar.

`search_classes`, `search_functions`, `search_declarations` and `check_diagnostics` are eager. `query_symbols` is deferred. Ordinary searches fix or admit declaration kinds and request names, locations and signatures; diagnostics lower to the existing path/limit request. Required nullable controls normalize before canonical construction. Directory/package scope shapes are exclusive, and duplicates and invalid lexical values reject.

The advanced pipeline preserves source meaning, step order, repeated steps and empty projections. Expansion returns related declarations; occurrence-oriented relation facts remain the relation-read contract. `symbol_ref` is derived from the exact result token and is equal to the retained migration field `ref.token`. No token spelling creates authority: existing runtime owners re-admit workspace, lifetime, epoch and compiler evidence.

Installed projection 10 and CLI invocation version 3 join by tool name. Repeated canonical operation IDs are allowed only with consistent effect, approval, budget and output metadata. Private admitted requests retain their presentation and schema identities through transport encoding, excluding cross-tool substitution. Old persisted catalogs reject rather than silently accepting a new grammar. Source, relation, traversal and change capabilities remain; raw candidate lookup/refinement remains opt-in.

The 32-example corpus, typed lowering, duplicate/path rejection, CLI wire parity and production provider routing are deterministic proofs. Codex schemas omit the Responses-only `strict` field and retain separate stronger admission constraints. These checks do not by themselves establish live API acceptance or improved model first-call accuracy.

See the [public search guide](../../docs/public/search.mdx) and [semantic query flow](../flows/semantic-query.md).
