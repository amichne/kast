---
type: Module Guide
title: "Kast manager"
description: "Owns the Go Control executable: public queries through the persistent coordinator, reset, and every other entry on the JVM."
resource: "repo://manager"
tags: [kast, manager, frontend, coordinator]
timestamp: "2026-11-27T00:00:00Z"
source_revision: dfcd16821b1a90cd9d70a038c06d639fad6344d8
code_sources:
  - path: manager/cmd/kast/main.go
  - path: manager/internal/agent/agent.go
  - path: manager/internal/agent/harness.go
  - path: manager/internal/frontend/catalog.go
  - path: manager/internal/frontend/control.go
  - path: manager/internal/frontend/invocation.go
  - path: manager/internal/frontend/run.go
  - path: manager/internal/reset/backup.go
  - path: manager/internal/reset/paths.go
  - path: manager/internal/reset/reset.go
  - path: manager/go.mod
  - path: manager/check
  - path: manager/acceptance
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/PublicInvocationProtocol.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/PublicInvocationOperations.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/CoordinatorPublicInvocations.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicFrontendCatalog.kt
---

# Manager

## Purpose

This directory is the repository's only Go module. Its module path and the `internal/frontend` package keep the earlier name,
because a module path change alters the bootstrap bytes that `install.sh` pins.
The Go binary is the installed Control executable at `share/kast/libexec/kast-management`, and the public `kast` command is its copy.
It serves the public query commands through the persistent coordinator and owns bootstrap, transport, signals, output delivery, and process status for them.
It also owns `kast reset`, which returns a damaged installation to "nothing installed" without a record, a runtime, or a JVM.
It selects, downloads, verifies and installs published builds for `kast install` and `kast upgrade`, and owns `kast uninstall`.
An activation writes the layout the Python engine wrote: the same records, runtime store entry and payload manifest, without the private Python.
The outgoing build stops its own workspace Hosts through `--internal-install managed-retire`.
The installed Python engine only finishes an upgrade that it recorded in `recovery/replacement`.
It connects Claude Code, Codex, GitHub Copilot, and Pi to the installed plugin for `kast agent`, through each agent's own commands.
Every other invocation replaces the process with `KastControlMain` on the Host's Java runtime, from the installation's `lib` class path.
The coordinator and the JVM entry own lowering, defaults, workspace decisions, semantic admission, and presentation.
Read the [tracer evidence](../docs/reviews/go-frontend-tracer.md) for the measured baseline.

## Rules

- Keep the frontend free of `cgo`. Build it with `CGO_ENABLED=0`, outside Gradle and the Kotlin module graph.
- `go.mod` owns the Go release through its `toolchain` line and the analyzers through its `tool` lines.
  The `mise.toml` Go entry only provisions a matching `go` command.
- Keep the bootstrap executables a function of the module's bytes and the `toolchain` line. `install.sh` pins their digests.
- After a dependency or toolchain change, run `mise run frontend-notices`. The gate rejects `THIRD_PARTY_NOTICES.txt` when it differs from the packages in the executable.
  That file covers only the Go executable. It does not cover the JVM libraries of a product.
- Forward the argument vector, working directory, and environment unchanged. Never lower flags or model requests in Go.
- Route only the public query commands to the coordinator. `hook` and `workspace` read process-local state, so they run on the JVM.
- Run the JVM by `exec`, so lifecycle owners observe one `java` process with the catalog's explicit class path and main class.
  Pass the invoked command as `-Dkast.command`, because the JVM cannot observe it after `exec`.
- When stdout is a terminal and the caller names no format, present results as an outline. `--json`, `--jq`, and a set output setting keep the exact document.
  A pipe always receives the exact document.
- Run the JVM as a child only for a presented command in `childServed`, and to settle Hosts for an uninstall. Never run `hook` or a private entry as a child.
- Verify every installed payload file before an upgrade runs any of it. Anything unexpected stops and names `kast reset`.
- Verify each composed runtime file and each extracted Control file against the admitted record before any effect on the installation.
- Unpack and train beside the management root. Retire the installed build only after the new one is complete, then switch by two renames.
- After retirement begins, finish the switch. Before it, a signal stops the activation and leaves the installation untouched.
- Hold `management.lock` and `activation.lock` only to recover leftovers and to replace, never during a download.
- Recover an interrupted activation by its leftover names, which `kast reset` also removes. Undo a switch that lost its installation.
- Let the Python engine finish only its own journal. After a signal, ask it to stop once and wait for it; never kill it.
- An uninstall settles every Host before it stops a process, and refuses an unfinished upgrade or an entry that Kast did not write.
- Never run an activation or uninstall test against a real engine, Host, or home. Script them in `upgrade_test.go` and `uninstall_test.go`.
- Read `host-progress.json` only to draw a progress line on stderr. `HostProgressRecord` derives the phase; never derive it in Go.
- Replace control characters in presented values, so a result cannot drive the terminal.
- Route JVM warnings to stderr with `-Xlog:disable -Xlog:all=warning:stderr`, so standard output carries only the entry's document.
- Pass `-XX:SharedArchiveFile` only when the catalog's `sharedArchive` is a regular file. The installer creates it with the installed runtime, and the manifest digests it.
- Dial a socket longer than the catalog's `socketAlias.maximumPathBytes` only through the alias that links to its exact directory.
- Read the Java home from the management receipt's runtime root and the catalog's platform table. Do not search `PATH` or `JAVA_HOME`.
- Answer only exact help vectors from `share/kast/frontend.json`. Forward every other vector.
- Admit the catalog versions before any other field. Ignore additive metadata within one schema version.
- Emit only the statuses the catalog declares. The fixed statuses in `catalog.go` apply before admission and to a reset.
- Route `reset` before any read of the installation. It must never need a record, the catalog, or the runtime.
- Delete only names that a cited Kast source writes. Select jobs by label and program path, and processes by executable path.
- Delete the public command last, so that an interrupted reset can run again.
- Never run a reset test against a real home or supervisor. Inject the adapters in `reset_test.go`, which reject unexpected effects.
- Route `install` to this process only with a mode, a build selector, or `--json`. The JVM owns every other `install` vector.
- Admit each downloaded byte through one digest chain: the catalog or release mapping, the build record, then each file.
- Accept only the current build record schema. Reject an older release before any file request.
- Planning writes nothing, and a download writes only `~/.local/share/.kast-downloads`. A reset deletes that directory.
- Never run an installation test against the network. Inject the transport in `install_test.go`, which rejects unexpected requests.
- Draw the download view only on a terminal, on stderr. Keep stdout, pipes, `TERM=dumb` and `--json` free of control sequences.
- Keep the view without input and without signal handling. With input, Bubble Tea asks the terminal questions whose answers reach the shell.
- The view reads progress snapshots. A slow or lost terminal never delays or stops a transfer.
- After a signal, wait `installGrace` for the installation, so the view restores the cursor before the process ends.
- Change agent configuration only through the agent's own commands. Never write its files.
- Read an agent's lists before an effect on it. An unreadable or absent list is a failure, never an empty one.
- An installation replaces any marketplace, plugin, or MCP server named `kast`. A removal rejects a marketplace whose path is not a Control layout.
- Report success only when the agent then names this layout at this version.
- A refresh changes only an agent that already has a Control layout. `kast upgrade` and the installer run it.
- Without agent names, use every agent on the `PATH`. Ask first when a terminal can answer. An end of input selects nothing.
- Never run an agent test against a real agent. Script the commands in `agent_test.go`, which rejects unexpected ones.
- Read stdin only after `input_required`, and never beyond its stated bound.
  The coordinator cancels an invocation whose requested input misses `PublicInvocationProtocol.inputLimit` with `INPUT_EXPIRED`.
- Bound the connection handshake by the catalog's `handshakeMilliseconds`. Expiry precedes the request, so it is unreachable.
- Answer local help only under an absent or admitted output setting. Forward any other value for the coordinator's rejection.
- A lost connection after the request is an unknown outcome. Never retry an invocation.
- The first terminal event owns the status. A signal before or during delivery wins, even while stdin or stdout blocks.

## Key Files

- [main.go](cmd/kast/main.go) - process state passed explicitly to `frontend.Run`.
- [catalog.go](internal/frontend/catalog.go) - version-first metadata admission and exact help lookup.
- [invocation.go](internal/frontend/invocation.go) - the typed envelope and the private-socket trust check.
- [run.go](internal/frontend/run.go) - start or join, status mapping, signals, and delivery.
- [control.go](internal/frontend/control.go) - installation location, entry selection, and the JVM command.
- [interactive.go](internal/frontend/interactive.go) - when a person reads a result, and the JVM commands that run as a child.
- [document.go](internal/terminal/document.go) - the outline of one JSON document.
- [lines.go](internal/terminal/lines.go) - JSON lines among other text.
- [progress.go](internal/terminal/progress.go) - the preparation progress line of the nearest workspace.
- [reset.go](internal/reset/reset.go) - the reset sequence, its failures, and the supervisor and process adapters.
- [paths.go](internal/reset/paths.go) - the exact deletion targets, their owners, and deletion without links.
- [uninstall.go](internal/reset/uninstall.go) - what an uninstall refuses, and its order.
- [upgrade.go](internal/install/upgrade.go) - build choice, the activation's order, and the result.
- [activate.go](internal/install/activate.go) - locks, leftover recovery, retirement, the switch, and the receipts.
- [runtime.go](internal/install/runtime.go) - runtime composition from its two archives, and the store's records.
- [control.go](internal/install/control.go) - the Control payload, kept configuration, class-data training, and the manifest.
- [payload.go](internal/install/payload.go) - proof of the installed files.
- [stages.go](internal/install/stages.go) - stages and results as words.
- [backup.go](internal/reset/backup.go) - the client configuration and workspace evidence that a reset copies first.
- [agent.go](internal/agent/agent.go) - ownership, selection, and the install, remove, and refresh sequences.
- [harness.go](internal/agent/harness.go) - each agent's vocabulary and reported state.
- [native.go](internal/agent/native.go) - the terminal question and bounded agent commands without input.
- [release.go](internal/install/release.go) - build selection, strict record decoding, and the files a build needs.
- [fetch.go](internal/install/fetch.go) - HTTPS transfers, resumption, digest admission, and the download directory.
- [install.go](internal/install/install.go) - the installation modes, their output, and the native adapter.
- [progress.go](internal/install/progress.go) - each file's stage and bytes, and the snapshots a view reads.
- [view.go](internal/install/view.go) - the Bubble Tea download view and its frames.
- [check](check) - the deterministic gate and the separate advisory scan.
- [acceptance](acceptance) - case matrix, comparison, latency, first callers, and cancellation evidence.

## Entry Points

- Gate: `mise run frontend`. It runs `manager/check gate` with the generated catalog.
  The gate covers module integrity and tidiness, `gofmt`, `go vet`, `staticcheck`, race tests, and cgo-free builds for every release target.
  It also builds the bootstrap executables from two directories and compares the bytes.
- Bootstrap: `mise run bootstrap:build --output <directory>` builds the executables. The [publication procedure](../docs/development.md#bootstrap-release) releases them.
- Advisory scan: `mise run ci:frontend-vulnerabilities`. Its result depends on the live Go advisory database.
- Inner loop: `cd manager && go test -race ./...`. Set `KAST_FRONTEND_CATALOG` to the generated catalog for the status check.
- Control payload: `mise run gradle :stageKastControlProduct` stages the binary, `lib`, and `share/kast/frontend.json`.
- Coordinator route tests: `PublicInvocationEndpointTest` in `app-server` and `CoordinatorPublicInvocationsTest` in `cli`.
