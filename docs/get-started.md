---
title: Get started
description: Install Kast, prewarm or auto-start a workspace runtime, verify
  it, and stop it cleanly.
icon: lucide/play
---

This guide covers two ways to get Kast running: the standalone CLI for
terminal and CI workflows, and the IntelliJ plugin for IDE-integrated
analysis.

## Prerequisites

You need a small amount of local setup before the first command can succeed.

- Keep Java 21 or newer available through `JAVA_HOME` or your shell `PATH`.
  The launcher is native-first, but the daemon still runs on the JVM.
- Know the absolute path to the Kotlin workspace you want to analyze.
- Optional: If you use the packaged `kast` skill or the repo-local launcher
  resolver, you can influence how it finds `kast`:
  - `KAST_CLI_PATH` — if set to an executable path, the resolver prefers it
    when locating the `kast` launcher or binary.
  - `KAST_SOURCE_ROOT` — when set to the repository source root, the resolver
    may use local build outputs, for example `kast/build/scripts/kast` or
    `dist/kast/kast`, and can attempt `./gradlew :kast:writeWrapperScript` when
    Java 21+ is available.

## Choose a backend

Kast provides two backend options. Pick the one that matches your workflow.

- **Standalone backend (CLI):** Best for terminal workflows, CI pipelines,
  and LLM agents. Advanced users can also expose the server over TCP for
  remote JSON-RPC clients. The `kast` CLI manages the daemon lifecycle for you.
  Install the CLI and the standalone distribution together in one step.
- **IntelliJ plugin backend:** Best when you already have IntelliJ IDEA open.
  The plugin starts a Kast server automatically when IntelliJ opens a
  project. Install the plugin into IntelliJ and connect to it from any
  JSON-RPC client.

See [How Kast works](how-it-works.md) for a detailed comparison of the two
backends, including a capability table.

## Install the standalone CLI

Install the current published release so the `kast` executable is available
from your shell.

1. Run the copyable installer from any directory:

   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)"
   ```

2. Install all components (standalone CLI + IntelliJ plugin) non-interactively:

   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)" -- --components=all --non-interactive
   ```

3. Install JVM-only variant for environments without GraalVM native images:

   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)" -- --jvm-only
   ```

4. If you already have this repository checked out, you can run the same
   installer from the repo root instead:

   ```bash
   ./install.sh
   ```

5. If your current shell still cannot find `kast`, open a new shell session so
   the updated `PATH` takes effect.

The installer supports these flags:

| Flag | Description |
|------|-------------|
| `--components=<list>` | Comma-separated: `standalone`, `intellij`, `all` (default: `standalone`) |
| `--jvm-only` | Install the JVM-only variant (no native binary) |
| `--non-interactive` | Skip all interactive prompts |

At the end of installation, the installer prints a config summary showing the
install root, binary path, JVM-only mode, installed components, and shell RC
file path.

!!! note
    The installer validates that Java 21 or newer is available before it
    unpacks the release. The portable bundle includes the launcher, the bundled
    native client, and the JVM runtime libs together.

## Optional: install the packaged `kast` skill into a workspace

If you use an agent that loads repository-local skills, run
`kast install skill` from the workspace root after you install the CLI. The
command copies the bundled skill into a repository-local directory and writes
a `.kast-version` marker so rerunning the same CLI version can skip a no-op
install.

1. From the workspace root, run:

   ```bash
   kast install skill
   ```

2. Let the command choose the default location from the directories already
   present in the current directory:

   - `.agents/skills/kast`
   - `.github/skills/kast`
   - `.claude/skills/kast`

3. If the same CLI version is already installed in the selected target, read
   the JSON result and confirm that `skipped` is `true`.

4. If you need a non-default path or want to replace an existing install,
   pass explicit options:

   ```bash
   kast install skill --target-dir=/absolute/path/to/skills --yes=true
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

## Install the IntelliJ plugin

If you use IntelliJ IDEA and prefer the plugin backend, you can install the
Kast plugin using the unified installer or manually from a release asset. The
plugin starts a Kast analysis server automatically when IntelliJ opens a
project.

**Option A: Unified installer**

Use the `--components` flag to include the IntelliJ plugin:

```bash
./install.sh --components=intellij
```

Or install everything at once:

```bash
./install.sh --components=all
```

The installer downloads the plugin zip and places it in
`$KAST_INSTALL_ROOT/plugins/`. Then install it from disk in IntelliJ.

**Option B: Manual install**

1. Download the `kast-intellij-<version>.zip` file from the
   [latest GitHub release](https://github.com/amichne/kast/releases/latest).

2. In IntelliJ IDEA, open **Settings → Plugins → ⚙️ → Install Plugin from
   Disk** and select the downloaded zip file.

3. Restart IntelliJ IDEA when prompted.

4. Open a Kotlin project. The plugin starts a Kast server on a Unix domain
   socket and writes a descriptor file so external clients can discover it.

5. Verify the plugin is running by connecting any JSON-RPC client to the
   socket path written in the descriptor file under `~/.config/kast/daemons/`.

!!! note
    The IntelliJ plugin backend does not require the standalone CLI to be
    installed. It reuses the IDE's own K2 analysis session and project model.
    If you also want CLI access, install the standalone CLI separately and
    it will detect and connect to the running IntelliJ backend automatically.

To disable the plugin temporarily without uninstalling it, set the
`KAST_INTELLIJ_DISABLE` environment variable before launching IntelliJ.

## Optional: prewarm a workspace runtime

Analysis commands can start the daemon on demand, so you do not need
`workspace ensure` before every session. Use it when you want a separate
startup step or when you want to block for `READY` before the first query.

1. Run the command with an absolute workspace path:

   ```bash
   kast \
     workspace ensure \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Read the JSON result on stdout for the selected runtime metadata.
3. Read the daemon note on stderr to see whether Kast started a daemon or
    reused one that was already ready.
4. Optional: Pass `--accept-indexing=true` when you only need a servable daemon
   and can tolerate `INDEXING` while background enrichment finishes.

## Verify or reuse the workspace runtime

If you skipped the explicit prewarm step, `capabilities` can be your first
runtime-dependent command. It auto-starts the daemon when needed and returns
once the daemon is servable.

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
operations the current runtime supports. When `workspace status` reports
`INDEXING`, semantic commands can still return partial or empty results until
the state becomes `READY`.

## Stop the runtime when you are done

Stop the workspace daemon when you no longer need it. This keeps the lifecycle
explicit and makes it easy to tell which workspace runtimes are active.

1. Stop the daemon for the workspace:

   ```bash
   kast \
     workspace stop \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Read stderr for the stop note when a daemon was actually stopped.

## Recover workspace state manually

Kast refreshes `apply-edits` results immediately and watches source roots for
most external `.kt` file changes. Use `workspace refresh` only when you need a
manual recovery path after a missed change.

1. Refresh the full workspace state:

   ```bash
   kast \
     workspace refresh \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Optional: Refresh only a targeted set of Kotlin files:

   ```bash
   kast \
     workspace refresh \
     --workspace-root=/absolute/path/to/workspace \
     --file-paths=/absolute/path/to/src/main/kotlin/example/App.kt,/absolute/path/to/src/main/kotlin/example/Use.kt
   ```

3. Read the JSON result on stdout for the refreshed and removed file paths.

## Troubleshooting first-run issues

The first failure usually comes from one of a small set of environment or usage
mistakes.

- If the installer fails immediately, confirm that Java 21 or newer is
  installed and your operating system is supported by the published bundle.
- If `kast install skill` reports that the target already exists, rerun with
  `--yes=true` when you intend to replace that install.
- If Kast reports a usage error, rewrite the command so every option uses the
  `--key=value` form.
- If Kast cannot find the workspace or a file, convert the path to an absolute
  path and rerun the command.
- If you want runtime-dependent commands to fail instead of starting a daemon
  implicitly, add `--no-auto-start=true` to the command.
- If the daemon misses an external `.kt` file change, run
  `kast workspace refresh --workspace-root=/absolute/path/to/workspace` to
  force a rescan. Add `--file-paths=...` when you want a targeted refresh.
- If you want the shell-specific completion help pages, run
  `kast help completion`.
- If the packaged skill or repo-local resolver cannot find `kast` on your
  `PATH`, you can point it directly at an explicit launcher or binary with
  `KAST_CLI_PATH`:

   ```bash
   export KAST_CLI_PATH=/absolute/path/to/kast
   kast workspace status --workspace-root=/absolute/path/to/workspace
   ```

- If you have a local checkout of the repository and want the packaged skill or
  repo-local resolver to use a locally-built launcher, set `KAST_SOURCE_ROOT`
  to the repo root. The resolver looks for `kast/build/scripts/kast` first,
  then `dist/kast/kast`, and may run `./gradlew :kast:writeWrapperScript` when
  Java 21+ is available. If you want the repo-local portable layout under
  `dist/kast`, run `./build.sh` from the repo root first:

   ```bash
   export KAST_SOURCE_ROOT=/absolute/path/to/kast/repo
   kast workspace ensure --workspace-root=/absolute/path/to/workspace
   ```

## Next steps

Once the workspace runtime is live, you can move on to the common analysis
tasks and the compact reference page.

- [Run analysis commands](run-analysis-commands.md)
- [Command reference](command-reference.md)
