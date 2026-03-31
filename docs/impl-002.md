---
title: CLI control plane alternatives
description: Options for exposing Kast as command-line utilities without
  requiring the caller to hold an interactive server process open.
icon: lucide/terminal-square
---

# CLI control plane alternatives

This note explores how to expose Kast through direct command-line utilities
while preserving the current backend contract and runtime split. It focuses on
one operator requirement: a caller must be able to start background workspace
indexing, validate that the target workspace is active and healthy, and then
run analysis operations without managing a foreground process handle.

This page describes planned alternatives only. The current implementation still
uses descriptor discovery plus HTTP `health` and `capabilities` checks.

## Current baseline

Kast currently has one transport surface and two runtime hosts.

- `analysis-server` owns the HTTP and descriptor lifecycle.
- `backend-intellij` starts a project-scoped HTTP server inside the IDE.
- `backend-standalone` starts a standalone JVM process that initializes a
  headless Analysis API session and then exposes the same HTTP routes.

Today, the bootstrap contract is:

1. Start a runtime.
2. Read its descriptor file from `<workspace>/.kast/instances/` or
   `KAST_INSTANCE_DIR`.
3. Call `/api/v1/health`.
4. Call `/api/v1/capabilities`.
5. Send analysis requests over HTTP.

That baseline has three gaps for a CLI-first workflow:

- There is no built-in detached launcher for the standalone host.
- There is no shared descriptor reader or liveness check in production code.
- `health` reports identity only. It does not report readiness, indexing state,
  or whether the workspace is usable for follow-on operations.

The descriptor default is already workspace-local. When that default is used
inside Git, Kast seeds `/.kast/` into `.git/info/exclude` so the metadata does
not become tracked by accident.

## Requirements

Any replacement or wrapper around the current HTTP surface must satisfy these
requirements.

- Start a workspace runtime without keeping an interactive terminal attached.
- Confirm that the selected workspace matches the requested absolute root.
- Reject stale descriptors and dead processes before routing any operation.
- Distinguish `starting`, `indexing`, `ready`, and `degraded` states.
- Keep capability checks honest across IntelliJ and standalone hosts.
- Stay easy for skills and automation to consume, preferably with stable JSON
  output and predictable exit codes.

## Alternatives

Each alternative below assumes the same shared API models and the same host
ownership boundaries unless noted otherwise.

### Alternative 1: thin CLI over a managed daemon

This option keeps HTTP as the internal transport and adds a CLI that manages
runtime discovery, detached startup, readiness checks, and request dispatch.

The CLI would:

1. Resolve the requested workspace root to an absolute normalized path.
2. Read and validate the matching descriptor.
3. Verify liveness by checking the recorded `pid` and a runtime status probe.
4. Start a detached standalone daemon when no healthy runtime exists.
5. Wait until the runtime reports `ready` before issuing analysis operations.

This is the lowest-risk option because it preserves the current host split and
request shapes. Existing HTTP clients keep working, while skills gain a direct
command surface that can hide bootstrap details.

The main weakness is that HTTP remains part of the internal design. That is an
implementation detail rather than a user-facing problem, but it does mean the
CLI is a control-plane wrapper, not a brand-new execution model.

### Alternative 2: one-shot CLI with a persisted index cache

This option replaces the long-lived daemon for most commands. Each command
starts a short-lived process, reads a disk-backed workspace index, performs the
operation, and exits. A separate background indexer refreshes the cache.

This sounds attractive because it avoids a resident analysis process for
day-to-day operations. In the current codebase, though, it is not a small lift.
Kast does not yet have a durable disk-backed index format, cache invalidation,
or snapshot loading path for either host. Building those mechanisms would be a
larger architecture change than the CLI itself.

This option is viable only after Kast has a real persisted workspace model. It
is not a good first step.

### Alternative 3: file queue plus background worker

This option replaces HTTP with a request directory. The CLI writes job files,
the background worker consumes them, and results are written back to disk for
the caller to read.

This avoids socket management, but it pushes complexity into queue ownership,
result correlation, timeouts, cleanup, retries, and concurrent mutation
control. It also creates a second wire format beside the existing API models.

For Kast's current scope, this is more operational machinery than the problem
justifies.

### Alternative 4: local IPC instead of HTTP

This option keeps a resident daemon but swaps HTTP for a Unix domain socket on
macOS and Linux and a named pipe on Windows.

Local IPC is a cleaner fit for machine-to-machine communication on one host,
and it avoids accidental network exposure. It also adds cross-platform
transport work, endpoint lifecycle differences, and a new client stack while
the current HTTP server already works for both IntelliJ and standalone hosts.

This could become attractive later if HTTP itself becomes a measurable source
of complexity or security risk. There is not yet enough evidence to justify
that migration.

## Recommendation

The recommended path is alternative 1: keep the existing HTTP server as an
internal transport, and add a CLI control plane in front of it.

That recommendation is grounded in the current code:

- `analysis-server` already owns the network boundary and descriptor writes.
- Both production hosts already implement the same backend contract.
- Skills need stable command behavior more than they need a new transport.
- A detached launcher plus readiness contract solves the operator problem
  without forcing a second large architecture change.

## Proposed command surface

The first CLI surface should stay small and directly map to the existing
workflow. Every command must accept `--workspace-root=/absolute/path`, and
machine-readable output must be available without parsing human text.

| Command | Purpose |
| --- | --- |
| `kast daemon start` | Start a detached standalone daemon for a workspace and print its resolved runtime metadata. |
| `kast daemon stop` | Stop the matching standalone daemon and clean up its descriptor if the process is still live. |
| `kast workspace status` | Report descriptor, liveness, health, readiness, backend identity, and capability state for one workspace. |
| `kast workspace ensure` | Ensure that a healthy, ready runtime exists for the workspace, starting one when needed. |
| `kast capabilities` | Return the advertised capabilities after the workspace passes readiness checks. |
| `kast symbol resolve` | Run symbol resolution after `workspace ensure` succeeds. |
| `kast references` | Run reference search after `workspace ensure` succeeds. |
| `kast diagnostics` | Run diagnostics after `workspace ensure` succeeds. |
| `kast rename` | Run rename planning after `workspace ensure` succeeds. |
| `kast edits apply` | Apply text edits after `workspace ensure` succeeds. |

For skills, the most important commands are `kast workspace ensure`,
`kast workspace status`, and the analysis subcommands that keep the current
request and response JSON shapes.

## Required implementation changes

This design needs one new control-plane concept: runtime readiness.

### API layer

`analysis-api` needs a status model that is richer than today's
`HealthResponse`. The current `health` shape is intentionally small and already
documented, so the safer path is to add a separate status model instead of
overloading `health`.

A first-pass status model could include:

- `state`: `starting`, `indexing`, `ready`, or `degraded`
- `healthy`: boolean
- `workspaceRoot`
- `backendName`
- `backendVersion`
- `message`: short operator-facing summary

### Transport layer

`analysis-server` should keep owning the transport boundary. The likely change
is a new route such as `/api/v1/runtime/status` that always remains available
like `health` and `capabilities`.

`analysis-server` is also the right home for shared descriptor read and liveness
helpers because descriptor lifecycle is already owned there. The current
`DescriptorStore` only writes and deletes files.

### Standalone host

`backend-standalone` needs the most work:

- Add a detached launcher path rather than relying on a foreground `main()`.
- Record startup transitions from `starting` to `ready` or `degraded`.
- Reject duplicate startups for the same workspace and backend identity.
- Expose enough state for `workspace ensure` to wait on readiness.

For the current standalone implementation, session construction is already a
large part of readiness. That means the first version can treat
`session initialized and server started` as `ready`, then grow into explicit
background indexing later if warmup becomes incremental.

### IntelliJ host

`backend-intellij` should not gain standalone-style daemon management. It
should report readiness only.

The important behavior is whether the project is in a usable state for analysis.
That likely means mapping IntelliJ smart-mode availability into the runtime
status response so the CLI can distinguish `starting` from `ready`.

### CLI module

The cleanest ownership boundary is a new module, for example `:analysis-cli`.
It would own:

- Multi-command parsing
- Descriptor lookup and liveness validation
- Detached standalone launch
- Readiness waiting
- HTTP client calls to the selected runtime
- Stable JSON output and exit codes

Putting this into `backend-standalone` would work for a first pass, but it
would couple standalone packaging with a client surface that also needs to talk
to IntelliJ-hosted runtimes.

## Suggested execution model

The CLI should use one bootstrap path for every analysis command.

1. Normalize the requested workspace root.
2. Look for a matching descriptor by workspace root and backend preference.
3. Validate that the recorded `pid` is still alive.
4. Call `runtime/status`.
5. If no healthy standalone runtime exists, start one in detached mode.
6. Wait until the runtime reports `ready`, or fail with a timeout.
7. Call `capabilities`.
8. Run the requested analysis operation.

This keeps startup, liveness, and capability checks in one place instead of
repeating them in every skill.

## Open decisions

Several design choices still need explicit decisions before implementation.

- Whether `kast workspace ensure` should auto-start a standalone daemon by
  default or require `--start-if-missing`
- Whether CLI commands should print JSON by default, or only when `--json` is
  set or stdout is not a TTY
- Whether readiness belongs only in the new runtime status route, or also in a
  separate on-disk state file for bootstrap without sockets
- Whether the first pass should support only the standalone host for startup
  while still letting the CLI discover an already-running IntelliJ host

## Next steps

If this direction is accepted, the next implementation sequence is:

1. Add a runtime status model and route without changing existing `health`
   semantics.
2. Extend descriptor handling with read, list, and liveness helpers.
3. Build `kast workspace status` and `kast workspace ensure`.
4. Add detached standalone startup and stop commands.
5. Layer analysis subcommands on top of the same readiness gate.
