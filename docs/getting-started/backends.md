---
title: Backends
description: Understand the headless and IDEA plugin backends, how they compare,
  and how to choose.
icon: lucide/server
---

# Backends

Two ways to run the analysis engine. They speak the same JSON-RPC, so
your scripts and prompts don't change when you switch.

## Pick the one that matches where you work

| Runtime           | What runs                            | Best for                              | How it starts                        |
|-------------------|--------------------------------------|---------------------------------------|--------------------------------------|
| Headless          | `kast` CLI plus a packaged IntelliJ backend | Terminals, CI, agents, no-IDE machines | `kast backend install headless`, then `kast up` |
| IDEA plugin       | A `kast` server inside an open IDE   | Local work with IDEA or Android Studio already open | Boots when the IDE opens the project |

## Headless backend

A separate JVM process backed by packaged IntelliJ components. `kast up` is
the high-level entry point. It reuses a running headless backend when one
already serves the workspace, or auto-starts one from the configured runtime
libraries when it doesn't. Use `kast daemon start` only when you need the
lower-level control directly.

Reach for it when:

- You're in a terminal, a CI runner, or an agent loop
- IDEA or Android Studio isn't installed on this machine
- You want to control the lifecycle yourself

Install:

```console title="Install the headless backend"
brew tap amichne/kast
brew install kast
kast backend install headless
```

On Ubuntu/Debian x86_64 hosts where Homebrew is not available, use the offline
bundle installer. It installs the Rust CLI and one bundled backend:

```console title="Install CLI and headless backend"
./scripts/install-ubuntu-debian.sh install
```

The Ubuntu/Debian installer writes `config.toml` with the installed headless
runtime libraries under
`$HOME/.local/share/kast/ubuntu-debian/<version>/lib/backends/headless-<version>/runtime-libs`.
To use a different installation, point `backends.headless.runtimeLibsDir` at
the installed `runtime-libs` directory and `backends.headless.ideaHome` at the
installed headless IDEA home in `config.toml`, or pass
`--runtime-libs-dir` to `kast daemon start`:

```toml title="$HOME/.config/kast/config.toml"
[backends.headless]
runtimeLibsDir = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/lib/backends/headless-v1.2.3/runtime-libs"
ideaHome = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/lib/backends/headless-v1.2.3/idea-home"
```

Warm a headless backend. The selector is optional because headless is the
default non-IDE backend:

```console title="Start the packaged headless backend"
kast up --workspace-root="$PWD"
```

How a session unfolds:

1. You run `kast up --workspace-root="$PWD"` somewhere. It
   starts or reuses the daemon, discovers the project, and waits until
   the analysis session is warm. If the backend is missing, `kast up`
   reports the exact `kast backend install <backend>` command.
2. You run more `kast` commands against the same workspace. The CLI
   finds the running backend and reuses it.
3. The daemon stays alive. No cold starts between commands.

The packaged Copilot extension also runs
`kast up --accept-indexing=true` at session start, so agent
sessions often find a warm headless backend without a separate manual
bootstrap step.

??? info "How headless discovers your project"

    With `settings.gradle(.kts)` or `build.gradle(.kts)` at the root,
    `kast` uses Gradle's project model — modules, source roots,
    classpath. Without those files, it falls back to conventional roots
    (`src/main/kotlin`, `src/main/java`, `src/test/kotlin`,
    `src/test/java`) and scans for directories with `.kt`, `.kts`, or
    `.java` files. The Gradle path matters most for multi-module builds.

## IDEA / Android Studio plugin backend

The same plugin ZIP runs inside a running IntelliJ IDEA 2025.3 or Android
Studio 2025.3.1+ instance. It reuses the IDE's K2 analysis session, project
model, and indexes — no second JVM, no second indexing pass.

Reach for it when:

- IDEA or Android Studio is already open on the project
- You'd rather not run a second analysis JVM
- You want the IDE's richer project model

How a session unfolds:

1. You open the project in IDEA or Android Studio.
2. The plugin starts a `kast` server on a Unix domain socket.
3. It hydrates a configured remote source index when one is available.
4. It prepopulates the local SQLite source index from IDE PSI files.
5. It indexes resolved references while the IDE is in smart mode.
6. It drops a descriptor file so other tools can find the socket.
7. External tools connect and speak the same JSON-RPC.

!!! tip
    Set `backends.intellij.enabled = false` in `config.toml` to disable
    the plugin without uninstalling it.

The plugin actions that shell out to `kast`, including
**Tools → Kast → Install Copilot Extension** and
**Tools → Kast → Uninstall Copilot Extension**, read the executable path from
`[cli] binaryPath` in `config.toml`. They don't search `PATH`, so the value
must point at an executable CLI binary:

```toml title="$HOME/.config/kast/config.toml"
[cli]
binaryPath = "/home/alex/.local/bin/kast"
```

To hydrate a remote SQLite source index before local indexing starts, add an
`indexing.remote` block. `sourceIndexUrl` accepts `file://`, `http://`, and
`https://` URLs that point to a `source-index.db` snapshot:

```toml
[indexing.remote]
enabled = true
sourceIndexUrl = "file:///absolute/path/to/source-index.db"
```

## Capability surface

Today, both backends advertise the same capabilities. Run
`kast capabilities` to confirm what's supported on the backend you're
talking to.

## How the CLI picks a backend

Without `--backend-name`, the CLI uses these rules in order:

1. A servable IDEA backend for the workspace? Use it.
2. A servable headless backend for the workspace? Use it.
3. Neither? Error out — no backend available.

`kast up` is the only command that starts a backend for
you. It boots or reuses the selected daemon and, by default, blocks until
indexing finishes. Pass `--backend=headless` when you want to be explicit, or
omit the selector for the headless default. Pass `--accept-indexing=true`
to return as soon as the daemon is servable. Read commands like `resolve` and
`references` never start a backend implicitly — they fail fast. So: run `up`
first, or open the project in IDEA or Android Studio with the plugin installed.

`kast status` reports backend state and helps you debug
connection issues.

## Running multiple runtimes

Nothing stops you from using both runtimes. The practical setup is headless for
terminal, CI, and hosted-agent work, and IDEA or Android Studio when the IDE is
already open.

When multiple runtimes are running, pin a command with `--backend-name=headless`
or `--backend-name=intellij` to be explicit. The
`intellij` backend name is the stable machine identifier for the IDE-hosted
runtime, even when the human-facing docs call it the IDEA plugin.

## Next steps

- [Quickstart](quickstart.md) — run your first analysis command
- [Manage workspaces](../what-can-kast-do/manage-workspaces.md) —
  start, refresh, and stop backends
