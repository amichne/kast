---
title: Get started
description: Install Kast, start a workspace runtime, verify it is ready, and
  stop it cleanly.
icon: lucide/play
---

This guide gets you from a fresh shell to a ready workspace runtime. When you
finish, you will have `kast` on your path, a daemon attached to one workspace,
and a clear way to confirm the runtime is healthy. If you use an agent
workflow, the same install also gives you `kast-skilled` so you can link the
packaged `kast` skill into a workspace without copying it.

## Before you begin

You need a small amount of local setup before the first command can succeed.

- Keep Java 21 or newer available through `JAVA_HOME` or your shell `PATH`.
- Know the absolute path to the Kotlin workspace you want to analyze.
- Optional: You can influence how the `kast` CLI binary is resolved at runtime:
  - `KAST_CLI_PATH` — if set to an executable path, the resolver will prefer
    it when locating the `kast` binary.
  - `KAST_SOURCE_ROOT` — when set to the repository source root, the resolver
    may use local build outputs, for example `kast/build/scripts/kast` or
    `dist/kast/kast`, and can attempt an auto-build via `./gradlew` if Java
    21+ is available.

## Install the published CLI

Install the current published release so the `kast` executable is available
from your shell.

1. Run the copyable installer from any directory:

   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)"
   ```

2. If you already have this repository checked out, you can run the same
   installer from the repo root instead:

   ```bash
   ./install.sh
   ```

3. If your current shell still cannot find `kast`, open a new shell session so
   the updated `PATH` takes effect.

!!! note
    The installer validates that Java 21 or newer is available before it
    unpacks the release.

## Optional: link the packaged `kast` skill into a workspace

If you use an agent that loads repository-local skills, run `kast-skilled`
from the workspace root after you install the CLI. The command creates only a
symlink, so every workspace link points at the single packaged skill root from
`KAST_SKILL_PATH` instead of copying the skill contents.

1. From the workspace root, run:

   ```bash
   kast-skilled
   ```

2. Confirm the prompt before the symlink is created.

3. Let the command choose the default location from the directories already
   present in the current directory:

   - `.agents/skills/kast`
   - `.github/skills/kast`
   - `.claude/skills/kast`

4. If you need a non-default path or a non-interactive run, pass explicit
   options:

   ```bash
   kast-skilled --target-dir /absolute/path/to/skills --yes
   ```

## Optional: enable shell completion

The installer can offer to enable completion in your shell init file. If you
accept that prompt, Bash or Zsh loads the generated completion script the next
time the shell starts. If you skip the prompt or want to enable it manually,
load the script yourself. The completion scripts cover the public command tree
and the supported `--key=value` options.

1. If the installer offers completion setup, accept the prompt to update your
   shell init file automatically.

2. In Bash, source the generated script manually when needed:

   ```bash
   source <(kast completion bash)
   ```

3. In Zsh, source the generated script manually when needed:

   ```bash
   source <(kast completion zsh)
   ```

4. Run `kast --help` if you want the grouped help page before the first
   workspace command.

## Start or reuse a workspace runtime

Use `workspace ensure` to attach Kast to one workspace. The command reuses a
healthy daemon when one already exists, or starts a new daemon when it does
not.

1. Run the command with an absolute workspace path:

   ```bash
   kast \
     workspace ensure \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Read the JSON result on stdout for the selected runtime metadata.
3. Read the daemon note on stderr to see whether Kast started a daemon or
   reused one that was already ready.

## Verify that the workspace is ready

Check status first, then inspect capabilities, so you know the runtime is live
and the features you plan to call are actually advertised.

1. Inspect the workspace runtime:

   ```bash
   kast \
     workspace status \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Inspect the advertised capability set:

   ```bash
   kast \
     capabilities \
     --workspace-root=/absolute/path/to/workspace
   ```

The second command is the fastest way to confirm which read and mutation
operations the current runtime supports.

## Stop the runtime when you are done

Stop the workspace daemon when you no longer need it. This keeps the lifecycle
explicit and makes it easy to tell which workspace runtimes are active.

1. Stop the daemon for the workspace:

   ```bash
   kast \
     daemon stop \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Read stderr for the stop note when a daemon was actually stopped.

## Troubleshooting first-run issues

The first failure usually comes from one of a small set of environment or usage
mistakes.

- If the installer fails immediately, confirm that Java 21 or newer is
  installed and your operating system is supported by the published bundle.
- If `kast-skilled` cannot find the packaged skill root, confirm that the
  published install completed and that `KAST_SKILL_PATH` points to a real
  `kast` skill directory when you override it.
- If Kast reports a usage error, rewrite the command so every option uses the
  `--key=value` form.
- If Kast cannot find the workspace or a file, convert the path to an absolute
  path and rerun the command.
- If you want the shell-specific completion help pages, run
  `kast help completion`.
- If `kast` is not found on your PATH, you can point the resolver directly at
  a binary using `KAST_CLI_PATH`:

   ```bash
   export KAST_CLI_PATH=/absolute/path/to/kast
   kast workspace status --workspace-root=/absolute/path/to/workspace
   ```

- If you have a local checkout of the repository and want `kast` to use the
  locally-built CLI, or build it automatically, set `KAST_SOURCE_ROOT` to the
  repo root. The resolver will look for expected build outputs and may run a
  minimal Gradle step to produce `kast/build/scripts/kast` when Java 21+ is
  available. If you want the repo-local packaged CLI under `dist/kast`, run
  `./build.sh` from the repo root first:

   ```bash
   export KAST_SOURCE_ROOT=/absolute/path/to/kast/repo
   kast workspace ensure --workspace-root=/absolute/path/to/workspace
   ```

## Next steps

Once the workspace runtime is ready, you can move on to the common analysis
tasks and the compact reference page.

- [Run analysis commands](run-analysis-commands.md)
- [Command reference](command-reference.md)
