---
title: How to use Kast
description: The one supported way to run Kast against a workspace.
icon: lucide/network
---

Kast has one supported path: run the repo-local `kast` command against
the workspace you want to analyze. Install the published CLI once, ensure a
runtime for the workspace, and then run analysis commands through that same
CLI.

## Install the published CLI

Install the latest published release into this checkout:

```bash
./install.sh
```

That script installs `kast` into your user-local bin directory and adds that
directory to your shell `PATH` when needed.

> **Note:** The published bundle still expects Java 21 or newer on your path or
> under `JAVA_HOME`.

## Start a workspace runtime

Start or reuse the standalone runtime for the workspace:

```bash
kast \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

That command prints JSON on stdout. If Kast starts or reuses a daemon, it also
prints a short daemon note on stderr.

## Run analysis commands

Run every supported operation through the same CLI:

```bash
kast \
  capabilities \
  --workspace-root=/absolute/path/to/workspace

kast \
  symbol resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/File.kt \
  --offset=123

kast \
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
kast \
  daemon stop \
  --workspace-root=/absolute/path/to/workspace
```

## Build an unpublished change

If you are testing code that has not been published yet, build the portable
distribution locally:

```bash
./gradlew :kast:portableDistZip
```

## Current gap

The main remaining production gap is `callHierarchy`.
