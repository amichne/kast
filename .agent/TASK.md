# Task Contract

## Goal

Pull merged PRs 630 and 631 into the current PR 632 branch, preserve both the Clikt command graph and current lifecycle behavior through conflict resolution, merge PR 632, and prove the merged executable through a real full-lifecycle, all-operation, and composed semantic smoke run.

## Allowed Writes

- `.agent/TASK.md`
- `.agent-turn/kotlin-agentic-correctness/`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt`
- `/Users/amichne/code/kast/.git/worktrees/kast1/`
- `/Users/amichne/code/kast/.git/refs/heads/feature/clikt-command-graph`
- `/Users/amichne/code/kast/.git/refs/heads/main`
- GitHub pull-request metadata for PR 632.

No other paths may be modified.

## Allowed Reads

- Repository instructions, `.agent/TASK.md`, `.agent-turn/`, CLI source and tests, protocol contracts and wire schemas, build logic, packaging, integration tests, and Gradle configuration.
- Git history, refs, status, diffs, merge analysis, and GitHub metadata for PRs 630, 631, and 632.
- Kotlin Engineering, Effective Delivery, GitHub, Git change-flow, and pstack instructions and evidence.
- Installed and staged Kast artifacts, their structured process output, runtime state for the isolated smoke fixture, and read-only host/runtime diagnostics.

## Non-Goals

- Changing any wire model, protocol contract, API shape, semantic operation, downstream implementation, or lifecycle behavior beyond the already-merged PR 630 and PR 631 content.
- Adding new public commands, options, operation variants, or compatibility parsing.
- Modifying user source files or running change operations against the primary workspace.
- Publishing a release.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
git merge-base --is-ancestor origin/main HEAD
```

Expected failure:

The current PR 632 head does not yet contain the merge commits for PR 630 and PR 631 from the latest `origin/main`.

## Green Proof

Command:

```shell
bash .agent-turn/kotlin-agentic-correctness/20260821T165727Z-merge-clikt-pr632/smoke-merged-clikt.sh
```

## Done When

- The current branch contains latest `origin/main`, with conflicts resolved by preserving current PR 631 lifecycle behavior and PR 632 typed Clikt actions.
- PR 632 has passing required checks and is merged into `main`.
- Local `main` matches the remote PR 632 merge result.
- The staged merged executable completes stop, clean, start, status, reindex, final stop, and final clean against an isolated real Kotlin Gradle fixture.
- All eleven canonical semantic operations execute through the staged merged executable.
- A representative symbol workflow composes discover, resolve, describe, callers, callees, references, and bounded traversal using output identities from prior commands.
- A real change plan is applied, verified, and recovered in the isolated fixture.
- Smoke outputs remain valid single JSON documents suitable for piping and every unexpected stderr or exit status fails the proof.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Task graph expression: `ORIENT -> CONTRACT -> FETCH_MAIN -> RED -> MERGE_MAIN -> CONFLICT_PROOF -> LOCAL_GATE -> UPDATE_PR -> REMOTE_GREEN -> MERGE_PR -> SYNC_MAIN -> STAGE_PRODUCT -> LIFECYCLE -> OPERATIONS -> COMPOSITION -> CLEANUP -> REPORT`.
- Merge constraint: the resolved product tree may differ from PR 632 only where latest `main` already differs or where `KastCli.kt` must compose the typed action model with the merged lifecycle result model.
- Smoke constraint: lifecycle and change effects are confined to `.agent-turn/kotlin-agentic-correctness/20260821T165727Z-merge-clikt-pr632/fixture/`.
- Output constraint: every semantic and lifecycle invocation must yield one parseable JSON document; help/version remain local text boundaries.
- Remote heads observed: PR 630 merged as `e20a9d3a9b71cc5b1e7614704fc93263e2a459b8`; PR 631 merged as `34d33bcfec781b039a1fce027ce1ef06fdf676bc`.
- Read-only merge analysis identified conflicts only in `.agent/TASK.md` and `cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt`.

## Out-of-Scope Findings

- None
