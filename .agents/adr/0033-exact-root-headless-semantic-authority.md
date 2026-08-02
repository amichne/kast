# ADR 0033: Exact-root headless semantic authority

Status: Accepted

Date: 2026-08-01

## Reason this record remains

The compiler implementation uses IntelliJ and Kotlin libraries, but that does
not make a foreground IDE process a Kast authority. This boundary is easy to
weaken accidentally across routing, packaging, setup, and runtime lifecycle
code.

## Decision

Kast admits one healthy headless runtime for one canonical workspace root. The
admitted runtime is the only semantic server and the only persistent source
index writer. Reads, mutations, graph refresh, reference indexing, readiness,
leases, and lifecycle operations use the same admitted identity.

Normal demand starts at the public interface:

```text
cd <canonical-workspace-root>
kast up
```

On macOS, a supported IntelliJ IDEA or Android Studio installation supplies
compatible runtime libraries. Kast starts an isolated process with its own
configuration, system, log, plugin, descriptor, socket, and VFS paths. The
private `idea-home/plugins/kast-headless` payload stays inside the release. It
is not installed into a foreground application.

A foreground IDE has no Kast startup, shutdown, routing, metadata, socket, or
writer edge. Opening or closing it cannot change the admitted runtime identity,
generation, or readiness. Kast does not inspect or control foreground IDE
processes during normal setup or semantic demand.

Legacy IDEA backend intent is migration input only. The central authority
planner rewrites it to headless or rejects it with a typed error before runtime
side effects. Setup removes known legacy public plugin files. Releases contain
no public plugin archive, update feed, checksum, build job, or publish job.

Runtime readiness, semantic graph coverage, and reference coverage remain
separate typed facts. `READY` does not imply complete persisted graph coverage.

## Source and proof

- `cli-rs/src/execution/runtime/backend/headless_authority.rs`
- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `backend-headless/`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/`
- `packaging/jetbrains/runtime-compatibility.json`
- `.github/scripts/runtime/test-headless-semantic-authority-contract.sh`
- `scripts/smoke-macos-headless-runtime.sh`

Any new semantic backend, foreground lifecycle edge, endpoint owner, or source
index writer must replace this decision explicitly.
