# Kast

[![DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

Kast is a Kotlin analysis tool for real Kotlin workspaces. The current
supported operator path is the repo-local `kast` command.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, JSON-RPC models, descriptor discovery,
  standalone options, errors, and edit validation
- `kast-cli`: operator-facing CLI control plane, wrapper packaging, portable
  distribution layout, and the native-image entrypoint
- `analysis-server`: request dispatch and daemon transport plumbing
- `backend-standalone`: standalone runtime entrypoint plus Kotlin Analysis API
  integration
- `shared-testing`: fake backend fixtures used by server and backend tests

## Install the published CLI

Kast publishes portable release zips for supported operating systems. Install
the latest release from any shell with a copyable one-line command:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)"
```

Install with all components (standalone + IntelliJ plugin):

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)" -- --components=all
```

Non-interactive install for CI/automation:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/install.sh)" -- --components=all --non-interactive
```

That installs `kast` into your user-local bin directory and adds that directory
to your shell `PATH` when needed. If you already have this repository checked
out, you can run `./install.sh` from the repo root instead.

> **Note:** The published bundle installs a launcher plus a colocated native
> client under `bin/kast`. Daemon-backed commands still launch the JVM backend
> from bundled `runtime-libs`, so Java 21 or newer remains required.

If you use an agent workflow, run `kast install skill` from the workspace root
to copy a version-matched `kast` skill into that workspace:

```bash
kast install skill
```

It defaults to `.agents/skills/kast`, `.github/skills/kast`, or
`.claude/skills/kast` based on which directories already exist in the current
directory. Pass `--target-dir=/absolute/path/to/skills` to override the
location and `--yes=true` to replace an existing install. Each installed skill
tree includes a `.kast-version` marker, so rerunning the same CLI version can
skip a no-op install safely.

## Local/dev builds

For local iteration, use `build.sh` from the repo root. It stages the portable
layout under `dist/kast`, including the `kast` launcher, the native client in
`dist/kast/bin/kast`, and the JVM fallback runtime libs. It also copies the
portable zip to `dist/kast.zip` and can install the result as a named
side-by-side dev instance when the build finishes.

```bash
./build.sh
./build.sh --no-install
./build.sh --install --instance my-dev
```

If you accept the install prompt or pass `--install`, the script installs into
`~/.local/share/kast/instances/my-dev` and creates `~/.local/bin/kast-my-dev`
(it does not edit your `PATH`). If you omit `--instance`, the installer
generates a default name like `agile-otter`.

Run smoke validation for a named instance with:

```bash
./scripts/validate-instance.sh my-dev
```

## How to use it

Use `kast --help` for the grouped command overview before you move into the
JSON-returning commands. Analysis commands auto-start the standalone daemon when
needed. Use `workspace ensure` when you want an explicit prewarm step or a
separate readiness check.

Optional: prewarm a runtime for a workspace:

```bash
kast \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

By default, `workspace ensure` waits for `READY`. Add
`--accept-indexing=true` when you only need a servable daemon and can tolerate
`INDEXING` while background enrichment finishes.

Run analysis commands the same way. The first one can start the daemon for you
when you skip `workspace ensure`:

```bash
kast \
  capabilities \
  --workspace-root=/absolute/path/to/workspace

kast \
  call-hierarchy \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --direction=incoming

kast \
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

If you want a runtime-dependent command to fail instead of auto-starting a
daemon, add `--no-auto-start=true`.

Kast refreshes `apply-edits` results immediately and watches source roots for
most external `.kt` file changes. If you need to force recovery after a missed
change, run:

```bash
kast \
  workspace refresh \
  --workspace-root=/absolute/path/to/workspace
```

Stop the daemon when you need to:

```bash
kast \
  workspace stop \
  --workspace-root=/absolute/path/to/workspace
```

Successful commands print JSON on stdout. Daemon lifecycle notes go to stderr,
including successful auto-start and reuse paths.

`call-hierarchy` is available through the public CLI and returns bounded trees
with traversal stats plus truncation metadata.

## Try it on your code

`kast demo` runs an interactive comparison of grep-based text search versus
kast's semantic analysis on your own workspace. It picks a symbol with the
built-in terminal chooser (or via `--symbol`), shows what grep gets wrong
(comments, strings, substring collisions), and then runs the standalone daemon's
resolve, references, rename (dry-run), and call-hierarchy flow to show the
difference.

Interactive mode:

```bash
kast demo --workspace-root=/path/to/your/kotlin/project
```

Non-interactive (skip the picker):

```bash
kast demo --workspace-root=/path/to/your/kotlin/project --symbol=YourClassName
```

## Optional: enable shell completion

The installer can offer to enable completion in your shell init file. If you
skip that prompt or want to enable it later, Kast can still print opt-in
completion scripts for the public command tree and the supported
`--key=value` options.

Bash:

```bash
source <(kast completion bash)
```

Zsh:

```bash
source <(kast completion zsh)
```

Run `kast help completion` if you want the shell-specific command pages.

## Build from source

If you are changing Kast itself, `./build.sh` stages the full portable layout
from the repo root:

```bash
./build.sh
```

If you only need the shared native client while working on `kast-cli`, run:

```bash
./gradlew :kast-cli:nativeCompile
```

The executable is written to `kast-cli/build/native/nativeCompile/kast`.
