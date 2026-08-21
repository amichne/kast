# Task Contract

## Goal

Provide a root `installLocal` task that installs the current built Kast control product and matching semantic runtime as a working local `kast` command.

## Allowed Writes

- `.agent/TASK.md`
- `.agent-turn/install-local/`
- `build.gradle.kts`
- `packaging/test-install-local.sh`
- `${user.home}/.local/bin/kast`
- `${user.home}/.local/share/kast/control/`
- `${user.home}/.local/share/kast/runtime/`

No other paths may be modified.

## Allowed Reads

- `.agent/TASK.md`
- `AGENTS.md`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `packaging/`
- `cli/`
- `indexer/`
- `build-logic/`
- `.gitignore`
- Git status and diffs

## Non-Goals

- Remote or release installation.
- Windows or Linux installation support.
- An uninstall task.
- Modifying shell startup files or the user's `PATH`.
- Changing Kast runtime behavior.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
bash packaging/test-install-local.sh
```

Expected failure:

The contract fails because the root project has no `installLocal` task.

## Green Proof

Command:

```shell
bash packaging/test-install-local.sh
```

## Done When

- `./gradlew installLocal` installs the current control product, matching semantic runtime archive, and a working `kast` launcher under `${user.home}/.local` by default.
- `-PkastLocalPrefix=<path>` installs the same surface under an isolated prefix.
- The installed launcher resolves its paired control product and runtime independently of the repository build directory.
- The Green Proof passes.
- The default local installation completes successfully.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Resumed after the task contract was replaced by concurrent work.
- Added the root `installLocal` task and isolated-prefix installation contract.
- Proved replacement of a pre-existing launcher symlink without modifying its target.
- Installed and verified Kast 0.27.0 under `${user.home}/.local`.
- Focused proof, configuration-cache reuse, repository shape, and the full build pass.

## Out-of-Scope Findings

- None
