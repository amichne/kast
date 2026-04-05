# Kast

Kast is a Kotlin analysis tool for real Kotlin workspaces. The current
supported operator path is the repo-local `kast` command.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, JSON-RPC models, descriptor discovery,
  standalone options, errors, and edit validation
- `kast-cli`: shared operator-facing CLI control plane that builds as the
  native client and also backs the JVM shell
- `kast`: wrapper packaging, portable distribution layout, and the JVM-only
  shell for `internal daemon-run`
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

That installs `kast` into your user-local bin directory and adds that directory
to your shell `PATH` when needed. If you already have this repository checked
out, you can run `./install.sh` from the repo root instead.

> **Note:** The published bundle installs a launcher plus a colocated native
> client under `bin/kast`. Daemon-backed commands still launch the JVM backend
> from bundled `runtime-libs`, so Java 21 or newer remains required.

The installer also registers `kast-skilled`. Run it from a workspace root to
create a `kast` skill symlink without copying the skill contents:

```bash
kast-skilled
```

It prompts before linking and defaults to `.agents/skills/kast`,
`.github/skills/kast`, or `.claude/skills/kast` based on which directories
already exist in the current directory. All installed links point back to the
single packaged skill root from `KAST_SKILL_PATH`, which the launcher defaults
to the installed release copy unless you override it.

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
JSON-returning commands.

Start or reuse a runtime for a workspace:

```bash
kast \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

Run analysis commands the same way:

```bash
kast \
  capabilities \
  --workspace-root=/absolute/path/to/workspace

kast \
  call hierarchy \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --direction=incoming

kast \
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

Kast refreshes `edits apply` results immediately and watches source roots for
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
  daemon stop \
  --workspace-root=/absolute/path/to/workspace
```

Successful commands print JSON on stdout. Daemon lifecycle notes go to stderr.

`call hierarchy` is available through the public CLI and returns bounded trees
with traversal stats plus truncation metadata.

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
