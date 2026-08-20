# Task Contract

## Goal

The completed minimal indexer lifecycle changes are merged through a green pull request and published as the immutable minor release `v0.26.0`.

## Allowed Writes

- `.agent/TASK.md`
- `AGENTS.md`
- `cli/AGENTS.md`
- `cli/src/main/kotlin/io/github/amichne/kast/cli/`
- `cli/src/test/kotlin/io/github/amichne/kast/cli/`
- `workspace/intellij/build.gradle.kts`
- `workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleJvm.kt`
- `workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt`
- `workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledProjectJvm.kt`
- `workspace/intellij/src/test/kotlin/io/github/amichne/kast/workspace/intellij/InstalledProjectJvmTest.kt`

No other paths may be modified.

## Allowed Reads

- Repository source, instructions, Git history, build and release metadata, local validation evidence, GitHub pull-request state, GitHub Actions state and logs, and GitHub release state.

## Non-Goals

- Changing behavior beyond fixes required for this pull request's deterministic CI failures.
- Modifying release automation, repository secrets, environment protection, or branch protection.
- Including `session-ses_fecf.md` or `tmp.md` in any commit.
- Moving, deleting, or force-updating an existing tag or release.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
test -n "$(gh pr list --head feature/indexer-lifecycle-commands --state merged --json number --jq '.[0].number')" && gh release view v0.26.0
```

Expected failure:

No merged pull request exists for the delivery branch and release `v0.26.0` does not exist.

## Green Proof

Command:

```shell
pr="$(gh pr list --head feature/indexer-lifecycle-commands --state merged --json number --jq '.[0].number')" && test -n "$pr" && gh pr checks "$pr" --required --json bucket,name,state,workflow && gh release view v0.26.0 --json assets,isDraft,isImmutable,isLatest,isPrerelease,publishedAt,tagName,targetCommitish,url
```

## Done When

- One focused pull request contains the completed minimal lifecycle changes and excludes unrelated user files.
- The pull request's required checks are green for its final head.
- The pull request is merged into `main`.
- The checked-in release workflow completes successfully for version `0.26.0` from the exact merged `main` commit.
- GitHub reports `v0.26.0` as a published, immutable, non-prerelease release with the expected control and semantic-runtime assets and checksums.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Branch `feature/indexer-lifecycle-commands` was created from exact `origin/main` commit `5e834d4b678aaed072b3006aae376e8501cf7760`.
- GitHub reports `v0.25.4` as the latest immutable release, so the requested minor release is `v0.26.0`.
- The exact CI Gradle command and repository-shape check pass locally before publication.
- `session-ses_fecf.md` and `tmp.md` remain untracked and unstaged.


## Out-of-Scope Findings

- None
