# Runtime Module Instructions

This directory owns exact-root indexer admission, lifecycle, status inspection,
descriptor management, workspace identity, and authenticated leases.

`control/lease.rs` owns the coordinator-safe exact-root agent lease. Its authenticated
record binds workspace classification, the indexer descriptor and process-start
identity, effective install generation, caller session, and started-versus-
borrowed disposition. It may compose the existing lifecycle and readiness
authorities but must not become another runtime manager. Release may stop only
the still-matching indexer recorded as started; borrowed resources are
preserved. Legacy IDEA leases never authorize validation, RPC, or lifecycle work.

`backend/indexer_authority.rs` is the one policy boundary. Setup alone may parse
legacy IDEA configuration to produce a migration plan.
Downstream code must receive `SemanticWorkspaceAdmission`, not an implementation name,
preference, string, or optional selector.

Semantic workspace admission owns primary, linked, disposable, standalone,
and unsupported workspace classification. A descriptor, status response, and
capability response must match the exact canonical requested root. Shared Git
ancestry, branch, or commit is not authority. Verification is reuse-only and
must not start a runtime, prune descriptors, or rewrite registry state.

Admission requires complete runtime-instance, process-start, effective-owner,
and socket device/inode identity. Revalidate the exact registered descriptor,
process, and endpoint before each RPC and before stop or restart. Never use PID
liveness alone. A descriptor cannot make a non-Gradle root supported, and a
temporary clone is not primary merely because it owns a `.git` directory.

More than one valid indexer for the exact root returns a typed conflict;
lifecycle code does not guess. Runtime readiness,
semantic graph coverage, and reference coverage remain separate typed facts.

Focused proof:

```console
cargo test --locked --manifest-path cli-rs/Cargo.toml --test semantic_workspace_admission_smoke
cargo test --locked --manifest-path cli-rs/Cargo.toml --test workspace_lease_smoke
cargo test --locked --manifest-path cli-rs/Cargo.toml --test agent_readiness_smoke
.github/scripts/runtime/test-runtime-compatibility-contract.sh
```
