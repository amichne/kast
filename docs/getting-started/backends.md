---
title: Backends
description: Understand standalone vs IntelliJ plugin — when to use each,
  how they compare, and how to choose.
icon: lucide/server
---

# Backends

`kast` supports two fully independent runtime modes for Kotlin analysis.
Both backends expose the same JSON-RPC contract, but they start
differently, depend on different runtime state, and give you different
operational advantages.

## Compare the two runtime modes

Use this table to decide which runtime mode matches your environment.

| Runtime mode | What runs | Best for | What you gain | How it starts |
|------|-----------|----------|---------------|---------------|
| Standalone CLI + daemon | `kast` CLI plus a separate JVM daemon | Terminal work, CI, agents, or machines without IntelliJ | A self-managed headless path with explicit lifecycle control | The CLI starts or reuses the daemon |
| IntelliJ plugin-backed runtime | A `kast` server inside an open IntelliJ project | Local work when IntelliJ is already open | Reuse IntelliJ's already-open project model and indexes without a second analysis JVM | The plugin starts when IntelliJ opens the project |

## Standalone backend

The standalone backend runs as an independent JVM process outside the
IDE. You start it explicitly with `kast daemon start`, and it keeps
semantic state warm for subsequent `kast` commands.

Use this path when you want:

- Terminal workflows and shell scripts
- CI pipelines where no IDE is running
- LLM agents operating headless
- Any machine where IntelliJ is not installed

What you gain from this path is operational independence. You can run
the same compiler-backed queries anywhere Java 21 is available, even if
no editor is open.

Install the standalone backend from releases with:

```console title="Install the standalone backend"
./kast.sh install --components=backend
```

This places the standalone backend in your install root. You
can also install the CLI and standalone backend together:

```console title="Install CLI and standalone backend"
./kast.sh install --components=cli,backend --non-interactive
```

After installation, run `kast config init` and set
`backends.standalone.runtimeLibsDir` to the installed `runtime-libs`
directory. You can also pass `--runtime-libs-dir` to `kast daemon start`
for one-off runs.

The standalone backend works like this:

1. You run `kast daemon start --workspace-root=<path>` in a terminal or
   background process. It starts, discovers the project, and prints
   `READY` when the analysis session is warm.
2. You run `kast` commands from another shell, targeting the same
   workspace root. The CLI finds the running backend automatically.
3. The backend stays alive and warm. Later commands reuse the warm
   session without a cold-start penalty.

For workspace discovery, `kast` treats the workspace root as
Gradle-aware when it contains `settings.gradle.kts`, `settings.gradle`,
`build.gradle.kts`, or `build.gradle`. In that case, it uses Gradle
discovery for modules, source roots, and classpath information. Without
those files, it falls back to conventional source roots such as
`src/main/kotlin`, `src/main/java`, `src/test/kotlin`, and
`src/test/java`, then scans for directories that contain `.kt`, `.kts`,
or `.java` files.

> **Note:** A root `settings.gradle.kts` or `settings.gradle` matters
> most when you want accurate multi-module Gradle discovery.

## IntelliJ plugin backend

The IntelliJ plugin backend runs inside a running IntelliJ IDEA
instance. It piggybacks on the IDE's already-open K2 analysis
session, project model, and indexes.

Use this path when:

- You already have IntelliJ open on the project
- You want `kast` analysis without a second JVM process
- You want the IDE's richer project model and index state

What you gain from this path is reuse. `kast` does not need to build a
second analysis environment because IntelliJ already has the workspace
open and indexed.

The IntelliJ plugin backend works like this:

1. IntelliJ opens a project.
2. The plugin starts a `kast` server automatically on a Unix domain
   socket.
3. It writes a descriptor file so external clients can discover the
   socket path.
4. External tools connect through the socket and get the same JSON-RPC
   interface.

!!! tip
    To disable the plugin without uninstalling it, set
    `backends.intellij.enabled = false` in your Kast `config.toml`.

## Capability surface

Both backends advertise the same capability surface today. Use
`kast capabilities` when you want to confirm what a specific running
backend supports.

## How the CLI chooses

When you run a `kast` command without `--backend-name`, the CLI uses
these rules:

1. If a servable IntelliJ backend is already running for that workspace,
   the CLI prefers it.
2. Otherwise, if a servable standalone backend exists
   for that workspace, the CLI reuses it.
3. Otherwise, the CLI returns an error: no backend is available.

The CLI never starts a backend for you. You must run `kast daemon start`
yourself (or have IntelliJ open with the plugin) before running analysis
commands.

`kast workspace status` reports the current backend state and helps
diagnose connection problems. To use the standalone path, start the
backend with `kast daemon start --workspace-root=<path>` before running
commands. To use the plugin path, open the project in IntelliJ IDEA with
the plugin installed.

## Using both

You can install and use both paths for the same workspace. This is often
the most practical setup: the standalone path covers headless work, and
the IntelliJ path is ready when the IDE is already open.

If both backends are available, pin the command with
`--backend-name=standalone` or `--backend-name=intellij` when you want
an explicit choice.

## Next steps

- [How Kast works](../architecture/how-it-works.md) — the full
  architecture story
- [Quickstart](quickstart.md) — run your first analysis command
