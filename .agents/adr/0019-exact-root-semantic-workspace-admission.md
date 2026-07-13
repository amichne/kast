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
workspace, or unsupported project before selecting semantic state.

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
configuration, or mutate a Homebrew receipt. Unsupported non-Gradle projects
fail separately and do not receive misleading workspace-preparation guidance.

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
| macOS, exact root prepared | Use `--backend=idea` or automatic selection | Plugin metadata and exact-root IDEA descriptor are authoritative |
| macOS, exact root unprepared | Open that exact root with the installed Kast plugin, then rerun verification | The CLI reports the action but performs no setup or launch |
| Supported headless distribution | Use `--backend=headless` with the exact root | Existing manifest-backed headless runtime is used; verification does not install or repair it |
| Unsupported non-Gradle root | Choose a Kotlin Gradle workspace | No backend is started and no preparation is suggested |

Read-only `symbol` and `diagnostics` workflows are supported after either the
prepared IDEA path or the headless path admits the exact root. This decision
does not widen mutation authority: IDEA-only or plugin-prepared operations
still require valid plugin metadata on macOS, and existing apply gates remain
in force.

## Source owners

- Workspace classification and exact-root admission:
  `cli-rs/src/runtime/workspace_admission.rs`
- Descriptor isolation and runtime status validation:
  `cli-rs/src/runtime/inspect.rs`
- Typed agent verification evidence and unavailable-route output:
  `cli-rs/src/agent/`
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
