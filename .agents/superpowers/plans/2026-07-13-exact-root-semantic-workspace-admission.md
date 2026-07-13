# Exact-root semantic workspace admission implementation plan

**Goal:** Make unprepared temporary checkouts return an actionable,
non-mutating semantic route while ensuring every admitted session and its
reported evidence belong to the exact requested workspace root.

**Architecture:** Add a typed runtime admission model that separates checkout
topology, preparation state, selected backend, and supported next actions.
Agent commands consult that model before opening a session. Runtime descriptor
selection becomes exact-root only, and verification derives a compact evidence
summary from the admitted runtime status and capabilities.

**Tech stack:** Rust 2024, Clap, serde, JSON/TOON output, tempfile-based Rust
integration tests, existing Kotlin/Gradle backends, Zensical documentation.

## Constraints

- Preserve Homebrew and plugin authority on macOS.
- Do not run `kast setup`, repair/install flows, or any command that writes
  global install state.
- Do not copy primary-checkout metadata into worktrees.
- Do not launch an IDE from verification.
- Keep all descriptor and runtime-status identity checks exact-root.
- Test behavior before implementation and observe the expected failure.

## Task 1: Specify workspace topology and unavailable routes

**Files:**

- Create: `cli-rs/tests/semantic_workspace_admission_smoke.rs`
- Modify: `cli-rs/tests/support/mod.rs`
- Create: `cli-rs/src/runtime/workspace_admission.rs`
- Modify: `cli-rs/src/runtime.rs`

1. Add integration fixtures for a primary checkout, linked worktree,
   disposable clone-shaped checkout, standalone Gradle project, and
   unsupported project.
2. Assert that unprepared supported roots return a stable structured error
   containing backend, normalized root, topology, empty source modules,
   limitations, unavailable evidence quality, and non-mutating next actions.
3. Assert that unsupported projects return a distinct error without IDEA
   preparation guidance.
4. Run the new integration test and confirm the missing contract fails.
5. Implement the smallest typed topology and admission model that makes the
   scenarios pass.

## Task 2: Enforce exact-root runtime identity

**Files:**

- Modify: `cli-rs/src/runtime/inspect.rs`
- Delete or reduce: `cli-rs/src/runtime/workspace_identity.rs`
- Modify: `cli-rs/src/runtime/tests.rs`
- Modify: `cli-rs/tests/semantic_workspace_admission_smoke.rs`

1. Add a regression test with two worktrees sharing Git common-dir and
   branch/HEAD facts but different roots.
2. Confirm the current IDEA compatibility match selects the other root.
3. Require the normalized descriptor root to equal the requested root for
   IDEA and headless backends.
4. Reject a runtime-status response whose backend or root disagrees with its
   descriptor.
5. Re-run focused runtime tests and keep the exact-root regression green.

## Task 3: Project semantic verification evidence

**Files:**

- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/runtime/rpc.rs`
- Modify: `cli-rs/tests/semantic_workspace_admission_smoke.rs`
- Modify: `cli-rs/tests/agent_output_format_smoke.rs`

1. Add a fake exact-root backend scenario with source modules, readiness,
   reference-index state, and capabilities.
2. Assert `agent verify` reports backend, exact root, source modules,
   limitations, and compiler-backed evidence quality in JSON and decodable
   TOON.
3. Confirm the new tests fail because verification currently exposes only
   step payloads.
4. Carry the successful admission into the session and derive the typed
   evidence summary from returned runtime status and capabilities.
5. Keep large backend payloads nested behind existing preview behavior.

## Task 4: Preserve read-only workflow and macOS authority boundaries

**Files:**

- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/runtime/workspace.rs`
- Modify: `cli-rs/src/self_mgmt.rs`
- Modify: `cli-rs/tests/semantic_workspace_admission_smoke.rs`
- Modify: `cli-rs/tests/runtime_backend_smoke.rs`
- Modify: `cli-rs/tests/ready_repair_smoke.rs`

1. Assert an admitted exact-root session serves read-only `symbol` and
   `diagnostics` requests.
2. Assert an unprepared IDEA mutation path remains rejected by plugin
   authority.
3. Assert explicit headless selection never falls back to another root or an
   IDEA descriptor.
4. Implement only the routing needed by those tests; retain all existing
   apply and setup gates.

## Task 5: Document the supported operator paths

**Files:**

- Modify: `docs/reference/agent-commands.md`
- Modify: `docs/troubleshoot.md`
- Modify if required by source ownership: `README.md`

1. Add reference facts for the verification evidence fields and exact-root
   routing.
2. Add a troubleshooting how-to for an unprepared primary, linked, or
   disposable checkout: open that exact root with the plugin on macOS, or use
   an already installed supported headless distribution.
3. State the limitations and non-mutation guarantees plainly.
4. Run docs content/navigation contracts and `zensical build --clean`.

## Task 6: Verify, review, and hand off

1. Run the focused Rust tests from ADR 0019 after every red-green slice.
2. Run the full locked Cargo suite, formatting, and clippy.
3. Run relevant Gradle backend/API tests and remove only generated `.kotlin`
   directories afterward.
4. Run contract generation/check and docs/generator gates when affected.
5. Review `git diff`, `git diff --check`, and status for unrelated files.
6. Write `.agent-turn/issue-336-report.md` with commits, commands, outcomes,
   limitations, and residual risks.
7. Commit coherent conventional commits, do not push, and leave the worktree
   clean.
