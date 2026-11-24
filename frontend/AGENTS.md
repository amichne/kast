---
type: Module Guide
title: "Kast frontend"
description: "Owns the Go Control executable: public queries through the persistent coordinator, reset, and every other entry on the JVM."
resource: "repo://frontend"
tags: [kast, frontend, coordinator]
timestamp: "2026-11-23T00:00:00Z"
source_revision: 402240fd87e8cf55254ddec55f6f02a809e4101c
code_sources:
  - path: frontend/cmd/kast/main.go
  - path: frontend/internal/frontend/catalog.go
  - path: frontend/internal/frontend/control.go
  - path: frontend/internal/frontend/invocation.go
  - path: frontend/internal/frontend/run.go
  - path: frontend/internal/reset/backup.go
  - path: frontend/internal/reset/paths.go
  - path: frontend/internal/reset/reset.go
  - path: frontend/go.mod
  - path: frontend/check
  - path: frontend/acceptance
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/PublicInvocationProtocol.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/PublicInvocationOperations.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/CoordinatorPublicInvocations.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicFrontendCatalog.kt
---

# Frontend

## Purpose

The Go binary is the installed Control executable at `share/kast/libexec/kast-management`, and the public `kast` command is its copy.
It serves the public query commands through the persistent coordinator and owns bootstrap, transport, signals, output delivery, and process status for them.
It also owns `kast reset`, which returns a damaged installation to "nothing installed" without a record, a runtime, or a JVM.
It selects and downloads published builds for `kast install`, without changing an installation.
Every other invocation replaces the process with `KastControlMain` on the Host's Java runtime, from the installation's `lib` class path.
The coordinator and the JVM entry own lowering, defaults, workspace decisions, semantic admission, and presentation.
Read the [tracer evidence](../docs/reviews/go-frontend-tracer.md) for the measured baseline.

## Rules

- Keep the frontend free of `cgo`. Build it with `CGO_ENABLED=0`, outside Gradle and the Kotlin module graph.
- `go.mod` owns the Go release through its `toolchain` line and the analyzers through its `tool` lines.
  The `mise.toml` Go entry only provisions a matching `go` command.
- Keep the bootstrap executables a function of the module's bytes and the `toolchain` line. `install.sh` pins their digests.
- Forward the argument vector, working directory, and environment unchanged. Never lower flags or model requests in Go.
- Route only the public query commands to the coordinator. `hook` and `workspace` read process-local state, so they run on the JVM.
- Run the JVM by `exec`, so lifecycle owners observe one `java` process with the catalog's explicit class path and main class.
  Pass the invoked command as `-Dkast.command`, because the JVM cannot observe it after `exec`.
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
- Route `install` to this process only with `--dry-run` or `--download-only`. The JVM owns every other `install` vector.
- Admit each downloaded byte through one digest chain: the catalog or release mapping, the build record, then each file.
- Accept only the current build record schema. Reject an older release before any file request.
- Write only `~/.local/share/.kast-downloads`. A reset deletes that directory. A dry run writes nothing.
- Never run an installation test against the network. Inject the transport in `install_test.go`, which rejects unexpected requests.
- Draw the download view only on a terminal, on stderr. Keep stdout, pipes, `TERM=dumb` and `--json` free of control sequences.
- Keep the view without input and without signal handling. With input, Bubble Tea asks the terminal questions whose answers reach the shell.
- The view reads progress snapshots. A slow or lost terminal never delays or stops a transfer.
- After a signal, wait `installGrace` for the installation, so the view restores the cursor before the process ends.
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
- [reset.go](internal/reset/reset.go) - the reset sequence, its failures, and the supervisor and process adapters.
- [paths.go](internal/reset/paths.go) - the exact deletion targets, their owners, and deletion without links.
- [backup.go](internal/reset/backup.go) - the client configuration and workspace evidence that a reset copies first.
- [release.go](internal/install/release.go) - build selection, strict record decoding, and the files a build needs.
- [fetch.go](internal/install/fetch.go) - HTTPS transfers, resumption, digest admission, and the download directory.
- [install.go](internal/install/install.go) - the installation modes, their output, and the native adapter.
- [progress.go](internal/install/progress.go) - each file's stage and bytes, and the snapshots a view reads.
- [view.go](internal/install/view.go) - the Bubble Tea download view and its frames.
- [check](check) - the deterministic gate and the separate advisory scan.
- [acceptance](acceptance) - case matrix, comparison, latency, first callers, and cancellation evidence.

## Entry Points

- Gate: `mise exec -- ./gradlew :frontendTest`. It runs `frontend/check gate` with the generated catalog.
  The gate covers module integrity and tidiness, `gofmt`, `go vet`, `staticcheck`, race tests, and cgo-free builds for every release target.
  It also builds the bootstrap executables from two directories and compares the bytes.
- Bootstrap: `frontend/check bootstrap --output <directory>` builds the executables. The [publication procedure](../docs/development.md#bootstrap-release) releases them.
- Advisory scan: `mise exec -- uv run --locked --script frontend/check vulnerabilities`. Its result depends on the live Go advisory database.
- Inner loop: `cd frontend && go test -race ./...`. Set `KAST_FRONTEND_CATALOG` to the generated catalog for the status check.
- Control payload: `mise exec -- ./gradlew :stageKastControlProduct` stages the binary, `lib`, and `share/kast/frontend.json`.
- Coordinator route tests: `PublicInvocationEndpointTest` in `app-server` and `CoordinatorPublicInvocationsTest` in `cli`.
