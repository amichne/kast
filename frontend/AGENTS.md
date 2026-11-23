---
type: Module Guide
title: "Kast frontend"
description: "Owns the Go Control executable: public queries through the persistent coordinator and every other entry on the JVM."
resource: "repo://frontend"
tags: [kast, frontend, coordinator]
timestamp: "2026-11-23T00:00:00Z"
source_revision: 80141523b63f25f64d98da433314951c94ace2f2
code_sources:
  - path: frontend/cmd/kast/main.go
  - path: frontend/internal/lifecycle/boundary_test.go
  - path: frontend/internal/lifecycle/record/records.go
  - path: frontend/internal/lifecycle/failure/failure.go
  - path: frontend/internal/lifecycle/layout/classify.go
  - path: frontend/internal/lifecycle/plan/install.go
  - path: frontend/internal/frontend/catalog.go
  - path: frontend/internal/frontend/control.go
  - path: frontend/internal/frontend/invocation.go
  - path: frontend/internal/frontend/run.go
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
Every other invocation replaces the process with `KastControlMain` on the Host's Java runtime, from the installation's `lib` class path.
The coordinator and the JVM entry own lowering, defaults, workspace decisions, semantic admission, and presentation.
Read the [tracer evidence](../docs/reviews/go-frontend-tracer.md) for the measured baseline.

## Rules

- Keep the frontend free of `cgo`. Build it with `CGO_ENABLED=0`, outside Gradle and the Kotlin module graph.
- `go.mod` owns the Go release through its `toolchain` line and the analyzers through its `tool` lines.
  The `mise.toml` Go entry only provisions a matching `go` command.
- Forward the argument vector, working directory, and environment unchanged. Never lower flags or model requests in Go.
- The Go lifecycle commands are the one exception: `upgrade`, `uninstall`, `versions`, the bootstrap, and the handoff
  entry. Go owns them, so Go parses their flags into typed selectors and operations.
  Section 1.5 of the lifecycle design in #1375 lists them. No command routes to them yet.
- Keep the lifecycle core in `internal/lifecycle` pure. Its packages import no package for files, processes, the
  network, the clock, random numbers, or the environment. `internal/lifecycle/boundary_test.go` enforces this.
  That test lists each package under `internal/lifecycle` as pure or as an effect adapter. Add each new package there.
- Decode lifecycle records strictly. A record is one object with unique members and no trailing data.
  Read `schemaVersion` first, and reject unknown members.
  Only the selection envelope ignores unknown members, so a newer envelope never breaks an older binary.
  Encode every field, including explicit nulls, in canonical compact form.
- Route only the public query commands to the coordinator. `hook` and `workspace` read process-local state, so they run on the JVM.
- Run the JVM by `exec`, so lifecycle owners observe one `java` process with the catalog's explicit class path and main class.
  Pass the invoked command as `-Dkast.command`, because the JVM cannot observe it after `exec`.
- Route JVM warnings to stderr with `-Xlog:disable -Xlog:all=warning:stderr`, so standard output carries only the entry's document.
- Pass `-XX:SharedArchiveFile` only when the catalog's `sharedArchive` is a regular file. The installer creates it with the installed runtime, and the manifest digests it.
- Dial a socket longer than the catalog's `socketAlias.maximumPathBytes` only through the alias that links to its exact directory.
- Read the Java home from the management receipt's runtime root and the catalog's platform table. Do not search `PATH` or `JAVA_HOME`.
- Answer only exact help vectors from `share/kast/frontend.json`. Forward every other vector.
- Admit the catalog versions before any other field. Ignore additive metadata within one schema version.
- Emit only the statuses the catalog declares. `bootstrapStatus` and `protocolStatus` apply before admission.
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
- [check](check) - the deterministic gate and the separate advisory scan.
- [lifecycle](internal/lifecycle) - the pure lifecycle core from stage 1 of the installation lifecycle design in
  #1375. No command calls it yet:
  - `codec` - strict JSON object admission and canonical encoding.
  - `record` - the current pointer, payload manifest, product selection at `schemaVersion` 2, build record, runtime
    receipt and manifest binding, assembly record, and retirement document.
  - `envelope` - the tolerant selection envelope and the strict handoff document.
  - `failure` - one closed failure type, with evidence sealed per code, and the separate outcome union.
  - `layout` - admission of an observed snapshot and its classification into the seven layout states.
  - `selection` - selector parsing and resolution over envelopes.
  - `plan` - each machine as a pure function from observations to typed effect intents.
- [acceptance](acceptance) - case matrix, comparison, latency, first callers, and cancellation evidence.

## Entry Points

- Gate: `mise exec -- ./gradlew :frontendTest`. It runs `frontend/check gate` with the generated catalog.
  The gate covers module integrity and tidiness, `gofmt`, `go vet`, `staticcheck`, race tests, and cgo-free builds for every release target.
- Advisory scan: `mise exec -- uv run --locked --script frontend/check vulnerabilities`. Its result depends on the live Go advisory database.
- Inner loop: `cd frontend && go test -race ./...`. Set `KAST_FRONTEND_CATALOG` to the generated catalog for the status check.
- Control payload: `mise exec -- ./gradlew :stageKastControlProduct` stages the binary, `lib`, and `share/kast/frontend.json`.
- Coordinator route tests: `PublicInvocationEndpointTest` in `app-server` and `CoordinatorPublicInvocationsTest` in `cli`.
