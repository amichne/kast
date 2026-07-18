# ADR 0028: Exact-root agent workspace leases

Status: Accepted

Date: 2026-07-17

## Context

ADR 0019 admits semantic work against one exact normalized workspace root, ADR
0023 owns runtime identity, ADR 0024 owns revision-coherent local-development
authority, and ADR 0027 proves the effective agent environment. Coordinators
still have to compose those facts with `developer runtime` commands and cannot
prove that later worker requests use the runtime they prepared.

This decision adds the coordination primitive requested by issue #397. It does
not add another runtime manager or weaken any existing admission rule.

## Decision

Kast exposes one typed agent lifecycle:

```console
kast agent lease acquire --workspace-root <absolute-root> [--backend idea|headless]
kast agent lease status --lease-id <opaque-id> --workspace-root <absolute-root>
kast agent lease release --lease-id <opaque-id> --workspace-root <absolute-root>
```

Acquisition canonicalizes the explicit root, classifies it through ADR 0019,
and validates `ready --for agent` before and after runtime selection. It waits
for a fully `READY` exact-root backend; `INDEXING` is never lease readiness.
Headless acquisition uses the existing runtime ensure/start path. IDEA
acquisition only borrows an already hosted exact-root runtime and never starts
or terminates an IDE process.

The effective environment identity binds installation authority, release or
local generation, binary dialect and revision, backend identity, skills, and
guidance. Acquisition fails closed and cleans up its exact started runtime if
that identity or the selected descriptor changes before the lease record is
committed.

### Identity and storage

The lease identifier is an opaque, HMAC-authenticated claim containing a
random record identity plus installation-authority and generation identity.
The authority key and strict lease records live below Kast's existing resolved
install/runtime state. They are not stored in the workspace, Git directory, or
another checkout's metadata.

An active record binds:

- canonical workspace root and checkout classification;
- selected backend and full runtime descriptor identity;
- runtime process-start identity in addition to PID;
- effective installation authority and generation;
- `STARTED` or `BORROWED` resource disposition;
- the acquiring shell's PID and process-start identity; and
- acquisition time and explicit lifecycle state.

Possession of a valid identifier authorizes use, but every semantic command
that supplies `--lease-id` revalidates the requested root, backend, effective
environment, owner liveness, and exact ready runtime identity before sending a
request. A lease identifier that is merely returned and never checked is not a
supported state.

### Concurrency and recovery

One active lease is allowed for an exact root/backend. A second live
acquisition returns a typed conflict; runtimes are never silently shared
between independently releasable leases.

Owner liveness uses PID plus process-start identity, not PID or record age
alone. No expiry or heartbeat is inferred. Status reports `ABANDONED` when the
recorded owner is gone and `FAILED` when the exact runtime has crashed or been
replaced. A later acquisition may recover an abandoned lease under the lease
registry lock, stopping only a still-matching runtime that the abandoned lease
started. A demonstrably live owner is never reaped.

Release is idempotent and stores a terminal receipt. It stops a headless
runtime only when the record says `STARTED` and the current descriptor and
process-start identity still match. Borrowed headless runtimes and every IDEA
runtime remain running. Wrong-root, wrong-backend, tampered, stale-generation,
foreign-authority, abandoned, and failed outcomes remain distinct typed
results.

## Source ownership

- `cli-rs/src/runtime/` owns lease identity, registry, runtime checks, recovery,
  and exact-resource release alongside the existing lifecycle.
- `cli-rs/src/cli/agent.rs` and `cli-rs/src/agent/` own the typed command and
  semantic-request guard.
- `cli-rs/src/self_mgmt/agent_readiness.rs` remains the effective environment
  authority; the lease consumes rather than duplicates it.
- `cli-rs/resources/kast-skill/`, Codex exposure generation, and `docs/` own
  agent-facing guidance.
- `.github/scripts/test-local-development-semantic-fixture.sh` remains the one
  representative installed semantic proof.

## Validation

The focused contract is `cli-rs/tests/workspace_lease_smoke.rs`. The existing
runtime, exact-root admission, readiness, local-development, generated command,
documentation, Gradle, and installed semantic fixture gates remain required.
No additional permanent full-workflow CI job is introduced.

## Out of scope

This decision does not add workspace discovery projections, default mutation
waiting, packaged-release golden gates, automatic plugin installation,
workspace metadata copying, branch/commit authority, raw SQLite access, or a
second runtime lifecycle.
