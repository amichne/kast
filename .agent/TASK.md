# Task Contract

## Goal

The Kotlin `kast` executable uses one predictable, composable Clikt command graph instead of the hand-rolled CLI grammar while preserving every existing semantic request, wire, API, runtime, and downstream contract.

## Allowed Writes

- `.agent/TASK.md`
- `.agent-turn/kotlin-agentic-correctness/`
- `gradle/libs.versions.toml`
- `cli/AGENTS.md`
- `cli/build.gradle.kts`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/`
- `cli/src/test/kotlin/io/github/amichne/kast/cli/`
- Baseline-only paths already changed by PR 630 under `workspace/`, `evidence/`, and `runtime/composition/` at head `6811f04894db12138808ad490e3d50eb7c367693`.

No other paths may be modified.

## Allowed Reads

- `AGENTS.md`
- `.agent/TASK.md`
- `.agent-turn/`
- `cli/`
- `gradle/`
- `build-logic/`
- `packaging/`
- `integration-tests/`
- `install.sh`
- `settings.gradle.kts`
- `protocol/`
- `distribution/`
- `kernel/`
- `runtime/`
- `workspace/`
- `symbol/`
- `relation/`
- `traversal/`
- `diagnostic/`
- `change/`
- `evidence/`
- `.github/`
- Git metadata and GitHub pull-request metadata for PRs 630, 631, and the resulting change.
- Installed Clikt artifacts and Kotlin, Effective Delivery, pstack, TDD, and Git skill instructions.

## Non-Goals

- Changing any protocol contract, generated wire model, wire schema, semantic request, semantic outcome, API shape, runtime implementation, or downstream service behavior.
- Adding a second parser, compatibility parser, fallback executable, hidden command, shell interpreter, or task runtime.
- Adding shell-specific output, prompts, color-dependent meaning, environment-variable options, or argument-file expansion.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
./gradlew :cli:test --tests '*CliCommandGraphContractTest'
```

Expected failure:

The new public command-graph contract fails because the current hand-rolled parser does not provide Clikt-generated nested help and option forms, one typed command authority, or composable stdout/stderr and exit routing.

## Green Proof

Command:

```shell
./gradlew :cli:test :cli:nativeTest verifyKastModuleGraph verifyNoLegacyArchitecture
```

## Done When

- PR 630 and PR 631 are present in order as the immutable baseline for this change.
- Clikt is the sole owner of public CLI token parsing, command dispatch, option conversion, validation, and help generation.
- Every command produces stable machine-composable stdout, reserves stderr for diagnostics, and returns a deterministic exit status.
- All eleven semantic operations, three local metadata flags, and five lifecycle commands remain reachable through one typed command graph.
- The legacy command parser, option-set parser, and parallel syntax/help authority are deleted.
- No file outside the CLI migration scope differs from the recorded post-630/631 baseline merge commit.
- No protocol, wire, API, runtime implementation, or downstream contract changes.
- The Green Proof passes.
- The repository-shape proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Task graph expression: `B630 -> B631 -> RED -> CLI_BOUNDARY -> COMMAND_FAMILIES -> LEGACY_REMOVAL -> PROOF -> DELIVERY`.
- `CLI_BOUNDARY` owns Clikt adaptation and typed command actions only.
- `COMMAND_FAMILIES` depends on `CLI_BOUNDARY` and may construct only existing protocol request types.
- `LEGACY_REMOVAL` depends on every command-family proof and removes all superseded grammar authorities in the same wave.
- `PROOF` depends on legacy absence, public command behavior, native transport, module direction, and repository shape.
- Scope constraint: the implementation delta after the recorded `B630 + B631` baseline is a subset of `.agent/TASK.md`, `.agent-turn/kotlin-agentic-correctness/`, `gradle/libs.versions.toml`, `cli/AGENTS.md`, `cli/build.gradle.kts`, and `cli/src/`.
- Immutability constraint: the implementation delta after the recorded baseline contains no path under `protocol/`, `runtime/`, `workspace/`, `symbol/`, `relation/`, `traversal/`, `diagnostic/`, `change/`, `evidence/`, `distribution/`, or `kernel/`.
- Baseline heads confirmed: PR 630 `6811f04894db12138808ad490e3d50eb7c367693`; PR 631 `45cde77e934b61ef1641a87d312ebbd7f0c3815c`.
- Ordered baseline merge recorded as `d5d399f8bb0589bf4c2eacb2e7a213330470a6f4`.
- Kotlin evidence session: `.agent-turn/kotlin-agentic-correctness/20260821T060501Z-clikt-command-graph/`.
- RED observed: `CliCommandGraphContractTest` failed because `-h` was rejected by the hand-rolled parser instead of completing with root help.
- `CLI_BOUNDARY`, `COMMAND_FAMILIES`, and `LEGACY_REMOVAL` completed: Clikt is the sole token grammar and every leaf refines directly to an existing request or lifecycle action.
- Final Green Proof passed after the proof-carrying Kotlin audit.
- Repository shape passed with zero violations; the command package has ten direct children and every changed Kotlin file remains below 400 lines.
- Distribution content and size proofs passed with Clikt packaged in the staged product.
- Staged launcher proof passed: `--schema | jq` reported eleven semantic commands, five lifecycle commands, and three local entries; malformed mode options returned one JSON diagnostic with exit status 2.
- Optional `installedProductTest` reached the baseline environment concern `runtime/endpoint-unavailable`; CLI-local staged-product behavior remained independently proven.

## Out-of-Scope Findings

- None
