# CI selection contract

This matrix precedes the selector implementation.
The source revision is `14ed044a0b` plus the unchanged source from `371fb8bfcb`.
The selector must preserve each stated obligation before it can omit other work.
The graph must include production, test, fixture, generated-contract, and shared-resource consumers.

| Changed input | Independently expected obligations |
| --- | --- |
| Documentation page, navigation, style, or snippet | Mintlify, parser, glossary, knowledge links, and the actual example generator and tests |
| `query/service` production | Module checks for `query/service`, `query/protocol`, `runtime/hosted`, and `cli`; shared guards and affected semantic families |
| `query/contract` production | The leaf-service obligations plus `query/contract` |
| `workspace/contract` test fixtures | Every fixture consumer, including `query/protocol`, `runtime/hosted`, `app-server`, and transitive `cli` consumers |
| `relation/intellij` adapter or authored semantic input | Relation and hosted module checks, real K2 families, their compiler witnesses and rejection controls, plus downstream protocol contracts |
| Protocol schema or generated-contract input | Its owner, declared consumers, encoded-shape tests, generated views, and schema guards |
| Shared query-delivery JavaScript | CLI and query-protocol tests, even though the latter edge exists only in test inputs |
| Packaging, native inputs, toolchain, or release policy | Source obligations and candidate qualification |
| Unknown ownership, malformed history, or stale graph | Full validation or explicit rejection |

Renames and deletions contribute both diff endpoints.
A selected family keeps every manifest case, compiler witness, and known regression control.
Class selection expands to the complete declared case population for that class.
Missing classes, skipped cases, duplicate results, malformed counts, and zero discovery reject the aggregate.
Plans and receipts bind the source tree, comparison basis, graph inputs, and semantic manifest.
A changed identity rejects reuse.

The initial selector can keep broader semantic families when evidence cannot justify an omission.
It cannot relabel that work as reduced execution.
A source receipt cannot authorize promotion.

## Frozen rollout corpus

`config/ci/rollout-corpus.json` records four actual historical changes before selector implementation.
Each entry fixes the original parent, resulting revision, exact path set, and binary-patch digest.
Each reverse patch applied to the current source in a read-only applicability check.
A replay must preserve those bytes and paths.

- Documentation replays the previously expensive value-journey change from `9269df7`.
- The leaf-service case replays the five query-service files from `abf9e57bd7`.
- The semantic-adapter case replays inherited receiver dispatch and its real compiler fixture from `371fb8bf`.
- The shared-contract case replays immutable path reuse and its regression test from `9ff85c5156`.

Run focused and full qualification on each resulting source tree before omissions become the default.
Keep exact case IDs and task outcomes from both paths.
End duplicate execution after that rollout.
If an obligation disappears, restore the full path and keep the fixture optimization.

Measure required source feedback from workflow start through its aggregate.
Report queue time separately.
Use the same runner class for all four cases.
Warm toolchain and dependency caches, but remove the relevant changed outputs before each first measurement.
Measure task-cache reuse separately.
Keep a separate cold-provisioning run and a final candidate replay.
Report total runner work, compiler counts, selected cases, cache outcomes, and deferred qualification work.

Completion needs all four source-feedback results of at most 360 seconds.
The criterion remains open until actual hosted evidence passes.
