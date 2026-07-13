# ADR 0018: Relative agent file path normalization

Status: Accepted

Date: 2026-07-13

Supersedes [ADR 0006](0006-forward-system-definition-and-audit-scope.md) in
part for typed agent command file arguments. The public agent CLI now accepts
workspace-relative Kotlin target paths when an explicit workspace root is
declared, while backend requests continue to use canonical absolute paths.

## Decision

The Rust typed agent CLI owns semantic target path normalization. Diagnostics
`--file-path`, add-file `--file-path`, and file-scoped mutation `--inside-file`
accept either an absolute path or a path relative to explicit
`--workspace-root`.

Before request or plan construction, each target is parsed into a canonical
absolute Kotlin path contained by the canonical workspace root. Existing safe
symlinks resolve to their real in-workspace target. Lexical escapes, symlink
escapes, broken symlinks, unreadable prefixes, directories, special files, and
extensions other than `.kt` or `.kts` fail closed. A missing `.kt` or `.kts`
leaf is permitted after its deepest existing ancestor is canonicalized and
proved to be an in-workspace directory; this preserves deleted-file refresh
and new-file planning.

Relative targets are rejected when `--workspace-root` was omitted, even if
Kast could infer a workspace from the current directory. Absolute targets
remain accepted without an explicit root when they are contained by the
inferred effective workspace.

Diagnostics output includes the ordered canonical `filePaths` list used by
both refresh and analysis. Mutation plans expose canonical target paths in
their typed request parameters. Human, JSON, and TOON therefore identify the
same path the backend receives.

`--file-hint` remains a compiler-identity hint, not a target path.
`--content-file` remains a payload-source argument. Neither is reinterpreted by
this decision.

## Rationale

Normalizing at the public CLI boundary gives every backend one existing
absolute-path contract and prevents a plan, refresh, analysis request, and
rendered result from disagreeing about file identity. Typed canonical paths
make it impossible for downstream request construction to accidentally reuse
unchecked input. Resolving the deepest existing ancestor supports meaningful
missing-file operations without weakening workspace containment or symlink
safety.

## Public surface and source owners

| Contract | Source of truth |
| --- | --- |
| Typed command arguments | `cli-rs/src/cli/agent.rs` |
| Canonical workspace-contained path parser | `cli-rs/src/agent/path.rs` |
| Agent request and plan construction | `cli-rs/src/agent/dispatch.rs` |
| Installed agent routing | `cli-rs/resources/kast-skill/` |
| Published command examples | `docs/reference/agent-commands.md` |
| Behavior and regression proof | `cli-rs/tests/agent_diagnostics_smoke.rs`, `cli-rs/tests/agent_command_surface_smoke.rs` |

## Out of scope

Raw JSON-RPC dispatch, generated catalog invocation, offset selectors, and
backend-specific path dialects remain outside the public product surface.
Relative `--file-hint` matching and arbitrary payload-file relocation are not
introduced. Backend API request fields stay canonical and absolute.

## Change rule

New public semantic target path arguments must use the same typed normalizer or
supersede this ADR with an equally explicit trust boundary. Do not add backend
fallback joining, current-directory-relative target interpretation, or string
cleanup after request construction.

## Validation

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke --test agent_command_surface_smoke --test packaged_content_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
git diff --check
```
