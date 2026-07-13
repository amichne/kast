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

CLI normalization is not the mutation boundary. Immediately before a shipped
IDEA or headless backend changes the filesystem, it opens the filesystem root
and traverses every canonical workspace and target directory component with
POSIX `openat` using `O_NOFOLLOW` and `O_DIRECTORY`. Missing create-file
directories are materialized with `mkdirat` relative to the held parent
descriptor. New files are created with `openat`; existing-file edits are
written to a descriptor-relative temporary file and committed with `renameat`.
Delete operations use `unlinkat`. The backend refreshes IDEA VFS state after
the descriptor-relative mutation rather than asking VFS to resolve the target
pathname for the write.

This write-boundary check is repeated after server containment validation, so
replacing an accepted ancestor with an escaping symlink cannot redirect an
add-file or file-scoped mutation. Any symlink, non-directory component, missing
primitive, or unsupported non-POSIX runtime fails with the typed
`UNSAFE_WORKSPACE_MUTATION` error before an outside write. macOS and Linux are
the shipped runtimes governed by this decision. Existing source permissions
are carried onto atomic text replacements; newly created Kotlin files and
directories use deterministic IDEA-compatible permissions.

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

Canonicalizing a missing path alone is a check-then-use operation. Repeating
the walk at the actual mutation seam with held directory descriptors closes
the ancestor-replacement gap without changing canonical CLI output or the
absolute backend request contract.

## Public surface and source owners

| Contract | Source of truth |
| --- | --- |
| Typed command arguments | `cli-rs/src/cli/agent.rs` |
| Canonical workspace-contained path parser | `cli-rs/src/agent/path.rs` |
| Agent request and plan construction | `cli-rs/src/agent/dispatch.rs` |
| Typed unsafe-mutation failure | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/UnsafeWorkspaceMutationException.kt` |
| Descriptor-relative write boundary | `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/SecureWorkspaceMutation.kt`, `IdeaEditApplier.kt` |
| Installed agent routing | `cli-rs/resources/kast-skill/` |
| Published command examples | `docs/reference/agent-commands.md` |
| Behavior and regression proof | `cli-rs/tests/agent_diagnostics_smoke.rs`, `cli-rs/tests/agent_command_surface_smoke.rs`, `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaEditApplicationTest.kt` |

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
./gradlew :analysis-api:test :backend-idea:test
.github/scripts/test-docs-content-contract.sh
git diff --check
```
