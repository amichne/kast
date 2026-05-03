# ADT-first validation and derivation boundaries

Kast already concentrates critical validation and derivation logic at a few
high-leverage boundaries. This page identifies those boundaries and outlines a
uniform way to encode outcomes as sealed ADTs so parse/validate/derive steps
are explicit, composable, and safe across CLI, JSON-RPC, and backend runtime
flows.

## Highest-impact boundary points

The most important places to preserve and extend ADT-first behavior are listed
below.

1. CLI command parsing and argument decoding (`kast-cli`).
   `CliCommandParser` converts raw `args` into structured command values and
   throws `CliFailure` on usage violations, which makes it the first decode
   boundary from untrusted input to typed intent.
2. API contract numeric and coordinate wrappers (`analysis-api`).
   `CoreTypes` enforces domain invariants like non-negative offsets and
   1-based line/column values with `require`, so these are foundational parse
   guards for all compile-API-facing payloads.
3. Edit plan validation and filesystem mutation preflight (`analysis-api`).
   `EditPlanValidator` canonicalizes paths, groups edits, checks overlap and
   hash presence, and yields validated operations before mutation. This is a
   high-impact assertion boundary because bad plans can corrupt user code.
4. Runtime/session bootstrapping (`backend-standalone`).
   Standalone startup/session code uses `require`/`check` to enforce workspace
   and module assumptions. Those assumptions should be represented as explicit
   startup-state ADTs before heavy analysis work begins.
5. Config/telemetry parsing (`backend-standalone`).
   Parsing functions map raw strings into enum-like runtime scopes and details.
   These parse points are ideal for total decode ADTs that preserve unknown or
   unsupported inputs without silent drops.

## ADT encoding model

Apply one consistent model for parse, assert, and derive boundaries.

### 1) Parse boundaries return total decode ADTs

Prefer returning sealed outcomes over exceptions during boundary decoding.

```kotlin
sealed interface DecodeResult<out T> {
    data class Ok<T>(val value: T) : DecodeResult<T>
    data class Invalid(
        val code: String,
        val message: String,
        val field: String? = null,
        val details: Map<String, String> = emptyMap(),
    ) : DecodeResult<Nothing>
}
```

Use at the edge for CLI args, JSON request bodies, env/config values, and
filesystem metadata extraction.

### 2) Assertion boundaries convert decode failures to transport errors once

At transport/command adapters, collapse `DecodeResult.Invalid` into
`CliFailure`, `ValidationException`, or JSON-RPC error envelopes exactly once.
Keep the inner layers exception-light and ADT-heavy.

### 3) Derivation steps use explicit computation-state ADTs

Where we derive data (workspace graphs, edit application plans, metrics), model
intermediate state with sealed ADTs rather than nullable/boolean flags.

```kotlin
sealed interface DerivationState<out T> {
    data class Ready<T>(val value: T) : DerivationState<T>
    data class Blocked(
        val reason: String,
        val remediation: String? = null,
    ) : DerivationState<Nothing>
}
```

### 4) Compile-API-facing types stay closed and discriminated

For all request/response/query families, keep `sealed interface` roots and
explicit discriminator fields stable. New variants should be additive and
wire-compatible, with decode fallbacks that preserve unknown-type diagnostics.

## Rollout sequence (tracer-bullet friendly)

Introduce this in short vertical slices.

1. Pick one boundary (for example, CLI `ParsedArguments.parse`) and add
   `DecodeResult` plus adapter conversion to existing failure types.
2. Add property-style tests for valid and invalid variants at that boundary.
3. Migrate one backend parse point (telemetry scope/detail) to the same ADT.
4. Extend to edit-plan preflight so validation errors are represented as a
   closed ADT before exception translation.
5. Propagate pattern to other compile-API-facing codecs and keep transport
   translation thin.

## Design guardrails

Use these guardrails as a repository-wide principle.

- Boundary-in: raw input, boundary-out: sealed ADT.
- Domain layers should consume validated ADTs, not raw strings/maps.
- Exception throwing is an adapter concern, not a parsing concern.
- ADT variants must encode enough evidence for deterministic diagnostics.
- When certainty is incomplete, prefer explicit `Blocked`/`Invalid` variants
  over partial success with hidden drops.
