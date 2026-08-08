---
type: Reference
title: CLI Reference
description: The typed public Kast protocol for coding agents.
tags: [cli, reference, commands, agents]
code_sources:
  - path: cli-rs/src/agent/public_protocol/registry.rs
---

# CLI Reference

<!-- Generated from the typed public operation registry. -->

`kast` is the only public interface. Every command supports `--output toon|json`; both formats preserve the same canonical protocol envelope. Compact TOON never removes semantic discriminators.

Every result contains `schemaVersion`, `operation`, `status`, and `result.type`. A `qualified` result names limitations. A `rejected` result contains a closed typed failure.

## Operations

| Operation | CLI syntax | Request type | Result type | Paging |
| --- | --- | --- | --- | --- |
| `workspace.home` | `kast` | `workspaceHome` | `workspaceHome` | unpaged |
| `workspace.ensure` | `kast workspace ensure` | `workspaceEnsure` | `workspaceReady` | unpaged |
| `workspace.refresh` | `kast workspace refresh --file src/main/kotlin/example/Widget.kt` | `workspaceRefresh` | `workspaceRefresh` | unpaged |
| `workspace.externalize` | `kast workspace externalize --failure-id <FAILURE_ID>` | `workspaceExternalize` | `externalization` | unpaged |
| `file.list` | `kast file list --match '**/*.kt'` | `fileList` | `files` | continuation (fileList) |
| `symbol.search` | `kast symbol search --query Widget` | `symbolSearch` | `matches` | unpaged |
| `symbol.resolve` | `kast symbol resolve --query 'example.Widget.render()'` | `symbolResolve` | `resolution` | unpaged |
| `symbol.show` | `kast symbol show --selector <SELECTOR>` | `symbolShow` | `symbol` | unpaged |
| `relation.references` | `kast relation references --selector <SELECTOR>` | `exactRelation` | `references` | continuation (references) |
| `relation.calls.incoming` | `kast relation calls incoming --selector <SELECTOR>` | `exactRelation` | `relations` | continuation (callsIncoming) |
| `relation.calls.outgoing` | `kast relation calls outgoing --selector <SELECTOR>` | `exactRelation` | `relations` | continuation (callsOutgoing) |
| `relation.implementations` | `kast relation implementations --selector <SELECTOR>` | `exactRelation` | `relations` | continuation (implementations) |
| `relation.hierarchy.supertypes` | `kast relation hierarchy supertypes --selector <SELECTOR>` | `exactRelation` | `relations` | continuation (hierarchySupertypes) |
| `relation.hierarchy.subtypes` | `kast relation hierarchy subtypes --selector <SELECTOR>` | `exactRelation` | `relations` | continuation (hierarchySubtypes) |
| `graph.summary` | `kast graph summary --scope symbol` | `graphProjection` | `graphSummary` | unpaged |
| `graph.nodes` | `kast graph nodes` | `graphNodes` | `graphNodes` | continuation (graphNodes) |
| `graph.neighbors` | `kast graph neighbors --node-selector <NODE_SELECTOR>` | `graphNeighbors` | `graphNeighbors` | unpaged |
| `graph.topology` | `kast graph topology --scope symbol` | `graphProjection` | `graphTopology` | unpaged |
| `graph.communities` | `kast graph communities --scope symbol` | `graphProjection` | `graphCommunities` | unpaged |
| `graph.derive` | `kast graph derive --experimental-derived-topology --out .kast/topology.json` | `graphDerive` | `derivedTopology` | unpaged |
| `graph.impact` | `kast graph impact --selector <SELECTOR>` | `graphImpact` | `impact` | continuation (graphImpact) |
| `diagnostic.check` | `kast diagnostic check --file src/main/kotlin/example/Widget.kt` | `diagnosticCheck` | `diagnostics` | unpaged |
| `change.plan.rename` | `kast change plan rename --selector <SELECTOR> --name Renamed` | `changePlanRename` | `changePlan` | unpaged |
| `change.plan.add-file` | `printf 'class Widget' | kast change plan add-file --file src/main/kotlin/example/Widget.kt` | `changePlanAddFile` | `changePlan` | unpaged |
| `change.plan.add-declaration` | `printf 'fun render() = Unit' | kast change plan add-declaration --file src/main/kotlin/example/Widget.kt` | `changePlanAddDeclaration` | `changePlan` | unpaged |
| `change.plan.replace` | `printf 'fun render() = Unit' | kast change plan replace --selector <SELECTOR>` | `changePlanReplace` | `changePlan` | unpaged |
| `change.apply` | `kast change apply --plan-id <PLAN_ID>` | `changeApply` | `mutationReceipt` | unpaged |
| `change.recover` | `kast change recover --recovery-id <RECOVERY_ID>` | `changeRecover` | `mutationReceipt` | unpaged |

Diagnostics do not block reference indexing.

## Composition

Use `query` only for `symbol.search` and `symbol.resolve`. Copy every Kast-issued `selector` verbatim into compatible exact operations. Repeat the same operation with its opaque `continuation`; continuations never cross operations. Apply only a returned plan ID with `kast change apply --plan-id <PLAN_ID>`. Recover only a returned recovery ID with `kast change recover --recovery-id <RECOVERY_ID>`.

Public paths are workspace-relative and use forward slashes. A qualified name, location, path, offset, or graph node selector is never a symbol selector.

## Boundary semantics

Externalizing an eligible content-bound failure records an explicit `UNKNOWN` graph boundary. Unknown, stale, incomplete, and wrong-workspace evidence fails closed.

## Internal control plane

The private release-local `libexec/kastctl` multicall entrypoint remains the developer control plane. It is not a public semantic route. Read `developerOperations.cli` and use `/kast:developer`; do not assume `kastctl` is on `PATH`.
