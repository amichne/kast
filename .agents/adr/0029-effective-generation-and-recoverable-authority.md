# ADR 0029: Effective generation and recoverable install authority

Status: Accepted

Date: 2026-07-18

## Context

Issue #417 asks Kast to select one complete release or local-development
generation, recover recognized install damage, and never leave an invocation on
a partial mixture. Its proposed mechanism also assumes a signed IDEA plugin and
a persisted recovery capability. Those assumptions are not current product
authority: ADR 0028 deliberately selects an unsigned GitHub plugin, and ADR
0023 keeps Homebrew CLI ownership, JetBrains plugin ownership, and exact-root
semantic admission as independent planes.

Kast already has immutable prepared local generations, `current` and
`previous` activation pointers, isolated local runtime state, effective agent
readiness, and exact-root leases. Replacing those systems would add state while
preserving their bugs. The remaining correctness gaps are narrower:

- release leases call a version string a generation;
- a same-version CLI and plugin build cannot prove that they came from the same
  release source revision;
- a recognized schema-1 Homebrew receipt cannot recover after the formula has
  advanced to a newer version;
- first local activation can be interrupted before `current` exists, leaving
  exact Kast-owned but unrecoverable staging links; and
- a local generation identifier omits its built CLI and backend identities.

"No failures ever" is not an implementable guarantee. Disk, process, IDE, and
permission failures remain possible. The enforceable guarantee is stronger
where it matters: Kast never reports a partial generation as active, never
silently borrows another authority, and every owned transition either preserves
the last verified generation or exposes a deterministic, bounded recovery.

## Decision

### One effective-generation value

Readiness and leases consume one closed effective-generation model:

- `release(distribution, revision)` identifies an official or managed release
  by an embedded full source revision; and
- `local-development(generationId)` identifies one immutable local artifact
  set.

Version remains descriptive compatibility evidence, not generation identity.
The release distribution remains explicit so `macos-homebrew` and
`managed-local` are never conflated. The lease JSON contract carries this typed
value directly instead of flattening it back into an unvalidated string.

Release CLI and IDEA plugin builds embed the same full release source revision.
Project-open admission rejects a same-version pair whose revisions differ
before writing exact-root workspace metadata. The unsigned distribution does
not claim signer identity, reproducible bytes, or immutable draft assets.
Observed component digests remain separate evidence and may distinguish local
or repackaged artifacts without being mislabeled as a signature.

A local generation identifier binds the source snapshot, implementation
version, CLI digest, and headless-backend digest. Source-owned skill, guidance,
configuration, and Codex templates remain transitively bound by the strict
source snapshot and are independently verified in the generation ledger. A
same-source rebuild with different executable bytes is therefore a distinct
generation rather than a collision.

### Closed authority outcomes

Authority resolution has three semantic outcomes:

- `ACTIVE(evidence)` permits readiness and lease acquisition;
- `RECOVERABLE(plan)` describes one bounded mutation that could restore CLI
  authority; and
- `BLOCKED(reason)` grants no authority and preserves unrecognized state.

A recovery plan is an in-process proof value, not a persisted bearer token or
semantic authorization. Apply reacquires the receipt lock, re-reads the
observed receipt, revalidates the exact running formula executable, writes one
CLI-only receipt atomically, discards the proof, and reruns ordinary strict
resolution. Receipt recovery never proves IDEA compatibility and never edits
JetBrains-owned plugin files.

Recognized stale schema-1 and schema-2 receipts may converge to the exact
running Homebrew formula even when their recorded version is older. Unknown,
malformed, foreign, copied, or non-Cellar state remains unchanged. An explicit
fresh receipt reset may quarantine unknown receipt bytes before reconstructing
only CLI authority from the exact running Cellar executable. It is never an
implicit repair fallback.

### Local transition recovery

`current` remains the sole local commit point. Before it moves, newly staged
resources are not active; after it moves, every stable entrypoint resolves
through that one pointer. A transition under the local authority lock first
reconciles only exact receipt-owned residue:

- an interrupted first activation with no `current` may remove or finish only
  components proven by a valid staged generation receipt;
- an interrupted removal may finish only a tombstone whose receipt binds the
  exact prefix and workspace; and
- unknown prefix or tombstone contents remain blocked and untouched.

An existing valid `current` is preserved on activation failure. Explicit
rollback selects a validated `previous`. Removal exposes ordinary release
resolution only after the local authority is fully gone; no invocation
silently switches from local to release.

The source-bound `kast-dev` entrypoint remains the only local execution path.
Local Codex projection remains an explicit, idempotent post-activation action
because a running Codex task cannot reload plugins atomically. Generated hooks
must bind the absolute `kast-dev` entrypoint and local generation token. The
healthy release plugin remains installed and becomes the ordinary fallback in
a newly started task after local authority is removed; Kast does not mutate the
current task or auto-upgrade Homebrew or JetBrains.

## Source ownership

- `cli-rs/src/install/` owns typed Homebrew receipt classification, locking,
  backup, atomic replacement, and explicit receipt reset.
- `cli-rs/src/self_mgmt/agent_readiness.rs` owns effective environment
  resolution; `cli-rs/src/runtime/lease.rs` consumes its typed generation.
- `analysis-api` owns release-revision compatibility types;
  `backend-idea` owns plugin projection and project-open rejection.
- `cli-rs/src/local_development/` owns content generation identity, pointer
  transitions, receipt-owned reconciliation, rollback, and removal.
- `cli-rs/src/codex/` and its resource tree own explicit local Codex
  projection. They do not become another installation authority.

## Validation

Focused tests prove recognized old-receipt convergence, changed-state
revalidation under lock, copied-binary and unknown-state preservation,
same-version/different-revision project-open rejection, typed release and local
lease identities, same-source/different-artifact local generations, and
reconciliation of every durable local transition checkpoint. Existing runtime
compatibility, local refresh, Codex plugin, release workflow, installer,
documentation, and representative installed semantic fixture contracts remain
the end-to-end gates. No second permanent full-workflow job is introduced.

## Rejected alternatives

- Restoring plugin signing, certificate trust, or signer identity conflicts
  with ADR 0028.
- Treating version equality as generation authority preserves the current bug.
- Persisted single-use recovery tokens add replay state without granting any
  authority that cannot be re-proven under the receipt lock.
- Another installer, runtime manager, lease implementation, or automatic
  fallback duplicates existing owners and risks mixed state.
- Extending the legacy `installDevelopmentLocal` mutation path would recreate
  the partial install ADR 0024 replaced.
- Deleting unknown state to make repair succeed would trade availability for
  silent data loss.
