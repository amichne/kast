---
title: Backends
description: Understand standalone vs IntelliJ plugin — when to use each,
  how they compare, and how to choose.
icon: lucide/server
---

# Backends

Two ways to run the analysis engine. Both speak the same JSON-RPC, so
your scripts and prompts don't change when you switch.

## Pick the one that matches where you work

| Runtime           | What runs                            | Best for                              | How it starts                        |
|-------------------|--------------------------------------|---------------------------------------|--------------------------------------|
| Standalone        | `kast` CLI plus a JVM daemon         | Terminal, CI, agents, no-IDE machines | `kast workspace ensure`              |
| IntelliJ plugin   | A `kast` server inside an open IDE   | Local work with IntelliJ already open | Boots when IntelliJ opens the project |

## Standalone backend

A separate JVM process. You start it explicitly with
`kast workspace ensure` (or `kast daemon start` for the lower-level
control), and it stays warm for everything you run after.

Reach for it when:

- You're in a terminal, a CI runner, or an agent loop
- IntelliJ isn't installed on this machine
- You want to control the lifecycle yourself

Install:

```console title="Install the standalone backend"
./kast.sh install --components=backend
```

Or pull the CLI and the backend together:

```console title="Install CLI and standalone backend"
./kast.sh install --components=cli,backend --non-interactive
```

After install, run `kast config init` and point
`backends.standalone.runtimeLibsDir` at the installed `runtime-libs`
directory. For one-off runs, pass `--runtime-libs-dir` to
`kast daemon start`.

How a session unfolds:

1. You run `kast workspace ensure --workspace-root=$(pwd)` somewhere. It
   starts the daemon, discovers the project, and waits until the
   analysis session is warm.
2. You run more `kast` commands against the same workspace. The CLI
   finds the running backend and reuses it.
3. The daemon stays alive. No cold starts between commands.

??? info "How standalone discovers your project"

    With `settings.gradle(.kts)` or `build.gradle(.kts)` at the root,
    `kast` uses Gradle's project model — modules, source roots,
    classpath. Without those files, it falls back to conventional roots
    (`src/main/kotlin`, `src/main/java`, `src/test/kotlin`,
    `src/test/java`) and scans for directories with `.kt`, `.kts`, or
    `.java` files. The Gradle path matters most for multi-module builds.

## IntelliJ plugin backend

The plugin runs inside a running IntelliJ IDEA instance. It reuses the
IDE's K2 analysis session, project model, and indexes — no second JVM,
no second indexing pass.

Reach for it when:

- IntelliJ is already open on the project
- You'd rather not run a second analysis JVM
- You want the IDE's richer project model

How a session unfolds:

1. You open the project in IntelliJ.
2. The plugin starts a `kast` server on a Unix domain socket.
3. It drops a descriptor file so other tools can find the socket.
4. External tools connect and speak the same JSON-RPC.

!!! tip
    Set `backends.intellij.enabled = false` in `config.toml` to disable
    the plugin without uninstalling it.

## Capability surface

Today, both backends advertise the same capabilities. Run
`kast capabilities` to confirm what's supported on the backend you're
talking to.

## How the CLI picks a backend

Without `--backend-name`, the CLI uses these rules in order:

1. A servable IntelliJ backend for the workspace? Use it.
2. A servable standalone backend for the workspace? Use it.
3. Neither? Error out — no backend available.

`kast workspace ensure` is the only command that starts a backend for
you. It boots the standalone daemon and blocks until indexing finishes.
Read commands like `resolve` and `references` never start a backend
implicitly — they fail fast. So: run `workspace ensure` first, or open
the project in IntelliJ with the plugin installed.

`kast workspace status` reports backend state and helps you debug
connection issues.

## Running both

Nothing stops you from installing both. It's the most practical setup —
standalone for headless, IntelliJ for when the IDE is already open.

When both are running, pin a command with `--backend-name=standalone` or
`--backend-name=intellij` to be explicit.

## Next steps

- [Quickstart](quickstart.md) — run your first analysis command
- [Manage workspaces](../what-can-kast-do/manage-workspaces.md) —
  start, refresh, and stop backends
