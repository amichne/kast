# Repository Guidelines

## Evidence-driven work packets

For every implementation task:

1. Derive one work packet from the user's request: one observable outcome,
   exact allowed writes, explicit non-goals, one focused RED, the smallest
   sufficient GREEN, and objective completion conditions.
2. Record that packet in ignored `.agent/TASK.md` before the first write. Keep
   it short; it is a scope lock and evidence ledger, not a restatement of this
   file or a running transcript.
3. After the first implementation write, freeze the goal, writes, non-goals,
   RED, GREEN, and completion conditions. Update only execution state and
   blocking out-of-scope findings.
4. Re-read the packet before the first write, after compaction or user steering,
   before verification, and before completion. Re-reading it before every tool
   call is unnecessary.
5. Establish a focused failing proof before production changes. Verify with the
   focused GREEN first, then widen only as required by the nearest guide.
6. At completion, compare the changed-path set with the packet, preserve
   pre-existing user work, and stop when every completion condition is met.

For a dependency-ordered program, select exactly one ready node from its
checked-in task graph. Record the node ID and dependencies in `.agent/TASK.md`;
do not copy a parallel tree of per-node Markdown contracts. Current repository
HEAD is authoritative over a pinned planning baseline. Land independently
verifiable nodes independently before starting their dependents.

## IntelliJ-first execution

The current migration program is
`.agents/arch/kast-architecture-firewall-and-mutation-workflow.md`. Its first
terminal journey is:

```text
native symbol discovery -> exact selector -> exact definition/description
```

Use production IntelliJ file, class, symbol, scope, smart-read, cancellation,
and liveness facilities where they already implement the operation. Preserve
one canonical root and semantic generation across the journey. Results and
continuations must be bounded and detached; never retain PSI or other live IDE
objects across requests.

Ordinary reads must not import Gradle, build a graph, write SQLite, mutate
source, recursively refresh the workspace, control processes, or reacquire
aggregate `AnalysisBackend` authority. Performance measurements are regression
diagnostics for this tranche, not a separate optimization program.

## Kotlin proof-carrying validation transitions

Apply this section to every changed production Kotlin source file with zero
exceptions.

1. Treat validation as a type transition `f: T -> S`, where `T` is the weaker
   boundary representation and `S` is any more constrained derivation of `T`
   that carries every invariant established by `f`. `S` does not need to
   contain or wrap `T`; it may be a distinct value, state, capability, or
   aggregate derived from it.
2. When validation has an expected failure, use the closed transition
   `f: T -> Result<S, E>`, where `E` is a finite typed failure. Do not return
   `Boolean`, `Unit`, `null`, the original `T`, or an arbitrary exception as the
   validation protocol.
3. Keep primitive input at the boundary. After parsing, normalization,
   validation, lookup, authorization, or state admission succeeds, pass the
   stronger type or capability inward. Do not unpack it and make downstream
   callers repeat or remember the proof.
4. Model absence and lifecycle with closed states or state-specific
   capabilities. Do not use nullable fields, boolean flags, strings, or call
   order as domain state.
5. Restrict construction of the stronger representation to the transition
   owner. A type alias, comment, naming convention, or validator that returns
   the original primitive does not preserve proof.
6. Every validating or parsing Kotlin API must have KDoc that states:
   - the proof transition using the concrete types, such as
     `Path -> RepositorySnapshotDatabase`;
   - the invariant gained by the output type;
   - the closed expected failure type, when applicable;
   - the outer boundary where raw extraction is permitted.
7. Callers must consume the returned stronger type. Calling a validator and
   discarding its result is prohibited.
8. Before completion, review every changed production Kotlin file and reject
   every newly introduced primitive contract, repeated validation, nullable
   control state, string protocol, or discarded proof. There are no local
   exceptions to this audit.

Example:

```kotlin
/**
 * Proof transition: `Path -> RepositorySnapshotDatabase`.
 *
 * Establishes that the path is canonical, repository-bound, regular,
 * non-symlinked, and backed by a matching snapshot manifest. The returned
 * capability may expose a JDBC URI only at the SQLite attachment boundary.
 */
fun requireRepositorySnapshotDatabase(path: Path): RepositorySnapshotDatabase
```

## Native IntelliJ MCP

Kast agent tooling is disabled until an explicit re-evaluation makes it useful
again. Do not invoke, start, repair, or silently fall back to the Kast CLI,
plugin, runtime, diagnostics MCP, or duplicate semantic index.

Use the bundled IntelliJ IDEA MCP server named `idea`. Keep its complete
available tool catalog exposed in both the IDE and Codex; do not add an
`enabled_tools` allowlist. Always pass the canonical current worktree as
`projectPath`. If that exact project is not open or indexed, make that state
explicit and open/qualify it before Kotlin work rather than using another
checkout.

For every Kotlin edit:

1. Use IDE-native project, symbol, text, call-hierarchy, and quick-documentation
   tools to establish the target and its consumers before editing.
2. Make the scoped file change, then use IntelliJ formatting and file or batch
   inspections on every changed Kotlin file.
3. Use the IntelliJ build tool for compile feedback, then run the focused
   Gradle proof and any widening ring required below.

Tool availability does not grant operation authority. Use only the tools
needed by the active work packet and preserve all repository invariants.

## Gradle topology

`settings.gradle.kts` is the project-membership authority. The main build has
four subprojects and one included build:

| Project | Broad owner | Direct project dependencies | Local guide |
| --- | --- | --- | --- |
| `:analysis-api` | Host-neutral contracts, validation, config, docs, and fixtures | None | `analysis-api/AGENTS.md` |
| `:index-store` | SQLite evidence, stages, snapshots, generations, and overlays | `:analysis-api` | `index-store/AGENTS.md` |
| `:analysis-server` | JSON-RPC admission, routing, transport, and server orchestration | `:analysis-api` as API; `:index-store` as implementation | `analysis-server/AGENTS.md` |
| `:indexer` | Isolated IntelliJ/K2 runtime and backend | All three Kotlin library modules | `indexer/AGENTS.md` |
| included `build-logic` | `kast.*` conventions and reusable Gradle task types | Version catalog; no product project | `build-logic/AGENTS.md` |

`analysis-api/src/testFixtures` is an independently consumed Gradle source-set
variant with its own nearer `AGENTS.md`. The Rust `cli-rs` crate is outside the
Gradle project graph and follows `cli-rs/AGENTS.md`.

## Dependency direction

Keep dependencies pointed toward host-neutral evidence:

```text
indexer -> analysis-server -> analysis-api
   |             |
   +-----------> index-store -> analysis-api
```

`build-logic` configures the graph but does not become a product dependency.
The repository root orchestrates generated protocol/docs output, portable
indexer packaging, and the Rust development CLI; it must not become a shared
domain-code module.

## Progressive instruction disclosure

- Start with this file, then read the nearest `AGENTS.md` for every path in
  scope. Nearer guides add local ownership and proof; they do not weaken this
  repository contract.
- Keep leaf guidance about exact types, state machines, files, and focused
  tests. Module guides describe local subsystem maps, dependency boundaries,
  durable invariants, and widening verification. This root describes only
  repository-wide policy and topology.
- Every project root named by `include` or `includeBuild` must own an
  `AGENTS.md`. Add a nearer guide for an independently consumed source set or a
  distinct nested owner only when it has invariants or verification that would
  otherwise make its parent misleading.
- Do not copy the same rule into every level. Move a rule to the narrowest
  common owner and let parents link downward.
- When code moves between owners, update the affected leaf/module guides in
  the same change and verify that every named path, symbol, task, and authority
  still exists.

## Repository verification

Use widening proof and stop at the first ring that fully covers the change:

1. Run the focused test class or task named by the nearest module guide.
2. Run the owning module's `test` or `check` task.
3. Run direct consumers when a public contract, storage schema, convention,
   runtime payload, or lifecycle boundary changed.
4. Run `./gradlew projects` to confirm project-guide coverage after changing
   settings or project layout.
5. Run `./gradlew test` for cross-module Kotlin behavior and `./gradlew build`
   when conventions, publication, or packaging changed.
6. Run `python3 .github/scripts/check-repository-shape.py --root .` on the
   final tracked tree. Governed files may have at most 400 physical lines and
   governed directories at most 10 direct children.
7. Run the owning shell/Rust contracts as well when generated protocol,
   installation, CLI lifecycle, runtime compatibility, or distribution layout
   crosses the Gradle boundary.
