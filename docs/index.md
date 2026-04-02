---
title: How to use Kast
description: The one supported way to run Kast against a workspace.
icon: lucide/network
---

Kast has one supported path: run the repo-local `analysis-cli` command against
the workspace you want to analyze. Build the CLI once, ensure a runtime for the
workspace, and then run analysis commands through that same CLI.

## Build the CLI

Build the wrapper and bundled runtime files from the repo root:

```bash
./gradlew :analysis-cli:syncRuntimeLibs :analysis-cli:writeWrapperScript
```

## Start a workspace runtime

Start or reuse the standalone runtime for the workspace:

```bash
./analysis-cli/build/scripts/analysis-cli \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

That command prints JSON on stdout. If Kast starts or reuses a daemon, it also
prints a short daemon note on stderr.

## Run analysis commands

Run every supported operation through the same CLI:

```bash
./analysis-cli/build/scripts/analysis-cli \
  capabilities \
  --workspace-root=/absolute/path/to/workspace

./analysis-cli/build/scripts/analysis-cli \
  symbol resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/File.kt \
  --offset=123

./analysis-cli/build/scripts/analysis-cli \
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

Supported commands today:

- `workspace status`
- `workspace ensure`
- `daemon start`
- `daemon stop`
- `capabilities`
- `symbol resolve`
- `references`
- `diagnostics`
- `rename`
- `edits apply`

## Stop the runtime

Stop the workspace daemon when you are done:

```bash
./analysis-cli/build/scripts/analysis-cli \
  daemon stop \
  --workspace-root=/absolute/path/to/workspace
```

## Current gap

The main remaining production gap is `callHierarchy`.
