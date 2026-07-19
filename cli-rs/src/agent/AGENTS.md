# Agent Module Instructions

This directory owns pipe-friendly typed `kast agent` behavior.

Keep command dispatch, internal catalog projection, package verification,
request input normalization, response envelopes, and typed command execution
in separate part files. Public agent commands are typed noun/verb operations
with bounded flags and structured output.

Keep compact public result projection families under `projection/`; the
`projection.rs` wrapper owns only their deterministic include order. Shared
view routing and envelope helpers stay in `view.rs` and `common.rs`, while
symbol, impact, diagnostics, verification, and mutation evidence remain in their
same-named family files.

Agent-facing semantic flows use `kast agent verify`, `kast agent
workspace-files`, `kast agent symbol`, standalone
`references`/`callers`/`callees`/`implementations`/`hierarchy`, `kast agent
diagnostics`, `kast agent impact`, `kast agent rename`, and `kast agent lsp`.

`task.rs` is the single cross-provider completion owner. It persists strict
exact-root task receipts, relevant-file baselines, Gradle model/policy proof,
same-epoch diagnostic hashes, accepted task outcomes, and test-report digests.
Provider hooks may supply a stable session identity, but must call this core
instead of owning a second baseline or completion state machine. This task
lease is never interchangeable with the IDEA runtime lease.

`workspace_files.rs` owns exact-root admission, typed conjunctive discovery
filters, the query-bound public continuation, and command execution.
`projection/workspace_files.rs` owns compact, selected-field, count, verbose,
and explain views. `public_capabilities.rs` owns the callable public route
registry used by verification. Keep backend raw paging internal and preserve
the distinction between source and script lane relevance, candidate and filter
coverage, build-qualified Gradle owners, and proven or unproven package and
source-set evidence. Public continuation binds the exact root, backend,
normalized query, result projection, limit, and discriminated composition
stamp; invalid or stale state must fail instead of restarting at page one.

`projection/diagnostics.rs` preserves and validates the ordered hash for every
analyzed file. Task completion consumes that projected same-read-epoch evidence
and must fail closed when a requested file hash is missing or stale.

`symbol_lookup.rs` owns identity lookup only. Exact lookup projects one
reusable anchored identity containing canonical declaration file and start
offset. `RESOLVED`, including indexed fallback, requires exactly one complete
anchor; otherwise project `IDENTITY_ANCHOR_UNAVAILABLE`.

`relations.rs` owns anchored relationship request construction, query-bound
page tokens, opaque wrapping of #337's UUID `ReferencePageToken`, opaque backend
traversal handles, and impact offsets. Rust must not decode or serialize the
reference source, provider position, returned-before count, query, subject,
generation, or traversal frontier. A continued page requires the typed
cardinality proof for at least one additional record.

`projection/relations.rs` owns the closed public record families and validates
each response family's own degraded-reason enum, non-null mismatch actual, and
unsupported-kind/stale/invalid variants. Preserve selector and verified subject
for `UNSUPPORTED_SUBJECT_KIND`; reject a variant whose family or actual kind is
not allowed by ADR 0022. Impact admits aggregate rows only after compiler
anchor verification and production path/offset/kind index identity; functions
and properties degrade because the production key cannot prove overload
isolation.
Do not reintroduce one-shot relationship work under symbol lookup, FQ-only
indexed reference reads, cross-family degradation codes, or client-serialized
semantic state.

Changes to this surface require
`cli-rs/tests/agent_relationship_navigation_smoke.rs` plus the command-surface,
result-projection, packaged-content, and generated-contract gates. Runtime
token changes use existing dependencies. The exact 1,500 `cl100k_base` compact
budget reuses #338's reviewed `tiktoken-rs` 0.12 test-only dependency;
relationship work does not rewrite `Cargo.toml` or `Cargo.lock` unless the
landed dependency graph itself changes.
