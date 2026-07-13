# ADR 0019: Exact-root semantic workspace admission

Status: Accepted

Date: 2026-07-13

This ADR supersedes the semantic-workspace routing portions of
[ADR 0006](0006-forward-system-definition-and-audit-scope.md) and narrows the
macOS workspace rules in
[ADR 0007](0007-macos-plugin-setup-authority.md). The IntelliJ plugin remains
the macOS IDEA workspace setup authority. This decision adds an explicit,
read-only recovery route for checkouts that have not yet been prepared and
for supported headless installations.

## Decision

Every typed semantic command is admitted against the exact normalized
workspace root requested by the caller. Admission classifies that root as a
primary checkout, linked worktree, disposable checkout, standalone Gradle
workspace, or unsupported project before selecting semantic state. A Gradle
build marker is required before any descriptor can participate in admission;
plugin metadata or an injected exact-root descriptor cannot turn a non-Gradle
directory into a supported project.

A temporary clone with its own `.git` directory remains disposable when it is
under the operating system temporary root and has no admitted semantic state.
The presence of `.git` alone is not durable evidence that the checkout is the
primary workspace.

IDEA state is usable only when plugin metadata names that exact root and the
selected runtime descriptor names that exact root. Sharing a Git common
directory, branch name, or commit with another checkout is evidence about
repository ancestry, not authority to reuse the other checkout's semantic
state. Headless descriptors are exact-root state under the same rule.

On macOS, an unprepared Gradle root does not become a terminal setup error.
`kast agent verify` returns a typed unavailable-evidence result with:

- the requested backend and normalized workspace root;
- the checkout classification;
- an empty source-module set because no backend supplied evidence;
- explicit limitations and unavailable evidence quality;
- a next action to open that exact root in IntelliJ IDEA or Android Studio
  with the Homebrew-coupled Kast plugin; and
- a headless alternative only when using the supported headless distribution
  against that exact root.

These next actions are guidance, not side effects. Verification does not copy
metadata, run `kast setup`, start an IDE, repair an install, alter global
configuration, mutate a Homebrew receipt, or start a headless runtime.
Verification reuses an already ready exact-root runtime only. Its inspection
policy preserves descriptor-registry bytes, including dead entries; pruning
is reserved for lifecycle paths that are authorized to mutate runtime state.
Unsupported non-Gradle projects fail separately and do not receive misleading
workspace-preparation guidance.

The unprepared/headless route admits read-only verification, symbol, and
diagnostics workflows. It is not mutation authority. On macOS every applied
public mutation family still requires valid exact-root plugin preparation,
including when `--backend=headless` is selected. Mutation plans remain
read-only; applied rename, add-file, add-declaration, add-implementation,
add-statement, and replace-declaration commands fail with
`SEMANTIC_MUTATION_AUTHORITY_REQUIRED` before descriptor discovery, runtime
status, capabilities, or any other backend request when that authority is
absent. Automatic and default-backend mutation routes use the same preflight.

Automatic backend selection is allowed only when at most one backend kind is
ready for the exact root. A sole ready backend is selected even when it differs
from the host fallback. If IDEA and headless are both ready, admission fails
with `SEMANTIC_BACKEND_AMBIGUOUS` and structured candidate evidence. An explicit
`--backend` or configured default remains authoritative.

After admission succeeds, `kast agent verify` projects a compact semantic
workspace evidence object from runtime status and capabilities. The object
identifies the selected backend, exact runtime workspace root, source modules,
capability limitations, and evidence quality. Symbol resolution and
diagnostics continue through the same admitted exact-root session so the
verification result and later evidence cannot silently refer to different
checkouts.

## Supported paths

| Host and workspace state | Supported action | Authority and mutation rule |
| --- | --- | --- |
| macOS, exact root prepared | Use `--backend=idea` or an explicit backend | Plugin metadata and the exact-root descriptor are authoritative; applied mutation is permitted through the existing apply gates |
| macOS, exact root unprepared | Open that exact root with the installed Kast plugin, then rerun verification | The CLI reports the action but performs no setup or launch |
| Supported headless distribution | Use `--backend=headless` with the exact root | An already ready exact-root runtime is reused for verification; symbol and diagnostics may use the installed distribution; verification does not start, install, repair, or prune descriptor state |
| More than one ready exact-root backend | Select `--backend=idea` or `--backend=headless` | Automatic routing fails with candidate evidence instead of preferring an OS/backend |
| Unsupported non-Gradle root | Choose a Kotlin Gradle workspace | No backend is started and no preparation is suggested |

Read-only `symbol` and `diagnostics` workflows are supported after either the
prepared IDEA path or the headless path admits the exact root. Existing apply
gates remain in force, and macOS applied mutations additionally require valid
exact-root plugin metadata regardless of the selected backend.

## Source owners

- Workspace classification and exact-root admission:
  `cli-rs/src/runtime/workspace_admission.rs`
- Descriptor isolation and runtime status validation:
  `cli-rs/src/runtime/inspect.rs`
- Typed agent verification evidence and unavailable-route output:
  `cli-rs/src/agent/`
- Applied-mutation authority classification: `cli-rs/src/agent/request.rs` and
  `cli-rs/src/runtime/workspace_admission.rs`
- macOS plugin metadata validation: `cli-rs/src/self_mgmt.rs`
- Public command and troubleshooting guidance: `docs/reference/agent-commands.md`
  and `docs/troubleshoot.md`
- Integration scenarios: `cli-rs/tests/semantic_workspace_admission_smoke.rs`

## Validation gates

At minimum, changes to this contract run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test semantic_workspace_admission_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test runtime_lifecycle_smoke --test runtime_backend_smoke --test agent_diagnostics_smoke
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
./gradlew :analysis-api:test :analysis-server:test :backend-headless:test :backend-idea:test
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

Run contract generation in check mode when agent response schemas or catalog
artifacts change. Remove only repository-generated `.kotlin` directories after
Gradle verification.

## Change rule

Any future relaxation of exact-root matching, new automatic setup or install
behavior, or new host distribution path requires a superseding ADR. Tests that
prove two checkouts cannot share a descriptor are mandatory and may not be
replaced by branch- or commit-equivalence checks.
