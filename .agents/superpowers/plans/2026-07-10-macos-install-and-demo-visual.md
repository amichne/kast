# macOS Install Completion And Demo Visual Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the two remaining macOS Homebrew install failures and refresh `kast demo` with a restrained semantic visual system and new README recording.

**Architecture:** Keep `install.sh` as the sole user-environment orchestrator, make the formula CLI-only with explicit caveats, and store a formula-owned executable identity that Rust and Kotlin validate canonically. Add one semantic `PublicDemoTheme` boundary to the existing Ratatui renderer; do not change commands, evidence, layout flow, or keybindings.

**Tech Stack:** Bash, Homebrew Ruby formulae, Rust 2024, Ratatui/Crossterm, Kotlin/JUnit 5, Gradle, Asciinema, agg, ImageMagick, Playwright.

## Global Constraints

- Keep PR #328 and its existing recording commits intact; add narrow commits.
- Keep `kast demo` read-only and preserve JSON/TOON schemas and exit codes.
- Keep the existing 120x40 story and keyboard flow.
- Respect `NO_COLOR`; color must never be the only evidence signal.
- The root installer owns user-profile convergence; Homebrew `post_install` must not.
- Rust and Kotlin receipt containment must agree after canonicalization.

---

### Task 1: Remove User-Profile Work From Homebrew `post_install`

**Files:**
- Modify: `packaging/homebrew/Formula/kast.rb`
- Modify: `packaging/homebrew/scripts/test-formulas.py`
- Modify: `packaging/homebrew/README.md`
- Modify: `.github/scripts/test-macos-installer-contract.sh`

**Interfaces:**
- Consumes: public root `install.sh` orchestration
- Produces: CLI-only formula plus explicit `kast developer machine plugin` caveat

- [ ] Add failing formula assertions that reject `def post_install`, require a caveat naming `kast developer machine plugin`, and retain formula/plugin version coupling in release metadata.
- [ ] Run `python3 packaging/homebrew/scripts/test-formulas.py`; expect failure on the current `post_install` contract.
- [ ] Replace formula `post_install` with Homebrew caveats explaining that direct formula users must close JetBrains IDEs and run `kast developer machine plugin`; update tap documentation.
- [ ] Tighten the installer fake-tool log assertion so `install` and `update` each invoke plugin convergence exactly once after Homebrew.
- [ ] Run `python3 packaging/homebrew/scripts/test-formulas.py` and `.github/scripts/test-macos-installer-contract.sh`; expect both to pass.
- [ ] Commit as `fix: keep Homebrew post-install user agnostic`.

### Task 2: Make Receipt Identity Canonical And Cross-Language

**Files:**
- Modify: `cli-rs/src/install/homebrew_idea_plugin.rs`
- Modify: `cli-rs/src/install/jetbrains_profiles.rs`
- Modify: `cli-rs/src/install/tests.rs`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceipt.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/MacosHomebrewInstallReceiptTest.kt`

**Interfaces:**
- Produces: `HomebrewContext.cli_path == formula_prefix.join("bin/kast")`
- Preserves: canonical containment and symlink-escape rejection

- [ ] Add Rust tests proving a formula `bin/kast` symlink is accepted when it resolves beneath the formula prefix and rejected when it escapes.
- [ ] Add a Kotlin test reproducing the released failure: a lexical `/opt/homebrew/bin/kast`-style symlink outside the prefix that canonically resolves under it must load.
- [ ] Run the focused Rust and Kotlin tests; expect the Kotlin canonical-symlink test to fail on the lexical prefix check.
- [ ] Resolve `${formula_prefix}/bin/kast`, require canonical equality with `env::current_exe()`, and store the formula-owned spelling in the receipt.
- [ ] Remove Kotlin's lexical containment precheck and retain existence, executable, canonical containment, and version checks.
- [ ] Run `cargo test --manifest-path cli-rs/Cargo.toml --locked install::tests` and `./gradlew :backend-idea:test --tests '*MacosHomebrewInstallReceiptTest'`; expect pass.
- [ ] Commit as `fix: unify Homebrew receipt identity`.

### Task 3: Add The Semantic Signal Demo Theme

**Files:**
- Create: `cli-rs/src/demo/public_theme.rs`
- Modify: `cli-rs/src/demo.rs`
- Modify: `cli-rs/src/demo/public_rendering.rs`
- Modify: `cli-rs/src/demo/tests.rs`
- Modify: `cli-rs/tests/demo_smoke.rs`

**Interfaces:**
- Produces: `PublicDemoTheme::detect()` and semantic style accessors for default, muted, emphasis, focus, compiler, index, success, warning, danger, selection, and read-only roles
- Consumes: existing `PublicDemoApp` and snapshot types without model changes

- [ ] Add renderer-buffer tests for semantic badges, focused selection rail, chapter markers, contextual keycaps, and monochrome output with `NO_COLOR=1`.
- [ ] Run focused demo tests; expect failures because the semantic theme and new visual labels do not exist.
- [ ] Implement `PublicDemoTheme` with restrained ANSI/256-color styles and a no-color monochrome mapping.
- [ ] Refactor only `public_rendering.rs` to use rounded borders, evidence/read-only badges, ranked story hierarchy, current chapter emphasis, compiler/index distinction, safe plan styling, and styled footer keycaps.
- [ ] Run `cargo test --manifest-path cli-rs/Cargo.toml --locked demo`; expect focused tests and real-PTY source immutability to pass.
- [ ] Verify manually at 80x24 and 120x40 with the real full-evidence backend.
- [ ] Commit as `feat: give kast demo semantic visual language`.

### Task 4: Refresh The Audited Recording And Publish

**Files:**
- Replace: `docs/assets/demo/kast-demo.cast`
- Replace: `docs/assets/demo/kast-demo.gif`
- Modify if necessary: `README.md`

**Interfaces:**
- Consumes: full IDEA evidence and the semantic-themed TUI
- Produces: updated inline GitHub demo and exact-head PR proof

- [ ] Close IntelliJ, run the source-built disposable install/update path, and verify the receipt is accepted without invoking a workaround path.
- [ ] Reopen the real checkout and wait for IDEA `READY` plus `referenceIndexReady: true`.
- [ ] Run full JSON demo preflight and record tracked Kotlin hashes.
- [ ] Record the same 120x40 story through identity, relationships, impact, safety, and `KastStoryPreview`; verify no source hash changes.
- [ ] Render with agg, remove blank intro/outro frames, and validate canvas, duration, frames, and size with ImageMagick.
- [ ] Run docs contracts, `zensical build --clean`, Rust fmt/clippy/tests, focused Gradle tests, installer/formula contracts, and `git diff --check`.
- [ ] Commit as `docs: refresh semantic demo recording`.
- [ ] Push PR #328, verify the GitHub README in Playwright, babysit exact-head checks, merge when terminal green, and close IntelliJ.
