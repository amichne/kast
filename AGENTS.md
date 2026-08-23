# Repository Guidelines

## Per-session task evidence

For every implementation task, keep its definition and proof in the current
session directory. Use the session directory supplied or selected by the active
workflow. Do not depend on a particular harness, provider, environment
variable, or repository-local session path.

### Define a task

Each session owns a `tasks/` directory. Each task uses the next available
`tasks/[0-9]{3}/` name: increment the highest existing number, or start with
`001` when the session has no task directories.

Create all five definition files when defining the task:

- `TASK.md` explains where we are, what we're solving, why we need to solve it,
  and how we know it's solved. Derive these statements from the user's request.
- `red.md` defines the semantics of the first required red case. State what the
  case exercises, the exact missing-behavior observation, and why that
  observation establishes the red instance. Name `red.sh` as its executable
  authority.
- `red.sh` is the executable authority for the red instance. It must expose the
  underlying failing check and exit successfully only when the exact red
  observation defined by `red.md` occurs.
- `green.md` defines the required green cases, their expected observations, and
  why those observations are sufficient to establish the requested outcome.
  Name `green.sh` as their executable authority.
- `green.sh` is the executable authority for completion. It must expose the
  checks it runs and exit successfully only when every case defined by
  `green.md` passes.

Keep paths, commands, and tool choices specific to the task, not to the agent
harness. If the active workflow does not provide a session directory, select a
session-scoped directory before defining the task.

### Capture and judge proof

Run `red.sh` before implementation and capture its complete standard output,
standard error, and final exit status in `red-proof.out`. Run `green.sh` after
implementation and capture the same evidence in `green-proof.out`. Create each
output in the numbered task directory when its script runs; the outputs are not
part of the five definition files.

A proof output is valid only when it is fresh for the current definition,
identifies the script that ran, contains the observed check results, and records
a zero proof-script exit status. A red proof succeeds when `red.sh` confirms the
expected underlying failure. A green proof succeeds when `green.sh` confirms
all required behavior.

Agents must inspect and validate `red-proof.out` and `green-proof.out`. These
captured outputs are the arbiters of truth; summaries, uncaptured commands, and
verbal completion claims cannot replace them.

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

## macOS indexer pathway

On a macOS developer workstation, explicit semantic demand is the normal
runtime bootstrap. Invoke the active public Kast CLI from the canonical
workspace root:

```shell
kast start
```

Kast reuses or starts one isolated indexer for the exact root. It
starts Gradle import and semantic indexing without opening, closing, focusing,
or routing through a foreground IDE project. `kast start` returns only when
semantic evidence is ready, or it reports a typed blocker.

Supported hosts are IntelliJ IDEA 2026.2/build 262 and Android Studio
2026.1.2/build 261. A supported installation supplies matched IntelliJ runtime
libraries to the isolated process. It is not a semantic backend and its open
or closed foreground state is irrelevant. Do not control a foreground IDE to
repair Kast. Resolve the typed indexer blocker instead.

## Public documentation topology

`zensical.toml` is the public site and navigation authority. Authored reader
content, generated reference boundaries, LikeC4 sources, and focused checks
live under `docs/`; follow `docs/AGENTS.md` for that ownership map.

Build the publishable artifact with `python3 docs/build_public_site.py`. The
script invokes Zensical against staged reader assets so repository guidance
and authored diagram sources do not enter the ignored `site/` output.

## Gradle topology

`settings.gradle.kts` is the project-membership authority. The main build has
exactly 36 target subprojects and one included build:

| Project or family | Broad owner | Dependency direction | Local guide |
| --- | --- | --- | --- |
| `:kernel` | Host-neutral refinement and generation primitives | Leaf project | `kernel/AGENTS.md` |
| `:distribution:{contract,managed}` | Runtime identity and the sole acquisition/store adapter | Contract then managed adapter | Each project root |
| `:protocol:{contract,registry,wire}` | Twelve canonical operations, typed metadata, and generated wire bindings | Contract then registry/wire | Each project root |
| `:workspace:{contract,service,intellij}` | Published-workspace identity, transition coordination, and IntelliJ/Gradle effects | Contract then service/adapter | Each project root |
| `:symbol:{contract,service,intellij}` | Discovery/exact-symbol contracts, admission, and IntelliJ/K2 execution | Contract then service/adapter | Each project root |
| `:relation:{contract,service,intellij}` | One-hop semantic relation evidence and bounded K2 execution | Contract then service/adapter | Each project root |
| `:traversal:{contract,service}` | Deterministic bounded traversal contracts and pure workflow | Contract then service | Each project root |
| `:topology:{contract,build,service,intellij}` | Explicit generation-bound graph construction, durable graph algorithms, and K2 extraction | Contract inward; build/service and IntelliJ adapter outward | Each project root |
| `:diagnostic:{contract,service,intellij}` | Generation-bound diagnostic evidence and compiler projection | Contract then service/adapter | Each project root |
| `:change:{contract,plan,apply,verify,recovery,intellij}` | Closed change intents and proof-preserving mutation stages | Contract inward; services and sole write adapter outward | Each project root |
| `:evidence:{contract,sqlite}` | Publication/recovery contracts and sole physical persistence | Contract then SQLite adapter | Each project root |
| `:runtime:{server,composition}` | Contract-only dispatch and the sole complete implementation graph | Server inward; composition depends on all target implementations | Each project root |
| `:cli` | Command parsing, indexer admission, wire transport, and result projection | Inward to kernel/protocol only | `cli/AGENTS.md` |
| `:indexer` | Isolated host for one already-constructed runtime composition | Depends only on `:runtime:composition` | `indexer/AGENTS.md` |
| included `build-logic` | `kast.*` conventions and reusable Gradle task types | Version catalog; no product project | `build-logic/AGENTS.md` |

## Dependency direction

Keep dependencies pointed toward host-neutral evidence:

```text
indexer -> runtime:composition
runtime:composition -> runtime:server + target services + target adapters
runtime:server -> protocol:{contract,registry,wire}
services -> their contracts and narrower contracts
IntelliJ/SQLite adapters -> their contracts
cli -> distribution:{contract,managed} + kernel + protocol:{contract,registry,wire}
workspace, symbol, relation, traversal, topology, diagnostic, change, and evidence contracts -> kernel
```

`build-logic` configures the graph but does not become a product dependency.
The repository root orchestrates Gradle verification; it must not become a
shared domain-code module.

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

## Turn-scoped guide maintenance

The repo-local Codex hook snapshots the Git worktree at `UserPromptSubmit` and
checks it at `Stop`. It reviews directories changed during the turn and their
ancestors in reverse breadth-first order, with changed leaves before parents.

- Create a missing local `AGENTS.md` only when the reviewed directory directly
  owns files. A directory that only groups child directories inherits its
  nearest ancestor guide and does not need a placeholder guide.
- Update an existing guide only when the turn changed a durable local fact.
- When an existing guide remains correct, record `unchanged` with the exact
  command emitted by the hook instead of manufacturing a documentation edit.
- Remove generated inheritance-only guides from directories with no directly
  owned files. Preserve a substantive guide when it defines a durable boundary
  for child owners.
- Do not bypass a pending guide operation. The hook's empty operation list is
  the mechanical completion evidence for this review.

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
7. Run the owning shell contracts as well when generated protocol,
   installation, CLI lifecycle, runtime compatibility, or distribution layout
   crosses the Gradle boundary.
