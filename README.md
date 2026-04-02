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
the latest release into this checkout:

```bash
./install.sh
```

That installs `kast` into your user-local bin directory and adds that directory
to your shell `PATH` when needed.

> **Note:** The published bundle still expects Java 21 or newer on your path or
> under `JAVA_HOME`. `./install.sh` validates that before it unpacks the
> release.

## How to use it

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

## Build from source

If you are changing Kast itself, you can still build the CLI from source:

```bash
./gradlew :kast:portableDistZip
```
