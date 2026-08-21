# Task Contract

## Goal

Publish the completed runtime lifecycle changes as a focused draft pull request with a post-push self-review and representative isolated validation using genuine implementations and fixtures.

## Allowed Writes

- `.agent/TASK.md`
- `.agent-turn/kotlin-agentic-correctness/20260821T032000Z-runtime-lifecycle-pr/`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/RuntimeLifecycle.kt`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/RuntimeEndpointArtifacts.kt`
- `cli/src/test/kotlin/io/github/amichne/kast/cli/RuntimeLifecycleTest.kt`
- `/Users/amichne/code/kast/.git/worktrees/kast/`
- `/Users/amichne/code/kast/.git/refs/heads/feature/runtime-lifecycle-markers`
- `/Users/amichne/.colima/`
- `/Users/amichne/.docker/contexts/`
- `/Users/amichne/.lima/`
- GitHub branch and pull-request metadata for `feature/runtime-lifecycle-markers`.

No other paths may be modified.

## Allowed Reads

- Repository instructions, lifecycle source and tests, build metadata, CI workflows, Git history, diffs, status, and remote metadata.
- Kotlin Engineering, Effective Delivery, GitHub, pstack, and Git change-flow instructions and evidence.
- Local container-runtime status and read-only host environment metadata.
- Pull-request checks, diff, review state, and Actions logs.

## Non-Goals

- Merging the pull request.
- Publishing a release.
- Changing repository CI or adding persistent container configuration.
- Adding mocks or a mocking framework.
- Replacing genuine filesystem and process implementations with simulated behavior.
- Refactoring unrelated code.
- Generalizing the lifecycle implementation.
- Fixing unrelated failures.

## Red Proof

Command:

```shell
test "$(git branch --show-current)" = "feature/runtime-lifecycle-markers" && test -n "$(gh pr list --head feature/runtime-lifecycle-markers --state open --json number --jq '.[0].number')"
```

Expected failure:

The checkout is detached and no open pull request exists for `feature/runtime-lifecycle-markers`.

## Green Proof

Command:

```shell
test "$(git branch --show-current)" = "feature/runtime-lifecycle-markers" && pr="$(gh pr list --head feature/runtime-lifecycle-markers --state open --json number --jq '.[0].number')" && test -n "$pr" && gh pr view "$pr" --json number,url,headRefOid,baseRefName,state,isDraft
```

## Done When

- A focused commit containing only the requested lifecycle changes is pushed on `feature/runtime-lifecycle-markers`.
- A draft pull request targets `main` and describes behavior, validation, and residual risk.
- The pushed pull-request diff receives a Kotlin type, boundary, package, and test self-review; justified findings are fixed and revalidated.
- Representative validation passes in a clean container, or the exact container blocker is recorded and an isolated temporary-checkout proof passes instead.
- Validation uses genuine implementations and filesystem fixtures without mocks or a mocking framework.
- Remote checks are read after the latest push.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Delivery task contract established from the pull-request and validation request.

## Out-of-Scope Findings

- The architecture policy model is stale relative to the 32-project topology in `settings.gradle.kts`; repairing it is unrelated to semantic identity stability.
- Both the staged product and installed Kast `0.26.0` report `endpoint-unavailable` before a live semantic request can run. Another user-owned Kast indexer for a separate worktree is active and was not interrupted.
