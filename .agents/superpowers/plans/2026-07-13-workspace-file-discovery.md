# Workspace File Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `kast agent workspace-files` as a bounded, typed, exact-root
discovery command backed by compiler project-model and source-index evidence,
with an uncapped internal inventory reusable by issue #340.

**Architecture:** A new Rust `workspace_inventory` unit reads all available
backend and exact-root index candidates without applying the public limit,
then classifies cross-source drift and annotations conservatively. The public
agent layer validates filters, applies deterministic bounds, projects ADR
0020 result views, and uses a typed route registry to make capability
advertisement depend on a callable Clap path.

**Tech Stack:** Rust 2024, Clap, serde/serde_json, rusqlite, glob, Git porcelain
v2, existing JSON/TOON output, tempfile-based integration fixtures, Markdown,
and Zensical.

## Global Constraints

- Rebase this branch onto the merged issue #337 result before writing
  production code; use its projection/view types instead of recreating them.
- Do not change Kotlin request/response models, backend implementations,
  analysis-server dispatch, source-index schema, generated RPC catalogs, or
  generated protocol artifacts.
- Keep `raw/workspace-files` internal; the public path is exactly
  `kast agent workspace-files`.
- Admit and report the exact normalized workspace root under ADR 0019.
- Never use recursive filesystem discovery or a Git file list as candidate
  authority.
- Never emit `INDEX_ONLY` unless backend evidence proves exhaustive absence
  for the relevant module/path.
- Keep the internal inventory uncapped by public filters and `--limit`; retain
  upstream backend truncation as typed partial evidence.
- Default `--limit` to 20, reject values outside 1 through 200, and keep the
  default compact result below 120 lines and 1,500 estimated tokens.
- Preserve unrelated worktree changes and commit each red-green slice
  independently with a conventional commit.
- Add a scoped `AGENTS.md` for the new production ownership boundary and do
  not add ADR/spec/plan files to published Zensical navigation.

---

### Task 1: Establish the typed public CLI boundary

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/projection/view.rs`
- Create: `cli-rs/src/agent/workspace_files.rs`
- Create: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/cli_core_smoke.rs`

**Interfaces:**

- Consumes: ADR 0020 `AgentResultView`, typed `AgentRuntimeArgs`, and the
  existing agent envelope/output path.
- Produces: `AgentCommand::WorkspaceFiles(AgentWorkspaceFilesArgs)`, typed
  filter arguments, `AgentWorkspaceFilesField`, and a temporary structured
  unavailable result that later tasks replace with inventory execution.

- [ ] **Step 1: Write the failing public-command and usage tests**

Add `workspace-files` to the visible agent-help assertion and remove it from
the retired-alias list in `cli_core_smoke.rs`. In
`agent_workspace_files_smoke.rs`, assert the command parses all documented
filters and rejects invalid limits, parent traversal, absolute path prefixes,
regex globs, blank selectors, and incompatible result-view flags:

```rust
#[test]
fn workspace_files_is_public_and_rejects_untyped_bounds() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let help = kast(&home, &config_home)
        .args(["agent", "workspace-files", "--help"])
        .output()
        .expect("workspace-files help");
    assert!(help.status.success(), "{}", String::from_utf8_lossy(&help.stderr));

    for invalid in [
        vec!["--limit", "0"],
        vec!["--limit", "201"],
        vec!["--path-prefix", "../other"],
        vec!["--path-prefix", "/absolute"],
        vec!["--glob", "regex:.*\\.kt"],
        vec!["--fields", "path", "--count"],
    ] {
        let output = kast(&home, &config_home)
            .args(["agent", "workspace-files"])
            .args(invalid)
            .output()
            .expect("invalid workspace-files command");
        assert_eq!(output.status.code(), Some(2));
    }
}
```

- [ ] **Step 2: Run the focused tests and observe the red state**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke smoke_core_cli_commands
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke workspace_files_is_public_and_rejects_untyped_bounds
```

Expected: the first test reports that `workspace-files` is still absent or
retired, and the second fails because Clap does not recognize the command.

- [ ] **Step 3: Add typed argument and field definitions**

Add the command variant and these typed boundaries in `cli/agent.rs`. Keep
validation in `FromStr` constructors, not in dispatch branches:

```rust
#[derive(Debug, Args, Clone)]
pub struct AgentWorkspaceFilesArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub module: Option<WorkspaceModuleFilter>,
    #[arg(long = "source-set")]
    pub source_set: Option<WorkspaceSourceSetFilter>,
    #[arg(long, value_enum)]
    pub kind: Option<WorkspaceFileKindFilter>,
    #[arg(long)]
    pub package: Option<WorkspacePackageFilter>,
    #[arg(long, value_enum)]
    pub dirty: Option<WorkspaceDirtyFilter>,
    #[arg(long, value_enum)]
    pub drift: Option<WorkspaceDriftFilter>,
    #[arg(long = "path-prefix")]
    pub path_prefix: Option<WorkspacePathPrefix>,
    #[arg(long)]
    pub glob: Option<WorkspaceGlobFilter>,
    #[arg(long, default_value_t = WorkspaceFileLimit::default())]
    pub limit: WorkspaceFileLimit,
    #[command(flatten)]
    pub view: AgentWorkspaceFilesViewArgs,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentWorkspaceFilesField {
    Path,
    Module,
    SourceSet,
    Kind,
    Package,
    Index,
    Drift,
    Dirty,
    Evidence,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("workspace_files_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentWorkspaceFilesViewArgs {
    #[arg(long)]
    pub verbose: bool,
    #[arg(long)]
    pub explain: bool,
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentWorkspaceFilesField>,
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum WorkspaceDirtyFilter {
    Clean,
    Dirty,
    Unknown,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum WorkspaceDriftFilter {
    None,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    Unknown,
}
```

Use newtypes with private fields for module, source set, package, path prefix,
glob, and limit. `WorkspaceFileLimit::from_str` must accept only 1 through 200.
`WorkspacePathPrefix::from_str` must normalize slashes and reject root,
absolute, `.`-only, and parent components. `WorkspaceGlobFilter::from_str`
must reject `regex:` and compile `glob::Pattern` once.

- [ ] **Step 4: Wire the command to a typed temporary failure**

Add `execute_agent_workspace_files` to `agent/workspace_files.rs`, include it
from `agent.rs`, add the exhaustive dispatch branch, and add a
`WorkspaceFiles` projection request variant in `projection/view.rs`. Until the
inventory exists, return:

```rust
error_envelope(
    "agent/workspace-files".to_string(),
    None,
    agent_error(
        "WORKSPACE_FILE_DISCOVERY_UNAVAILABLE",
        "Workspace inventory collection is not available.",
    ),
)
```

This step exists only to make Clap and exhaustive Rust matches compile; do not
claim discovery works yet.

- [ ] **Step 5: Run the focused tests and verify green parsing behavior**

Run the two commands from Step 2. Expected: both pass, and the command's valid
invocation reaches the typed temporary execution error rather than a Clap
unknown-command error.

- [ ] **Step 6: Commit the CLI boundary**

```console
git add cli-rs/src/cli/agent.rs cli-rs/src/agent.rs cli-rs/src/agent/dispatch.rs cli-rs/src/agent/projection/view.rs cli-rs/src/agent/workspace_files.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/cli_core_smoke.rs
git commit -m "feat: add typed workspace file command boundary"
```

### Task 2: Build the uncapped exact-root source-index inventory

**Files:**

- Modify: `cli-rs/src/main.rs`
- Create: `cli-rs/src/workspace_inventory.rs`
- Create: `cli-rs/src/workspace_inventory/AGENTS.md`
- Create: `cli-rs/src/workspace_inventory/model.rs`
- Create: `cli-rs/src/workspace_inventory/index.rs`
- Create: `cli-rs/src/workspace_inventory/tests.rs`
- Modify: `cli-rs/tests/support/mod.rs`
- Create: `cli-rs/tests/support/workspace_files.rs`

**Interfaces:**

- Consumes: `config::workspace_database_path`,
  `source_index_db::configure_read_connection`,
  `SOURCE_INDEX_SCHEMA_VERSION`, and the current index path-prefix encoding.
- Produces: `WorkspaceRoot`, `WorkspaceFilePath`,
  `WorkspaceIndexSnapshot`, `IndexedWorkspaceFile`, `WorkspaceIndexRead`, and
  `read_workspace_index(&WorkspaceRoot) -> Result<WorkspaceIndexRead>`.

- [ ] **Step 1: Add failing index-reader and type-invariant tests**

Seed an exact-root database with indexed `.kt`, `.kts`, non-Kotlin, missing
metadata, `__kast_rel__/`, `__kast_abs__/`, outside-root, and 500 Kotlin rows.
Assert:

```rust
#[test]
fn index_snapshot_is_uncapped_typed_and_exact_root() {
    let fixture = WorkspaceInventoryFixture::new();
    fixture.seed_index_rows(500);
    fixture.seed_outside_root_row();
    fixture.seed_non_kotlin_row();

    let root = WorkspaceRoot::new(fixture.workspace()).expect("workspace root");
    let WorkspaceIndexRead::Available(snapshot) =
        read_workspace_index(&root).expect("index read")
    else {
        panic!("expected available index snapshot");
    };

    assert_eq!(snapshot.files.len(), 500);
    assert!(snapshot.files.iter().all(|file| file.path.is_within(&root)));
    assert!(snapshot.files.iter().any(|file| {
        file.kind == WorkspaceFileKind::KotlinScript
            && file.module.gradle_path() == Some(":build-logic")
            && file.source_set.as_ref().is_some_and(|value| value.as_str() == "main")
    }));
    assert_eq!(snapshot.excluded_out_of_root_count, 1);
}
```

Add separate tests for invalid schema, missing database, malformed package
metadata, and `WorkspaceFilePath` rejecting traversal and symlink escape.

- [ ] **Step 2: Run the unit filter and observe missing inventory types**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
```

Expected: compilation fails because `workspace_inventory` and its domain types
do not exist.

- [ ] **Step 3: Define the inventory domain model**

Create a facade-only `workspace_inventory.rs` with explicit includes, and put
the types in `model.rs`:

```rust
#[derive(Debug, Clone)]
pub(crate) struct WorkspaceIndexSnapshot {
    pub(crate) files: Vec<IndexedWorkspaceFile>,
    pub(crate) excluded_out_of_root_count: usize,
}

#[derive(Debug)]
pub(crate) enum WorkspaceIndexRead {
    Available(WorkspaceIndexSnapshot),
    Unavailable {
        limitation: WorkspaceInventoryLimitation,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceInventoryLimitationCode {
    BackendWorkspaceFilesUnavailable,
    BackendEnumerationTruncated,
    SourceIndexUnavailable,
    SourceIndexSchemaUnsupported,
    DirtyStateUnavailable,
    PackageMetadataUnavailable,
    WorkspacePathExcluded,
    ProjectModelOwnershipUnknown,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct WorkspaceInventoryLimitation {
    pub(crate) code: WorkspaceInventoryLimitationCode,
    pub(crate) message: String,
    pub(crate) affected_count: usize,
    pub(crate) module_names: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceFileKind {
    KotlinSource,
    KotlinScript,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceFileIndexState {
    Indexed,
    NotIndexed,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum WorkspaceFileDrift {
    None,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    Unknown,
}
```

Add validated newtypes for the exact root, relative/absolute path pair,
backend module name, Gradle module path, source set, and package. Expose only
constructors that prove their invariants. Keep all fields private unless a
consumer requires a read-only accessor.

- [ ] **Step 4: Implement the read-only all-row index query**

Open the configured database with
`SQLITE_OPEN_READ_ONLY | SQLITE_OPEN_URI`, configure the connection, verify the
schema version and required tables, and run the exact SQL from the design.
Decode paths through one function:

```rust
fn indexed_path(
    root: &WorkspaceRoot,
    dir_path: String,
    filename: String,
) -> Result<WorkspaceFilePath> {
    let candidate = match dir_path.strip_prefix("__kast_abs__/") {
        Some(absolute) => PathBuf::from(absolute).join(filename),
        None => {
            let relative = dir_path
                .strip_prefix("__kast_rel__/")
                .unwrap_or(&dir_path);
            relative
                .split('/')
                .filter(|segment| !segment.is_empty())
                .fold(root.as_path().to_path_buf(), PathBuf::join)
                .join(filename)
        }
    };
    WorkspaceFilePath::new(root, candidate)
}
```

Read every row before returning. Do not accept a `limit` parameter. Map a
missing or incompatible database to typed `IndexWorkspaceCoverage::Unavailable`
evidence instead of an empty available snapshot.

- [ ] **Step 5: Add the scoped source-ownership guide**

Create `workspace_inventory/AGENTS.md` stating that this unit owns reusable,
uncapped, exact-root candidate composition; source limits must remain explicit;
public filtering/projection belongs in `agent`; and recursive filesystem/Git
candidate discovery is prohibited. List the focused Rust test command.

- [ ] **Step 6: Run focused tests and verify the uncapped snapshot**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
```

Expected: the high-cardinality test observes all 500 Kotlin rows, non-Kotlin
and outside-root rows are excluded with evidence, and schema/path invariant
tests pass.

- [ ] **Step 7: Commit the internal index boundary**

```console
git add cli-rs/src/main.rs cli-rs/src/workspace_inventory.rs cli-rs/src/workspace_inventory cli-rs/tests/support/mod.rs cli-rs/tests/support/workspace_files.rs
git commit -m "feat: add uncapped workspace index inventory"
```

### Task 3: Compose backend, filesystem, index, and dirty evidence

**Files:**

- Create: `cli-rs/src/workspace_inventory/backend.rs`
- Create: `cli-rs/src/workspace_inventory/dirty.rs`
- Create: `cli-rs/src/workspace_inventory/collect.rs`
- Modify: `cli-rs/src/workspace_inventory/model.rs`
- Modify: `cli-rs/src/workspace_inventory/tests.rs`
- Modify: `cli-rs/src/workspace_inventory.rs`
- Modify: `cli-rs/tests/support/workspace_files.rs`

**Interfaces:**

- Consumes: decoded `raw/workspace-files` result, `WorkspaceIndexSnapshot`,
  exact-root candidate paths, targeted `symlink_metadata`, and Git porcelain
  v2 output.
- Produces:
  `collect_workspace_inventory(WorkspaceInventoryInputs) -> Result<WorkspaceInventorySnapshot>`
  with no public filters or result cap.

- [ ] **Step 1: Write the failing drift truth-table tests**

Construct typed inputs directly and assert every ADR row. The critical
regressions are:

```rust
#[test]
fn incomplete_backend_never_proves_index_only() {
    for coverage in [
        BackendWorkspaceCoverage::Truncated {
            module_names: vec![BackendModuleName::new("app").expect("module")],
        },
        BackendWorkspaceCoverage::Unavailable {
            code: WorkspaceInventoryLimitationCode::BackendWorkspaceFilesUnavailable,
        },
    ] {
        let file = classify_candidate(candidate_inputs(|inputs| {
            inputs.backend_present = false;
            inputs.index_present = true;
            inputs.filesystem_present = true;
            inputs.backend_coverage = coverage;
        }));
        assert_eq!(file.index_state, WorkspaceFileIndexState::Indexed);
        assert_eq!(file.drift, WorkspaceFileDrift::Unknown);
    }
}
```

Also prove backend-only present files are `FILESYSTEM_ONLY`, complete-backend
index-only rows are `INDEX_ONLY`, agreed present rows are `NONE`, and missing
paths are `MISSING_ON_DISK`.

- [ ] **Step 2: Write failing Git and candidate-authority tests**

Create a real temporary Git worktree containing clean, modified, added,
deleted, renamed, untracked, and conflicted candidate paths. Assert porcelain
v2 maps them to distinct `WorkspaceFileDirtyState` variants. Add an unowned
`.kt` file that exists on disk and appears in Git status but in neither
backend nor index inputs; assert it is absent from the inventory.

- [ ] **Step 3: Run focused tests and observe missing composition**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
```

Expected: the new composition and dirty tests fail because the collector does
not exist.

- [ ] **Step 4: Decode and validate backend coverage**

Deserialize the existing raw result into strict Rust input types carrying
module name, source roots, dependency names, files, `filesTruncated`, and
`fileCount`. Reject blank module names, invalid absolute paths, duplicate paths
with conflicting module identities, a returned count greater than
`fileCount`, and `filesTruncated=false` when the response contradicts its own
count.

Represent coverage as `Complete`, `Truncated { module_names }`, or
`Unavailable { code }`. Preserve module records in the snapshot for #340; do
not flatten source roots or dependency names into strings without their module
owner.

- [ ] **Step 5: Implement candidate union and conservative classification**

Build the candidate key set exclusively from backend and index paths. Use this
classification function after targeted filesystem evidence is known:

```rust
fn classify_drift(input: &CandidateClassificationInput) -> WorkspaceFileDrift {
    if input.filesystem_state == WorkspaceFilesystemState::Missing {
        return WorkspaceFileDrift::MissingOnDisk;
    }
    match (input.backend_present, input.index_present) {
        (true, true) => WorkspaceFileDrift::None,
        (true, false) if input.index_available => WorkspaceFileDrift::FilesystemOnly,
        (false, true) if input.backend_proves_absence => WorkspaceFileDrift::IndexOnly,
        (true, false) | (false, true) | (false, false) => WorkspaceFileDrift::Unknown,
    }
}
```

Set `backend_proves_absence` only for complete coverage of the associated
module. If module association is missing while any backend module is
truncated, set it false. Record `PROJECT_MODEL_OWNERSHIP_UNKNOWN` for those
rows.

- [ ] **Step 6: Implement targeted filesystem and Git annotation**

Call `symlink_metadata` only for candidate paths. Canonicalize existing paths
and exclude canonical targets outside the exact root. Run:

```console
git -C <workspace-root> status --porcelain=v2 -z --untracked-files=all
```

Parse record types `1`, `2`, `u`, and `?`, including the extra original path
for rename records. Annotate only existing candidate keys. A successful Git
snapshot makes absent candidate records `CLEAN`; command failure makes all
states `UNKNOWN` and adds `DIRTY_STATE_UNAVAILABLE`.

- [ ] **Step 7: Verify the full internal collector**

Run the focused unit filter again. Expected: all drift, dirty-state, exact-root
containment, no-filesystem-discovery, and uncapped snapshot tests pass.

- [ ] **Step 8: Commit evidence composition**

```console
git add cli-rs/src/workspace_inventory.rs cli-rs/src/workspace_inventory cli-rs/tests/support/workspace_files.rs
git commit -m "feat: compose typed workspace file evidence"
```

### Task 4: Execute and project bounded public discovery

**Files:**

- Modify: `cli-rs/src/agent/workspace_files.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/projection/view.rs`
- Create: `cli-rs/src/agent/projection/workspace_files.rs`
- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`

**Interfaces:**

- Consumes: admitted exact-root runtime session,
  `collect_workspace_inventory`, typed `AgentWorkspaceFilesArgs`, and ADR 0020
  result-view machinery.
- Produces: `KAST_AGENT_WORKSPACE_FILES_RESULT`, selection, count, verbose, and
  explain projections with deterministic filters and limits.

- [ ] **Step 1: Add failing end-to-end discovery and filter tests**

Use `spawn_sequenced_idea_backend` with `runtime/status`, `capabilities`, and
`raw/workspace-files`, plus the index fixture. Assert the default record
contains path, both module identities, source set, kind, package, index state,
drift, and dirty state. Add one test per filter and one conjunction test. Assert
the raw request is:

```json
{
  "method": "raw/workspace-files",
  "params": {"includeFiles": true}
}
```

and does not pass the public `--limit` as `maxFilesPerModule`.

- [ ] **Step 2: Add failing limitation and output-budget tests**

Cover a truncated backend module, missing index, missing Git, backend
capability absence, malformed backend payload, and both candidate sources
unavailable. Seed 500 records and assert the default output has at most 20
file records, no more than 120 lines, and no more than 1,500 estimated tokens.

- [ ] **Step 3: Run focused public tests and observe the temporary error**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke workspace_files
```

Expected: tests reach `WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` from Task 1 or
fail because the projection is absent.

- [ ] **Step 4: Implement exact-root collection and typed degradation**

In `execute_agent_workspace_files`:

1. call `runtime::semantic_workspace_route` and preserve its exact rejection;
2. open one admitted raw RPC session;
3. request and strictly decode `raw/workspace-files`;
4. read the uncapped index snapshot;
5. collect targeted filesystem and dirty evidence;
6. return a detailed typed inventory result to the projection layer.

Treat malformed backend JSON as `WORKSPACE_FILES_BACKEND_INVALID`. Treat
capability absence as partial only when the index supplies candidates. Treat
index absence as partial when the backend supplies candidates. Return
`WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` when neither source is usable.

- [ ] **Step 5: Apply typed filters, deterministic order, then public limit**

Convert CLI arguments into `WorkspaceInventoryFilter` once. Match module
against exact backend name or exact Gradle path, package and source set exactly,
path prefix at segment boundaries, and glob against normalized relative path.
Sort by relative path, Gradle module path, and backend module name before
calling `take(limit.get())`.

Populate page evidence as:

```rust
WorkspaceFilesPage {
    known_match_count: filtered.len(),
    returned_count: files.len(),
    truncated: filtered.len() > files.len(),
    inventory_complete: snapshot.is_complete(),
    limit: args.limit.get(),
}
```

Do not label `known_match_count` a total when `inventory_complete` is false.

- [ ] **Step 6: Add all ADR 0020 projections**

Add `AgentProjectionRequest::WorkspaceFiles` and project:

- compact: required file fields, page, limitations;
- fields: selected file fields plus type/ok/page/schema;
- count: known counts grouped by kind/index/drift/dirty with no file payloads;
- verbose: complete typed inventory and evidence sources; and
- explain: verbose evidence plus normalized filters and classification source.

Never put raw request/response envelopes into compact, fields, or count
results. Preserve detailed typed backend/index errors in failed envelopes.

- [ ] **Step 7: Run public and projection tests and verify green output**

Run the commands from Step 3. Expected: all filters, limitation cases, view
shapes, deterministic ordering, and budget assertions pass.

- [ ] **Step 8: Commit public discovery and projections**

```console
git add cli-rs/src/agent.rs cli-rs/src/agent/dispatch.rs cli-rs/src/agent/workspace_files.rs cli-rs/src/agent/projection.rs cli-rs/src/agent/projection cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs
git commit -m "feat: expose bounded workspace file discovery"
```

### Task 5: Couple capability advertisement to the callable route

**Files:**

- Create: `cli-rs/src/agent/public_capabilities.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/projection/verify.rs`
- Modify: `cli-rs/src/agent/projection/tests.rs`
- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`

**Interfaces:**

- Consumes: backend `readCapabilities`, `Cli::command()`, and the public
  workspace-files command.
- Produces: `AgentPublicCapabilityRoute`,
  `callable_public_capabilities`, and verification `publicRead` route evidence.

- [ ] **Step 1: Add failing registry and verification tests**

Assert the registry contains exactly this initial route:

```rust
AgentPublicCapabilityRoute {
    capability: AgentPublicCapability::WorkspaceFiles,
    command_segments: &["agent", "workspace-files"],
    display_command: "kast agent workspace-files",
}
```

Walk `Cli::command()` through every segment and fail if any segment is absent
or hidden. In verification projection tests, assert `WORKSPACE_FILES` produces
one `publicRead` entry only when present in backend capabilities; omit it when
the backend does not advertise it.

- [ ] **Step 2: Run the focused projection tests and observe the missing route**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke verify
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke capability
```

Expected: failures show no public capability registry or `publicRead`
projection.

- [ ] **Step 3: Implement the typed route registry**

Define `AgentPublicCapability` as an enum and make its canonical backend string
a total match. `callable_public_capabilities` intersects parsed backend read
capabilities with the static registry and returns typed route projections.
Verification keeps raw capability counts for runtime diagnosis but uses the
intersection for public command evidence.

- [ ] **Step 4: Verify callability and absence behavior**

Run the two focused commands from Step 2. Expected: the route test resolves
the real Clap command, advertised backend capability emits the public route,
and absent backend capability emits no workspace discovery claim.

- [ ] **Step 5: Commit the capability invariant**

```console
git add cli-rs/src/agent.rs cli-rs/src/agent/public_capabilities.rs cli-rs/src/agent/projection/verify.rs cli-rs/src/agent/projection/tests.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs
git commit -m "feat: couple workspace capability to public command"
```

### Task 6: Prove composition and teach the public path

**Files:**

- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/support/workspace_files.rs`
- Modify: `cli-rs/src/agent/AGENTS.md`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Modify: `docs/reference/agent-commands.md`
- Modify: `docs/use/inspect-kotlin.md`

**Interfaces:**

- Consumes: stable `filePath`, workspace-files filters and limitations, typed
  diagnostics, and exact symbol `--file-hint`.
- Produces: executable no-search and composition regressions plus public and
  packaged guidance aligned to the real command.

- [ ] **Step 1: Add failing direct-composition regression**

Run workspace discovery against the scripted backend, extract one returned
`filePath`, then invoke:

```console
kast agent diagnostics --file-path <returned-file-path> --workspace-root <repo>
kast agent symbol --query app.App --file-hint <returned-file-path> --workspace-root <repo>
```

Assert both backend requests receive the exact returned path. Keep an unowned
on-disk `.kt` file in the fixture and assert it is never returned, proving the
flow does not begin with generic filesystem discovery.

- [ ] **Step 2: Run the composition test before documentation edits**

Run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke composition
```

Expected: pass after Task 4. If it fails, repair the typed path composition
before documenting the command.

- [ ] **Step 3: Update source-ownership and packaged guidance**

Add `workspace-files` to the public agent command list in
`cli-rs/src/agent/AGENTS.md`. Teach this route in the packaged skill and
quickstart:

```console
kast agent workspace-files --kind source --module :app --workspace-root "$PWD"
kast agent workspace-files --kind script --drift unknown --workspace-root "$PWD" --explain
```

State that results are compiler/index evidence, that limitations make partial
coverage explicit, and that agents should pass `filePath` directly to
diagnostics or symbol `--file-hint`.

- [ ] **Step 4: Update public reference and how-to docs**

Document every flag, default/max limit, default result fields, result views,
drift truth, limitation codes, and exact-root behavior in
`docs/reference/agent-commands.md`. In `docs/use/inspect-kotlin.md`, replace the
generic-search starting point with workspace-files discovery, then exact
symbol lookup and diagnostics. Do not imply that #340 Gradle task symbol
support exists yet.

- [ ] **Step 5: Validate docs and packaged routing**

Run:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke composition
zensical build --clean
```

Expected: every command example parses or is covered by existing command
contracts, docs/navigation contracts pass, the packaged skill names the typed
path, and Zensical renders without broken links.

- [ ] **Step 6: Commit guidance and composition proof**

```console
git add cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/support/workspace_files.rs cli-rs/src/agent/AGENTS.md cli-rs/resources/kast-skill/SKILL.md cli-rs/resources/kast-skill/references/quickstart.md docs/reference/agent-commands.md docs/use/inspect-kotlin.md
git commit -m "docs: teach semantic workspace file discovery"
```

### Task 7: Run full gates and prepare issue handoff

**Files:**

- Review: all files changed by Tasks 1 through 6
- Update only when a gate proves drift: authored Rust, tests, or docs already
  listed in this plan

**Interfaces:**

- Consumes: the complete issue #338 implementation.
- Produces: fresh full-suite evidence, a clean focused diff, and a branch ready
  for independent review and PR publication.

- [ ] **Step 1: Run the focused issue gates from ADR 0021**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke
```

Expected: all focused tests pass with zero ignored failures.

- [ ] **Step 2: Run the full Rust quality gates**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
```

Expected: the full locked suite passes, formatting reports no changes, and
clippy emits no warnings.

- [ ] **Step 3: Prove generated raw contracts did not drift**

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
git diff --exit-code -- cli-rs/protocol cli-rs/resources/kast-skill/references/commands.json cli-rs/resources/kast-skill/references/commands.yaml
```

Expected: the contract checker passes and generated raw protocol/catalog files
remain unchanged.

- [ ] **Step 4: Run final documentation gates**

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

Expected: both contracts and rendering pass.

- [ ] **Step 5: Review scope and whitespace**

```console
git status --short --branch
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected: only issue #338 source, tests, guidance, ADR/spec/plan, and docs are
present; there are no Kotlin wire/schema or generated protocol/catalog changes;
the worktree is clean after commits.

- [ ] **Step 6: Request independent review**

Ask a fresh reviewer to check type invariants, exact-root containment, no
recursive/Git candidate authority, the false-`INDEX_ONLY` rule under partial
backend coverage, capability callability, #340 reuse, output budgets, and all
acceptance criteria. Repair every blocking finding with a focused red-green
commit and rerun the affected gate plus the full Rust suite.
