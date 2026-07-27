# Agent Module Instructions

This directory owns pipe-friendly typed `kast agent` behavior.

Keep command dispatch, internal catalog projection, package verification,
request input normalization, response envelopes, and typed command execution
in separate part files. Public agent commands are typed noun/verb operations
with bounded flags and structured output.

Keep non-navigation public result projection families under
`projection/families/`;
relationship navigation projections stay with their compiler-evidence flow
under `navigation/`. Each wrapper owns only deterministic include order. Shared
view routing and envelope helpers stay in `view.rs` and `common.rs`, while
symbol, impact, diagnostics, verification, and mutation evidence remain in
their same-named family files.

Agent-facing semantic flows use `kast agent verify`, `kast agent
workspace-files`, `kast agent symbol`, standalone
`references`/`callers`/`callees`/`implementations`/`hierarchy`, `kast agent
diagnostics`, `kast agent impact`, and `kast agent rename`.

`workspace_files.rs` owns exact-root admission, typed conjunctive discovery
filters, the query-bound public continuation, and command execution.
`projection/workspace_files.rs` owns compact, selected-field, count, verbose,
and explain views. `core/public_capabilities.rs` owns the callable public route
registry used by verification. Keep backend raw paging internal and preserve
the distinction between source and script lane relevance, candidate and filter
coverage, build-qualified Gradle owners, and proven or unproven package and
source-set evidence. Public continuation binds the exact root, backend,
normalized query, result projection, limit, and discriminated composition
stamp; invalid or stale state must fail instead of restarting at page one.

`projection/diagnostics.rs` preserves and validates the ordered hash for every
analyzed file. Task completion consumes that projected same-read-epoch evidence
and must fail closed when a requested file hash is missing or stale.

`core/symbol_lookup/mod.rs` owns identity lookup only. Exact lookup projects one
reusable anchored identity containing canonical declaration file and start
offset. `RESOLVED`, including indexed fallback, requires exactly one complete
anchor; otherwise project `IDENTITY_ANCHOR_UNAVAILABLE`.

`navigation/relations.rs` owns anchored relationship request construction, query-bound
page tokens, opaque wrapping of backend `ReferencePageToken` values, opaque
backend traversal handles, and impact offsets. Rust must not decode or serialize the
reference source, provider position, returned-before count, query, subject,
generation, or traversal frontier. A continued page requires the typed
cardinality proof for at least one additional record.

`navigation/projection.rs` owns the closed public record families and validates
each response family's own degraded-reason enum, non-null mismatch actual, and
unsupported-kind/stale/invalid variants. Preserve selector and verified subject
for `UNSUPPORTED_SUBJECT_KIND`; reject a variant whose family or actual kind is
not allowed by that relationship family. Impact admits aggregate rows only after compiler
anchor verification and production path/offset/kind index identity; functions
and properties degrade because the production key cannot prove overload
isolation.
Do not reintroduce one-shot relationship work under symbol lookup, FQ-only
indexed reference reads, cross-family degradation codes, or client-serialized
semantic state.

Changes to this surface require
`cli-rs/tests/agent_relationship_navigation_smoke/main.rs` plus the command-surface,
result-projection, packaged-content, and generated-contract gates. Runtime
token changes use existing dependencies. The exact 1,500 `cl100k_base` compact
budget reuses the existing `tiktoken-rs` 0.12 test-only dependency;
relationship work does not rewrite `Cargo.toml` or `Cargo.lock` unless the
landed dependency graph itself changes.
