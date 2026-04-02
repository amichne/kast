# Kast

Kast is a Kotlin analysis tool for real Kotlin workspaces. The current right
way to use it is the repo-local `analysis-cli` command.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, models, errors, and edit validation
- `analysis-cli`: CLI control plane for workspace status, ensure, daemon
    lifecycle, and request dispatch
- `analysis-server`: request dispatch and daemon transport plumbing
- `backend-standalone`: standalone runtime entrypoint plus Kotlin Analysis API
    integration
- `shared-testing`: fake backend fixtures used by server and backend tests

## How to use it

Build the CLI from the repo root:

```bash
./gradlew :analysis-cli:syncRuntimeLibs :analysis-cli:writeWrapperScript
```

Start or reuse a runtime for a workspace:

```bash
./analysis-cli/build/scripts/analysis-cli \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

Run analysis commands the same way:

```bash
./analysis-cli/build/scripts/analysis-cli \
  capabilities \
  --workspace-root=/absolute/path/to/workspace

./analysis-cli/build/scripts/analysis-cli \
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

Stop the daemon when you need to:

```bash
./analysis-cli/build/scripts/analysis-cli \
  daemon stop \
  --workspace-root=/absolute/path/to/workspace
```

Successful commands print JSON on stdout. Daemon lifecycle notes go to stderr.

The main remaining production gap is `callHierarchy`.
