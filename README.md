# Kast

Kast is a Kotlin analysis tool for real Kotlin workspaces. The current right
way to use it is the repo-local `kast` command.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, models, errors, and edit validation
- `kast`: CLI control plane for workspace status, ensure, daemon
    lifecycle, and request dispatch
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

> **Note:** The published bundle still expects Java 21 or newer on your path or
> under `JAVA_HOME`. The installer validates that before it unpacks the
> release.

## Local/dev instance installs

For local iteration, keep using `install.sh` for end-user release installs and
use the dedicated dev installer for named side-by-side instances:

```bash
./scripts/install-instance.sh --instance my-dev
./scripts/install-instance.sh
```

That installs into `~/.local/share/kast/instances/my-dev` and creates
`~/.local/bin/kast-my-dev` (it does not edit your `PATH`). If you omit
`--instance`, the script generates a default name like
`agile-otter`.

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
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

Stop the daemon when you need to:

```bash
kast \
  daemon stop \
  --workspace-root=/absolute/path/to/workspace
```

Successful commands print JSON on stdout. Daemon lifecycle notes go to stderr.

The main remaining production gap is `callHierarchy`.

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

If you are changing Kast itself, you can still build the CLI from source:

```bash
./gradlew :kast:portableDistZip
```
