---
type: Module Guide
title: "Kast frontend"
description: "Owns the Go command-line frontend for the persistent coordinator, its staging, and its acceptance."
resource: "repo://frontend"
tags: [kast, frontend, coordinator]
timestamp: "2026-11-18T00:00:00Z"
source_revision: cf067e5fcdbc0de5db077e101d50f0c287edd4fd
code_sources:
  - path: frontend/cmd/kast/main.go
  - path: frontend/internal/frontend/catalog.go
  - path: frontend/internal/frontend/invocation.go
  - path: frontend/internal/frontend/run.go
  - path: frontend/stage
  - path: frontend/acceptance
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/PublicInvocationProtocol.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/PublicInvocationOperations.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastCoordinatorMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/CoordinatorPublicInvocations.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicFrontendCatalog.kt
---

# Frontend

## Purpose

The Go frontend owns bootstrap, transport, signals, output delivery, and process status for public commands.
The coordinator owns lowering, defaults, workspace decisions, semantic admission, and presentation.
This is a tracer increment. Native Image remains the shipped product. Read the [tracer evidence](../docs/reviews/go-frontend-tracer.md).

## Rules

- Keep the frontend free of `cgo`. Build it with `CGO_ENABLED=0`, outside Gradle and the Kotlin module graph.
- Forward the argument vector, working directory, and environment unchanged. Never lower flags or model requests in Go.
- Answer only exact help vectors from `share/kast/frontend.json`. Forward every other vector.
- Admit the catalog versions before any other field. Ignore additive metadata within one schema version.
- Emit only the statuses the catalog declares. `bootstrapStatus` and `protocolStatus` apply before admission.
- Read stdin only after `input_required`, and never beyond its stated bound.
- A lost connection after the request is an unknown outcome. Never retry an invocation.
- The first terminal event owns the status. A signal before or during delivery wins.

## Key Files

- [main.go](cmd/kast/main.go) - process state passed explicitly to `frontend.Run`.
- [catalog.go](internal/frontend/catalog.go) - version-first metadata admission and exact help lookup.
- [invocation.go](internal/frontend/invocation.go) - the typed envelope and the private-socket trust check.
- [run.go](internal/frontend/run.go) - start or join, status mapping, signals, and delivery.
- [stage](stage) - assembles a coordinator root from Gradle output, the binary, and an explicit launcher.
- [acceptance](acceptance) - case matrix, comparison, latency, first callers, and cancellation evidence.

## Entry Points

- Tests: `cd frontend && go test -race ./...`. Set `KAST_FRONTEND_CATALOG` to the generated catalog for the status check.
- Coordinator bytecode and metadata: `mise exec -- ./gradlew :cli:assembleJvmCoordinator`.
- Coordinator route tests: `PublicInvocationEndpointTest` in `app-server` and `CoordinatorPublicInvocationsTest` in `cli`.
