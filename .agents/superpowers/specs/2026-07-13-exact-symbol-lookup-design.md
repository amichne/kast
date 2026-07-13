# Exact Symbol Lookup Design

## Goal

Make `kast agent symbol` return one exact symbol identity or a typed not-found
or ambiguous outcome, while keeping fuzzy discovery available only through an
explicit mode.

## Current Failure

The public command currently sends `symbol/query` with exact and lexical modes,
then sends `symbol/resolve`. The resolver searches workspace symbols, scores
the candidates, and resolves the first one. A name that is absent, stale, or
not yet indexed can therefore surface unrelated lexical candidates before
compiler identity is established.

## Chosen Architecture

The existing `symbol/resolve` RPC becomes the compiler-owned exact boundary.
It searches broadly enough to collect candidates, then applies normalized
simple-name or fully-qualified-name equality plus kind, file hint, and
containing type as hard constraints. Backtick normalization exists only in the
comparison function. The returned `Symbol` remains the backend's canonical
identity.

`KastResolveResponse` becomes a sealed four-variant contract:

- `RESOLVE_SUCCESS` carries the unique compiler identity and
  `source=compiler`.
- `RESOLVE_NOT_FOUND` carries the exact query and `source=compiler`.
- `RESOLVE_AMBIGUOUS` carries the bounded exact candidates and
  `source=compiler`.
- `RESOLVE_FAILURE` remains the operational error variant for existing
  internal consumers.

The response owner moves from the legacy aggregation file into
`KastResolveResponse.kt`. The sealed variants stay with their root, satisfying
the repository's Kotlin file-isolation rule without causing an unrelated
package-wide migration.

The Rust public boundary adds `AgentSymbolMode` with `exact` as the default and
`discovery` as the only path to lexical candidates. Rust maps backend and index
payloads into a typed public result whose outcome is `resolved`, `not-found`,
`ambiguous`, or `discovery`, and whose evidence source is `compiler`,
`indexed-exact`, or `fuzzy`.

## Exact Data Flow

1. Parse the CLI query and optional hard constraints into `AgentSymbolArgs`.
2. Send `symbol/resolve` to the selected compiler backend.
3. Return the compiler's resolved, not-found, or ambiguous outcome directly.
4. If and only if the request fails with a typed backend-availability or
   capability error, issue `symbol/query` with `modes=[exact]` and no lexical
   mode.
5. Map zero, one, or multiple hard-constrained index results to not-found,
   resolved, or ambiguous with `source=indexed-exact`.
6. If a requested constraint cannot be proven by the source index, preserve
   the compiler availability error instead of weakening the request.
7. Run references or callers only after compiler resolution, using the
   canonical resolved fully qualified name.

Compiler not-found and ambiguous outcomes terminate the flow. They never
invoke the source index or fuzzy discovery.

## Discovery Data Flow

`--mode discovery` sends the existing indexed `symbol/query` request with
`modes=[lexical]`. Its candidates are returned under a typed discovery outcome
with `source=fuzzy`. Exact-looking lexical candidates do not change the source
or outcome and cannot pose as resolved identity.

References and callers are invalid in discovery mode because discovery does
not establish one canonical compiler identity. The CLI returns a structured
usage error before attempting relation requests.

## Identity And Constraints

The comparison boundary trims whitespace and removes one pair of Kotlin
backticks from each qualified-name segment. It does not lowercase names,
perform substring matching, or rewrite the result. A query without a package
separator compares against the candidate's simple name; a qualified query
compares against the complete qualified name.

Kind, file hint, and containing type are filters, not ranking bonuses. Multiple
overloads with the same fully qualified name remain ambiguous when the
available constraints cannot distinguish their compiler locations.

## Error Design

Not-found and ambiguous are expected domain outcomes and therefore remain
successful command execution with typed payloads. Missing runtime, unreachable
daemon, unsupported compiler capability, and response timeout are operational
availability errors. Only the defined availability set can authorize the
indexed exact fallback. Invalid responses, compiler failures after dispatch,
and unprovable index constraints remain errors.

## Source And Generated Ownership

The CLI definitions, agent orchestration, Kotlin response contract, server
selection logic, command catalog, packaged skill, and public docs are authored
sources. Protocol Markdown, schemas, and request samples generated from the
catalog are regenerated through the release contract generator and are not
hand-edited.

## Testing

The TDD slices are:

1. Server tests for exact not-found, ambiguous overloads, backticked simple and
   qualified names, and hard constraints. Each test fails against the current
   first-ranked resolver before production changes.
2. Rust public-command tests for default exact requests, explicit discovery,
   typed outcome/source rendering, exact-only availability fallback, no
   fallback after compiler not-found or ambiguous, and relation requests based
   on canonical compiler identity.
3. Source-index tests proving exact mode excludes lexical candidates and
   normalizes backticked identity without changing returned names.
4. Contract and migration tests proving internal consumers can still decode
   `RESOLVE_SUCCESS` and `RESOLVE_FAILURE` while exhaustively recognizing the
   two new expected variants.
5. Generated contract, documentation, formatting, lint, module, and full-suite
   gates.

## Non-Goals

This change does not add fuzzy fallback to exact mode, invent signature-level
identity for overloads, redesign source-index storage, expose arbitrary RPC
dispatch, or make generated protocol files an authored source of truth.
