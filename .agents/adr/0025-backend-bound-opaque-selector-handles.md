# ADR 0025: Backend-bound opaque selector handles

Status: Accepted

Date: 2026-07-16

This ADR supersedes ADR 0022 only for transporting an already-resolved exact
selector into later semantic operations. ADR 0022's explicit anchored selector,
identity outcomes, relationship semantics, bounded traversal, and public paging
contracts remain in force. It extends ADR 0020's compact projections and ADR
0023's backend authority boundary. Issue #392 implements this decision after
the revision-coherent local development authority from ADR 0024 has landed.

## Context

Exact symbol lookup returns a human-readable declaration identity, but every
later operation currently requires an agent to reconstruct several flags from
that response. Reconstruction is verbose and gives each command another chance
to omit or alter the declaration anchor. Re-resolving by name would be worse:
it would discard the overload-safe identity already proven by the backend.

Paging cursors and mutation idempotency keys cannot fill this role. A paging
cursor owns one bounded traversal position and an idempotency key owns one
mutation submission. A reusable selector instead identifies one declaration
across multiple independent reads or plans, while that declaration identity is
still coherent with the issuing semantic backend.

## Decision

An exact compiler-backed `RESOLVE_SUCCESS` returns a compact opaque
`selectorHandle` beside the existing human-readable `identity`. The handle has
the versioned `ksh1.` prefix. Clients may store, compare, and return it, but
must not decode it or synthesize one from identity fields.

The backend issues the handle from the exact declaration it resolved. Its
integrity envelope binds all of the following claims:

- the canonical workspace identity;
- the semantic backend kind and runtime-instance identity;
- the semantic revision, PSI generation, or index generation relevant to the
  permitted operation families;
- the complete ADR 0022 declaration selector; and
- a closed set of operation families for which that selector kind is valid.

The envelope uses canonical, versioned serialization plus a cryptographic
digest. It detects malformed or changed handles before trusting any claim. A
selector handle is not an authentication, authorization, or confidentiality
credential: Kast's local backend boundary remains the authority, and callers
must not rely on the digest to resist a party that can manufacture a new
envelope. If handles later cross an untrusted boundary, a superseding decision
must add backend-authenticated issuance rather than describing the digest as a
signature.

The public selector inputs are an exclusive choice:

1. the existing explicit `--symbol`, `--declaration-file`,
   `--declaration-start-offset`, and optional assertion flags; or
2. one `--selector-handle` value.

Explicit selectors remain supported for inspection, scripting, recovery, and
compatibility. The CLI validates the exclusive choice and carries a handle
unchanged. It never turns the handle back into explicit fields. The backend
validates the handle and recovers its exact selector before provider, index,
continuation, or mutation work starts.

The following public families accept a handle where their subject-kind rules
allow it: references, callers, callees, implementations, hierarchy, impact,
rename, and replace-declaration. Rename and replace retain their plan-first,
precondition, scope, and idempotency contracts; the handle selects the subject
but is not a mutation authorization or idempotency key.

Validation has closed, actionable outcomes:

- `SELECTOR_HANDLE_TAMPERED` for malformed envelopes, unknown versions, digest
  disagreement, or invalid claims;
- `SELECTOR_HANDLE_WRONG_WORKSPACE` when an intact handle names another
  canonical workspace;
- `SELECTOR_HANDLE_WRONG_BACKEND` when it names another backend kind or runtime
  instance;
- `SELECTOR_HANDLE_STALE` when its bound semantic generation is no longer
  current; and
- `SELECTOR_HANDLE_FAMILY_NOT_ALLOWED` when the requested operation family was
  not issued for the resolved subject kind.

Integrity is checked before contextual comparisons. Contextual comparisons are
then evaluated in workspace, backend, generation, and family order so the same
input has one deterministic outcome. Each outcome tells the caller to resolve
again, target the issuing workspace/backend, or choose a supported operation as
appropriate. None may fall back to fuzzy or FQ-name resolution.

Relationship paging remains a separate `krp1.` query-bound cursor. When the
initial selector was a handle, the public page-token fingerprint binds that
exact opaque value and the relation options. Continuations do not embed,
replace, refresh, or extend the selector handle. Existing mutation idempotency
keys also remain distinct.

Compact symbol output includes `selectorHandle` without requiring verbose
resolve evidence or replaying the backend response. Field selection may request
the handle directly. Detailed output preserves the backend-issued value.

## Ownership and proof

`analysis-api` owns host-neutral handle values, operation-family types, and
closed validation outcomes. The semantic backend owns issuance, integrity,
workspace/backend/generation comparison, and exact-selector recovery. The RPC
orchestrator parses the wire-level exclusive selector choice immediately and
does not create a second relationship-state store. The Rust CLI owns typed
flags, opaque transport, compact projection, and public paging fingerprints.

Proof lands in vertical TDD slices:

1. exact resolution emits a handle and references consumes it without another
   resolution;
2. each rejection outcome occurs before semantic provider work;
3. every applicable read and plan family accepts the same handle while explicit
   selectors continue to work; and
4. an installed local-development workflow resolves once, performs multiple
   exact operations, and keeps compact output bounded.

Each slice uses the same focused command for its RED and GREEN transition and
records both commits on the draft pull request.

## Consequences

Agents can resolve once and reuse a compiler-proven declaration without
reconstructing command-specific selectors. Handles naturally expire when the
semantic generation changes or the backend restarts, making that loss of
authority explicit rather than silently choosing a new declaration.

The handle duplicates a small exact selector inside an opaque envelope, but it
does not retain the large resolve payload and requires no unbounded server-side
handle registry. Public JSON and command schemas gain an alternative selector
form and stable failure codes, so catalogs, docs, projections, and installed
proof must change from their source owners.

Any future change to the handle prefix, claim schema, integrity algorithm,
validation order, family set, or selector exclusivity must supersede this ADR.
