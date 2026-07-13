# Relative Agent File Paths Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept workspace-relative Kotlin target paths on typed agent commands and send only canonical workspace-contained absolute paths to backend requests.

**Architecture:** A Rust agent-boundary normalizer parses the effective workspace and untrusted target strings into canonical path types. Diagnostics and mutation dispatch consume only the trusted strings, while docs and installed guidance teach relative construction against explicit `--workspace-root`.

**Tech Stack:** Rust 2024, Clap, serde/serde_json, `std::fs`, Cargo integration tests, Markdown contract checks.

## Global Constraints

- Relative semantic targets require explicit `--workspace-root`.
- Absolute and relative targets must resolve inside the canonical effective workspace.
- Existing safe symlinks resolve to their real in-workspace target; lexical escapes, broken symlinks, and symlink escapes fail closed.
- Missing `.kt` and `.kts` leaves remain valid for deleted-file refresh and add-file planning.
- Directories, special files, unreadable prefixes, and non-Kotlin extensions fail before backend dispatch.
- `--file-hint` remains a compiler-identity hint and `--content-file` remains a payload source.
- Diagnostics, refresh, plans, JSON, TOON, and human output use the same canonical path strings.
- Preserve target order and existing absolute in-workspace behavior.
- Do not run `kast setup` on macOS.
- Work only on `feature/issue-341-relative-file-paths`; do not push.

---

### Task 1: Typed Canonical Kotlin Path Boundary

**Files:**
- Create: `cli-rs/src/agent/path.rs`
- Modify: `cli-rs/src/agent.rs`

**Interfaces:**
- Consumes: `AgentRuntimeArgs.workspace_root` and untrusted `&str` target paths.
- Produces: `AgentFilePathNormalizer::from_runtime(&AgentRuntimeArgs) -> Result<AgentFilePathNormalizer, AgentError>`, `normalize(&self, &str) -> Result<CanonicalKotlinFilePath, AgentError>`, and `CanonicalKotlinFilePath::rpc_path(&self) -> &str`.

- [ ] **Step 1: Write failing unit tests for the trust boundary**

Add `#[cfg(test)] mod agent_file_path_tests` in `path.rs`. Construct temporary workspaces and assert the wished-for API:

```rust
#[test]
fn relative_kotlin_file_resolves_against_explicit_workspace() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/with spaces/App.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&file, "class App\n").expect("source");
    let runtime = AgentRuntimeArgs {
        workspace_root: Some(workspace.clone()),
        backend_name: None,
    };

    let normalizer = AgentFilePathNormalizer::from_runtime(&runtime).expect("normalizer");
    let actual = normalizer
        .normalize("src/with spaces/App.kt")
        .expect("canonical target");

    assert_eq!(actual.rpc_path(), file.canonicalize().expect("canonical file").to_str().expect("UTF-8"));
}
```

Add separate tests for an existing absolute path, `.kts`, a missing `.kt` leaf,
relative input without explicit workspace, `../` escape, absolute outside path,
unsupported extension, a directory named `Directory.kt`, an in-workspace
symlink, an escaping symlink, and a broken symlink. Gate symlink construction
with platform-specific test helpers.

- [ ] **Step 2: Run the unit tests and verify red**

Run:

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked agent_file_path_tests
```

Expected: compilation fails because `AgentFilePathNormalizer` and
`CanonicalKotlinFilePath` do not exist.

- [ ] **Step 3: Implement typed root and target normalization**

Add these owned types to `path.rs`:

```rust
struct AgentFilePathNormalizer {
    declared_root: PathBuf,
    canonical_root: PathBuf,
    relative_targets_allowed: bool,
}

struct CanonicalKotlinFilePath {
    path: PathBuf,
    rpc_path: String,
}
```

`from_runtime` resolves the effective workspace using
`config::resolve_workspace_root`, lexically normalizes it, canonicalizes it,
and requires a directory. `normalize` must:

1. reject empty input and a relative input without explicit workspace;
2. reject input extensions other than case-sensitive `.kt` or `.kts`;
3. join relative input to the declared root and lexically resolve components;
4. reject relative lexical escape before filesystem resolution;
5. walk upward through `ErrorKind::NotFound`, recording missing suffixes;
6. canonicalize the deepest existing prefix and reject unresolved symlinks;
7. require an existing prefix with a missing suffix to be a directory;
8. append missing suffix components to the canonical prefix;
9. require the final canonical path to start with `canonical_root`;
10. require an existing target to be a regular file;
11. require the canonical target extension to be `.kt` or `.kts`;
12. reject a non-UTF-8 RPC path rather than using lossy conversion.

Convert every failure into `AgentError` with one of:
`AGENT_WORKSPACE_INVALID`, `AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE`,
`AGENT_FILE_OUTSIDE_WORKSPACE`, `AGENT_FILE_SYMLINK_UNSAFE`,
`AGENT_FILE_KIND_UNSUPPORTED`, or `AGENT_FILE_PATH_UNREADABLE`. Populate
`input`, `workspaceRoot`, and `resolvedPath` details when those facts exist.

Include `agent/path.rs` before `agent/dispatch.rs` in `agent.rs`, and import
`crate::config`, `std::fs`, and the path/IO types only in the owning part.

- [ ] **Step 4: Run the unit tests and verify green**

Run the Task 1 test command again.

Expected: every typed path case passes and no test starts a runtime session.

- [ ] **Step 5: Commit the trust boundary**

```bash
git add cli-rs/src/agent.rs cli-rs/src/agent/path.rs
git diff --cached --check
git commit -m "feat: canonicalize agent Kotlin paths"
```

---

### Task 2: Canonical Diagnostics Requests and Output

**Files:**
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/tests/agent_diagnostics_smoke.rs`

**Interfaces:**
- Consumes: Task 1 `AgentFilePathNormalizer` and ordered diagnostics inputs.
- Produces: identical canonical `filePaths` for `raw/workspace-refresh`, `raw/diagnostics`, and command-level `result.filePaths`.

- [ ] **Step 1: Write failing fake-daemon integration tests**

Extend the fake backend so it records full requests as well as method names.
Add a scenario with two files, including `src/with spaces/Second.kt`, and invoke
diagnostics with two relative `--file-path` arguments. Assert:

```rust
assert_eq!(refresh["params"]["filePaths"], json!([first, second]));
assert_eq!(diagnostics["params"]["filePaths"], json!([first, second]));
assert_eq!(document["result"]["filePaths"], json!([first, second]));
```

Run the same successful relative scenario with `json`, `toon`, and `human` and
decode each through the existing helpers. Add an absolute-path compatibility
scenario. Add a deleted relative `.kt` scenario where no file exists but the
fake backend returns `MISSING_ON_DISK`; assert refresh receives the canonical
missing path and the command fails with typed incomplete evidence rather than
path parsing failure.

Add pre-dispatch cases for `../Outside.kt`, an unsupported `.java` path, and an
escaping symlink. Bind a nonblocking fake listener and assert it receives no
request before the command exits with the corresponding structured path code.

- [ ] **Step 2: Run focused integration tests and verify red**

Run:

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke relative
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke deleted
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke rejects
```

Expected: relative scenarios fail because raw input still reaches the backend;
the command-level canonical `filePaths` field is absent.

- [ ] **Step 3: Normalize once before diagnostics step construction**

At the start of `execute_agent_diagnostics`, construct one normalizer and map
the ordered inputs into one `Vec<String>`:

```rust
let normalizer = match AgentFilePathNormalizer::from_runtime(&args.runtime) {
    Ok(normalizer) => normalizer,
    Err(error) => return error_envelope("agent/diagnostics".to_string(), None, error),
};
let file_paths = match normalizer.normalize_all(&args.file_paths) {
    Ok(file_paths) => file_paths,
    Err(error) => return error_envelope("agent/diagnostics".to_string(), None, error),
};
```

Build both steps from `&file_paths`. After `execute_agent_steps`, insert
`filePaths: file_paths` into the result object even when semantic analysis is
incomplete, so every output shape reports what was attempted. Do not start a
runtime session until every input has normalized successfully.

- [ ] **Step 4: Run the diagnostics integration suite**

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke
```

Expected: all legacy completeness scenarios and new path scenarios pass.

- [ ] **Step 5: Commit diagnostics behavior**

```bash
git add cli-rs/src/agent/dispatch.rs cli-rs/tests/agent_diagnostics_smoke.rs
git diff --cached --check
git commit -m "feat: accept relative diagnostic paths"
```

---

### Task 3: Canonical File-Target Mutation Plans

**Files:**
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/tests/agent_command_surface_smoke.rs`

**Interfaces:**
- Consumes: Task 1 normalizer for add-file `file_path` and optional scoped-mutation `inside_file`.
- Produces: canonical `params.filePath` and `params.placement.scope.insideFile` in read-only plans and applied requests.

- [ ] **Step 1: Write failing plan tests**

Create a temporary workspace, an external snippet payload, and relative target
`src/generated/New File.kt`. Run add-file, add-declaration, and
add-implementation without `--apply`, each with explicit `--workspace-root`.
Assert the plan requests contain `workspace.join(target)` as canonical strings.
Assert the external absolute `contentFile` remains unchanged. Add a separate
test proving a relative target without `--workspace-root` returns
`AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE`.

- [ ] **Step 2: Run the plan tests and verify red**

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke relative_file
```

Expected: plan request fields still contain the relative input.

- [ ] **Step 3: Normalize mutation targets before constructing plans**

In `execute_agent_add_file`, normalize `args.file_path` and pass only its RPC
string into `params.filePath`. In `execute_agent_scoped_mutation`, normalize
`inside_file` when present before calling `scoped_placement_params`; leave
named scopes unchanged. Convert any path error into the command's structured
agent envelope before `execute_agent_mutation`.

Update Clap help from “Absolute path” to “Absolute or workspace-root-relative
path” for semantic target flags. Do not change content-file help or behavior.

- [ ] **Step 4: Run command-surface and CLI core tests**

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke --test cli_core_smoke
```

Expected: all plan and public-surface tests pass.

- [ ] **Step 5: Commit mutation plan behavior**

```bash
git add cli-rs/src/agent/dispatch.rs cli-rs/src/cli/agent.rs cli-rs/tests/agent_command_surface_smoke.rs
git diff --cached --check
git commit -m "feat: canonicalize mutation file targets"
```

---

### Task 4: Installed Guidance, Docs, and Full Proof

**Files:**
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Modify: `cli-rs/resources/kast-skill/references/runbook.md`
- Modify: `cli-rs/resources/kast-skill/references/workflows.md`
- Modify: `docs/reference/agent-commands.md`
- Modify: `cli-rs/tests/packaged_content_smoke.rs`
- Create: `.agent-turn/issue-341-report.md` (ignored evidence)

**Interfaces:**
- Consumes: Task 2 and Task 3 public behavior.
- Produces: installed and published examples using `src/main/kotlin/App.kt` with explicit `--workspace-root "$PWD"`, plus final verification evidence.

- [ ] **Step 1: Write failing packaged-guidance assertions**

In `packaged_content_smoke.rs`, assert the installed skill and references
contain:

```text
kast agent diagnostics --file-path src/main/kotlin/App.kt --workspace-root "$PWD"
kast agent add-file --file-path src/main/kotlin/NewType.kt
kast agent add-declaration --inside-file src/main/kotlin/App.kt
```

Also assert the semantic examples no longer require
`$PWD/src/main/kotlin/App.kt` or `<absolute.kt>`.

- [ ] **Step 2: Run packaged-content tests and verify red**

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
```

Expected: relative guidance assertions fail against the current absolute-only examples.

- [ ] **Step 3: Update authored guidance and published reference**

Replace diagnostic semantic targets with workspace-relative paths whenever the
same command already supplies `--workspace-root "$PWD"`. Change mutation target
placeholders from `<absolute.kt>` to `<workspace-relative.kt>` and add one real
relative add-file/declaration example. Keep payload `--content-file` examples
unchanged. Update `docs/reference/agent-commands.md` with the same rule and
state that output reports canonical absolute paths.

- [ ] **Step 4: Run focused resource and contract checks**

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
```

Expected: tests and checks exit zero with no stale generated output.

- [ ] **Step 5: Run full verification**

```bash
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
git status --short --branch
```

Expected: all commands exit zero and only the ignored evidence report is
untracked. Remove generated `.kotlin/` if a Gradle/docs tool created it; do not
remove unrelated files.

- [ ] **Step 6: Record and commit the final slice**

Write `.agent-turn/issue-341-report.md` with the isolated Kast failure,
red-green evidence, commits, commands, counts, and remaining concerns. Then:

```bash
git add cli-rs/resources/kast-skill/SKILL.md \
  cli-rs/resources/kast-skill/references/quickstart.md \
  cli-rs/resources/kast-skill/references/runbook.md \
  cli-rs/resources/kast-skill/references/workflows.md \
  docs/reference/agent-commands.md \
  cli-rs/tests/packaged_content_smoke.rs
git diff --cached --check
git commit -m "docs: teach relative agent file paths"
```
