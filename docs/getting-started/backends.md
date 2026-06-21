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
| IDEA plugin       | A `kast` server inside an open IDE   | macOS developer machines with IDEA or Android Studio | Homebrew installs the cask; the plugin boots when the IDE opens the project |
| Headless          | Linux tarball with `kast` CLI plus a packaged IDEA backend | CI, hosted agents, servers, and no-IDE Linux images | Install the Linux headless tarball, then `kast up` |

## Headless backend

A separate JVM process backed by packaged IDEA components. The Linux headless
tarball installs the CLI, backend runtime, scripts, and install manifest
together. `kast up` is the high-level entry point after that distribution is
installed.

Reach for it when:

- You're building a CI runner, hosted-agent image, or server snapshot
- The machine is Linux and should own its backend runtime
- You need a non-Homebrew distribution with explicit lifecycle control

Install the Linux headless tarball:

```console title="Install the headless backend"
./scripts/install-ubuntu-debian.sh install
```

The Ubuntu/Debian installer writes the install manifest at
`$HOME/.local/share/kast/install.json`, stages versioned files under
`$HOME/.local/share/kast/versions/<version>`, and flips
`$HOME/.local/share/kast/current` atomically. The packaged headless runtime is
resolved from that manifest, normally through
`$HOME/.local/share/kast/current/lib/backends/headless/current/runtime-libs`.
Use `kast paths` to inspect the active runtime paths. Do not put headless
runtime library paths or IDEA home paths in `config.toml`; they are
install-owned.

Warm a headless backend. The selector is optional because headless is the
default non-IDE backend:

```console title="Start the packaged headless backend"
kast up
```

How a session unfolds:

1. You run `kast up` somewhere inside the workspace. It
   starts or reuses the daemon, discovers the project, and waits until
   the analysis session is warm. If the backend is missing, `kast up`
   reports the missing Linux headless tarball installation instead of
   downloading a separate backend.
2. You run more `kast` commands against the same workspace. The CLI
   finds the running backend and reuses it.
3. The daemon stays alive. No cold starts between commands.

Add `--output json` to lifecycle commands when automation needs the full
descriptor payload instead of the readable summary.

The packaged Copilot LSP configuration starts `kast lsp --stdio`, which
auto-ensures the selected backend when the editor opens the language server.

??? info "How headless discovers your project"

    With `settings.gradle(.kts)` or `build.gradle(.kts)` at the root,
    `kast` uses Gradle's project model — modules, source roots,
    classpath. Without those files, it falls back to conventional roots
    (`src/main/kotlin`, `src/main/java`, `src/test/kotlin`,
    `src/test/java`) and scans for directories with `.kt`, `.kts`, or
    `.java` files. The Gradle path matters most for multi-module builds.

## IDEA / Android Studio plugin backend

The same plugin ZIP runs inside a running IDEA 2025.3 or Android
Studio 2025.3.1+ instance. On macOS developer machines, this plugin is part of
the functional Homebrew install. It reuses the IDE's K2 analysis session,
project model, and indexes — no second JVM, no second indexing pass.

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

### Configure IDE self-start

By default, `kast` never opens a GUI IDE for you. The plugin remains part of
the macOS developer install; this setting only controls whether `kast up
--backend=idea` or a pinned Copilot session may start IDEA when no compatible
IDE descriptor is already running:

```toml title="$HOME/.config/kast/config.toml"
[runtime]
defaultBackend = "idea"

[runtime.ideaLaunch]
enabled = true
command = "idea"
waitTimeoutMillis = 90000
requireInstalledPlugin = true
```

`command` is executed directly with the workspace root as its only argument;
set an absolute path when `idea` is not on `PATH`. When
`requireInstalledPlugin` is true, `kast` first checks JetBrains profile
directories for the Kast plugin and reports `kast install plugin` if none are
linked.

For Copilot, set `KAST_COPILOT_IDEA_AUTOSTART=1` in the extension environment
to pin startup and tool RPCs to `--backend=idea`. That flag does not launch an
IDE by itself; `runtime.ideaLaunch.enabled` must also allow GUI launch.

IDEA / Android Studio integration is installed through the Homebrew
`kast-plugin` cask. Use `kast install plugin` to repair Homebrew-managed
profile links. Inside the IDE, Kast stays focused on diagnostics and the
IDE-hosted backend instead of duplicating CLI install workflows. When IDE
runtime launch is enabled, the CLI path is resolved from the same install
manifest and stable shim used by the rest of Kast.

### Opt in to project-open profile installs

IDEA can install the repository Copilot/LSP package automatically before
backend startup when a Gradle project opens. This is disabled by default and
limited to the packaged `copilot-lsp` profile:

```toml title="$HOME/.config/kast/config.toml"
[projectOpen]
profileAutoInit = true
profile = "copilot-lsp"
autoExcludeGit = true
```

With this policy enabled, the plugin runs the configured CLI as:

```console
kast install copilot --target-dir <project>/.github
```

Set `autoExcludeGit = false` to add `--no-auto-exclude-git` to that command.
Failures are reported in IDEA but do not block the backend from starting.

Applying Kast settings in IDEA reloads the workspace config and restarts the
local Kast backend when the effective config changes. Installing or relinking
the Homebrew-managed plugin still requires restarting the IDE so JetBrains can
load the plugin from the profile link.

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
`kast capabilities` to summarize what's supported on the backend you're
talking to, or `kast --output json capabilities` for the full machine-readable
payload.

## How the CLI picks a backend

Without `--backend`, the CLI uses these rules in order:

1. A `--backend` flag, when present.
2. `[runtime] defaultBackend`, when configured as `headless` or `idea`.
3. Automatic selection.

Automatic selection prefers a servable IDEA backend, then a servable headless
backend. If neither is running, `kast up` starts the configured packaged
headless backend when the Linux headless tarball has installed one.
When the selected backend is IDEA and no compatible descriptor is running,
`kast` only opens the IDE if `runtime.ideaLaunch.enabled = true`; otherwise it
reports that IDEA is not running.

`kast status` reports backend state, selected runtime details, and actionable
next steps when no daemon is available.

Use `kast restart --backend=headless` when a headless daemon is stuck,
degraded, or no longer reachable. Restart stops every matching workspace
headless process and descriptor before starting a clean runtime. IDEA-hosted
backends run inside the IDE process; `kast restart --backend=idea` asks the
plugin backend to stop, rebuild its server and indexer, and then waits for the
new descriptor. If no compatible IDE backend is running, Kast follows the
configured `runtime.ideaLaunch` path when that launch profile is enabled.

## Running multiple runtimes

Some environments have both runtimes available, especially when testing release
artifacts or comparing server behavior with developer-machine behavior. The
developer-machine path is IDEA or Android Studio through Homebrew; the Linux
headless bundle is for CI, hosted agents, and server images.

When multiple runtimes are running, pin a command with `--backend=headless`
or `--backend=idea` to be explicit. The
`idea` backend name is the stable machine identifier for the IDE-hosted
runtime, even when the human-facing docs call it the IDEA plugin.

## Next steps

- [Quickstart](quickstart.md) — run your first analysis command
- [Manage workspaces](../what-can-kast-do/manage-workspaces.md) —
  start, refresh, and stop backends
