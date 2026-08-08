---
name: kast
description: Use when Kotlin or Gradle work needs compiler-backed discovery, exact traversal, graph evidence, diagnostics, or validated changes through Kast.
---

# Kast

<!-- Generated from the typed public operation registry. -->

Use `kast` from the target workspace. Read the canonical envelope; never infer identity from display data.

## Route by semantic state

- Unknown target -> `symbol.search`.
- Exact textual target -> `symbol.resolve`.
- Have selector -> a compatible exact operation.
- Have continuation -> repeat the same operation.
- Have plan ID -> `change.apply`.
- Have recovery ID -> `change.recover`.

Copy Kast-issued selectors, continuations, plan IDs, and recovery IDs verbatim. Never reconstruct them from qualified name, location, path, offset, kind, or container.

## Registered syntax

| Operation | Command | Valid successors |
| --- | --- | --- |
| `workspace.home` | `kast` | workspace.ensure |
| `workspace.ensure` | `kast workspace ensure` | workspace.refresh, file.list, symbol.search, diagnostic.check |
| `workspace.refresh` | `kast workspace refresh --file src/main/kotlin/example/Widget.kt` | file.list, symbol.search, diagnostic.check |
| `workspace.externalize` | `kast workspace externalize --failure-id <FAILURE_ID>` | workspace.refresh |
| `file.list` | `kast file list --match '**/*.kt'` | file.list, symbol.search |
| `symbol.search` | `kast symbol search --query Widget` | symbol.resolve, symbol.show, relation.references, relation.calls.incoming, relation.calls.outgoing, relation.implementations, relation.hierarchy.supertypes, relation.hierarchy.subtypes, graph.impact |
| `symbol.resolve` | `kast symbol resolve --query 'example.Widget.render()'` | symbol.show, relation.references, relation.calls.incoming, relation.calls.outgoing, relation.implementations, relation.hierarchy.supertypes, relation.hierarchy.subtypes, graph.impact |
| `symbol.show` | `kast symbol show --selector <SELECTOR>` | relation.references, relation.calls.incoming, relation.calls.outgoing, relation.implementations, relation.hierarchy.supertypes, relation.hierarchy.subtypes, graph.impact, change.plan.rename, change.plan.replace |
| `relation.references` | `kast relation references --selector <SELECTOR>` | relation.references |
| `relation.calls.incoming` | `kast relation calls incoming --selector <SELECTOR>` | relation.calls.incoming |
| `relation.calls.outgoing` | `kast relation calls outgoing --selector <SELECTOR>` | relation.calls.outgoing |
| `relation.implementations` | `kast relation implementations --selector <SELECTOR>` | relation.implementations |
| `relation.hierarchy.supertypes` | `kast relation hierarchy supertypes --selector <SELECTOR>` | relation.hierarchy.supertypes |
| `relation.hierarchy.subtypes` | `kast relation hierarchy subtypes --selector <SELECTOR>` | relation.hierarchy.subtypes |
| `graph.summary` | `kast graph summary --scope symbol` | graph.nodes, graph.topology, graph.communities |
| `graph.nodes` | `kast graph nodes` | graph.nodes, graph.neighbors |
| `graph.neighbors` | `kast graph neighbors --node-selector <NODE_SELECTOR>` | graph.neighbors |
| `graph.topology` | `kast graph topology --scope symbol` | graph.communities |
| `graph.communities` | `kast graph communities --scope symbol` | graph.nodes |
| `graph.derive` | `kast graph derive --experimental-derived-topology --out .kast/topology.json` | graph.topology |
| `graph.impact` | `kast graph impact --selector <SELECTOR>` | graph.impact |
| `diagnostic.check` | `kast diagnostic check --file src/main/kotlin/example/Widget.kt` | change.plan.rename, change.plan.replace |
| `change.plan.rename` | `kast change plan rename --selector <SELECTOR> --name Renamed` | change.apply |
| `change.plan.add-file` | `printf 'class Widget' | kast change plan add-file --file src/main/kotlin/example/Widget.kt` | change.apply |
| `change.plan.add-declaration` | `printf 'fun render() = Unit' | kast change plan add-declaration --file src/main/kotlin/example/Widget.kt` | change.apply |
| `change.plan.replace` | `printf 'fun render() = Unit' | kast change plan replace --selector <SELECTOR>` | change.apply |
| `change.apply` | `kast change apply --plan-id <PLAN_ID>` | change.recover |
| `change.recover` | `kast change recover --recovery-id <RECOVERY_ID>` | change.apply |

Use `--output toon` for compact TOON or `--output json` for JSON. Both retain `schemaVersion`, `operation`, `status`, and `result.type`. Treat only complete evidence and a `VERIFIED` mutation receipt as success. A qualified result has explicit limitations; rejected, conflicted, rolled-back, and recovery-required outcomes are non-success.

For setup, runtime control, configuration, raw RPC, local-state inspection, or release work, invoke `/kast:developer`. Read `developerOperations.cli`; do not assume `kastctl` is on `PATH`.
