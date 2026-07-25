# Aggressive cruft cleanup log

Date: 2026-07-25

## Result

This pass removes repository state that had no current product, build, test,
operational, or decision-making owner. Git remains the history store.

- 138 tracked files changed.
- 86 tracked files deleted.
- 500,285 lines deleted and 663 lines added.
- 29 ADR files reduced to six current decisions.
- Four unused direct Rust dependencies removed.
- Final fallback graph: 13,065 nodes, 26,774 edges, 692 communities.
- Final graph diagnostics: no unverified nodes, missing or dangling endpoints,
  self-loops, exact duplicate edges, relation variants, or endpoint-collapse
  risk.

## Evidence method

The pass combined:

1. Installed Kast runtime readiness and compiler diagnostics.
2. Kast native graph attempts.
3. A fresh Graphify fallback graph before and after removal.
4. Repository-wide reference searches and deleted-path sweeps.
5. Cargo dead-code warnings, all-target Clippy, `cargo machete`, and tests.
6. Gradle task wiring, Kotlin compilation, and focused tests.
7. Documentation navigation/content contracts and a clean site build.
8. Git history only where current ownership was otherwise ambiguous.

Kast reached `READY` with IDEA backend `0.16.1-1-gc40fb0c2`. Native graph
queries failed closed with `NATIVE_GRAPH_DATABASE_UNAVAILABLE`: the CLI
correctly selected
`~/.local/share/kast/state/data/workspaces/.../cache/source-index.db`, while
the installed backend's database was under
`~/.local/share/kast/state/workspaces/.../cache/source-index.db`. The native
graph was therefore impaired, not treated as empty evidence. The requested
Graphify fallback was used.

The pre-cleanup fallback contained 13,628 nodes, 28,358 edges, and 774
communities. The final code-only rebuild contains 13,065 nodes, 26,774 edges,
and 692 communities. Both structural diagnostic runs were clean.

## Deleted authored history

### Obsolete ADRs

The following 24 files were superseded, described removed surfaces, duplicated
current executable contracts, or existed only to redirect to a newer decision:

- `.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md`
- `.agents/adr/0005-axi-only-agent-cli-and-semantic-edit-dialect.md`
- `.agents/adr/0006-forward-system-definition-and-audit-scope.md`
- `.agents/adr/0008-kotlin-developer-surface-ratchet.md`
- `.agents/adr/0009-scope-mutation-agent-commands.md`
- `.agents/adr/0011-journey-first-documentation-operating-model.md`
- `.agents/adr/0012-repo-native-semantic-story-demo.md`
- `.agents/adr/0013-macos-homebrew-install-authority.md`
- `.agents/adr/0014-kotlin-top-level-type-file-isolation.md`
- `.agents/adr/0015-observable-semantic-mutation-lifecycle.md`
- `.agents/adr/0016-fail-closed-exact-symbol-lookup.md`
- `.agents/adr/0017-semantic-admission-refresh-barrier.md`
- `.agents/adr/0018-relative-agent-file-path-normalization.md`
- `.agents/adr/0019-exact-root-semantic-workspace-admission.md`
- `.agents/adr/0020-compact-agent-result-projections.md`
- `.agents/adr/0021-first-class-workspace-file-discovery.md`
- `.agents/adr/0022-identity-first-relationship-navigation.md`
- `.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`
- `.agents/adr/0026-codex-cli-plugin-and-rust-exposure-authority.md`
- `.agents/adr/0028-unsigned-github-idea-plugin-distribution.md`
- `.agents/adr/0029-processless-development-machine-authority.md`
- `.agents/adr/0030-codex-only-workstation-authority.md`
- `.agents/adr/0031-external-codex-marketplace-authority.md`
- `.agents/adr/0031-sole-transactional-setup-authority.md`

The two ADR 0031 variants were replaced by one current decision:
`.agents/adr/0031-cli-install-and-data-authority.md`. It codifies the active
CLI receipt as the sole authority for installation paths and workspace data,
including database paths. Plugins and backends may consume that authority but
may not derive a competing location. The external Codex marketplace owns only
packaging and presentation.

The retained ADRs were reduced to current invariants:

- 0025: backend-bound opaque selector handles.
- 0026: proof-carrying relationship coverage.
- 0027: effective agent-environment readiness.
- 0028: exact-root runtime leases.
- 0031: CLI installation and data authority.
- 0032: the supported macOS IDEA pathway.

All retained guidance was swept for references to the deleted ADRs.

### Completed plans, specs, and partial skills

The following 37 files were historical work products or incomplete skill
fragments with no `SKILL.md`, catalog entry, or caller:

- `.agents/plans/2026-07-10-macos-installer-banner.md`
- `.agents/specs/2026-07-10-macos-installer-banner-design.md`
- all 30 files under `.agents/superpowers/plans/` and
  `.agents/superpowers/specs/`
- `.agents/skills/llm-wiki/agents/openai.yaml`
- `.agents/skills/llm-wiki/references/page-patterns.md`
- `.agents/skills/refresh-affected-agents/references/agents-update-contract.md`
- `.agents/skills/refresh-affected-agents/scripts/find_affected_agents.py`

The removed plans/specs totaled 11,734 lines. They recorded completed
migrations, deleted repository shapes, and an abandoned Tekmor rename.

`.agents/docs/documentation-journeys.md` was also deleted. It described the
gone `docs/install`, `docs/learn`, `docs/use`, and Markdown
`docs/distribute` trees. `zensical.toml` is the current navigation authority.

## Deleted generated and documentation state

All 11 tracked `graphify-out/` files were deleted:

- graph, manifest, report, labels, and learning state;
- five stored query memories;
- the stored reflection ledger.

The snapshot was derived audit state built at an older commit, still named a
deleted Rust file, and was locally hidden with `skip-worktree`. The
contradictory `.gitignore` re-inclusion rules were removed. Fresh fallback
graphs now live only in the ignored audit workspace.

`docs/distribute/kast-runtime-manifest.schema.json` was deleted because it was
unreferenced, absent from site navigation, and described the superseded V1
runtime artifact shape.

`docs/explanation/compiler-evidence.md` now describes the native graph and
source-index database instead of the removed Graphify projection.

## Deleted scripts and inert executables

- `scripts/analyze-spans.py`: no caller, documentation, workflow, or output
  consumer.
- `scripts/verify-native-graph-cutover.sh`: one-time cutover assertion after
  the cutover completed.
- `.github/scripts/test-selector-handle-installed-workflow.sh`: exported an
  environment variable the invoked Rust test never read.
- `cli-rs/src/bin/kast-metrics-bench.rs`: unreferenced benchmark that invoked
  removed top-level metrics commands, so it failed before benchmarking.

The current `scripts/benchmark-native-graph.py` remains because it exercises
the live native graph protocol and has a concrete benchmark output contract.

## Deleted Rust behavior

### Removed-command tombstones

The hidden `kast agent tools`, `kast agent call`, `kast agent workflow`, and
`kast developer inspect demo` tombstones were deleted with:

- their argument structs;
- dispatch and replacement helpers;
- projection arms;
- tombstone-only integration tests.

The commands are absent from the parser instead of retaining production code
whose only behavior is to reject them.

### Retired managed-repository receipt state

The installation receipt's always-empty `repos` field and the entire retired
Copilot managed-resource subsystem were deleted:

- `ManagedRepo` and all managed-resource/checksum/history types;
- `ManagedResourceKind` and verification result types;
- managed-fence extraction and checksum verification;
- readiness filtering, retired `copilotPackageVersion` handling, and output;
- constructors that wrote `repos: []`;
- self-referential scope tests.

Both live receipt constructors always produced an empty list. No installation
path could create the state, while readiness still carried code to validate
and print it.

### Unreachable metrics controls

The following test-only production path was deleted:

- `MetricsQueryControls`;
- the `MetricsDatabase.controls` field;
- `open_with_controls`;
- SQLite progress-handler installation/reset;
- deadline, cancellation, and progress-budget mapping;
- the synthetic cancellation test.

Production always opened the database with defaults and had no way to activate
those controls. Keeping the path advertised cancellation behavior the product
could not reach.

### Compiler-revealed dead declarations

Broad production dead-code allowances were removed. Forced warnings then
identified and removed:

- an unused removed-command envelope and helper;
- an unused macOS bootstrap function;
- `CanonicalKotlinFilePath::rpc_path`;
- the duplicate native-graph CSR weight vector;
- the unread projection step `name`;
- `AgentMutationResultEvidence::empty`;
- a test-only compatibility wrapper.

Required wire fields that are deserialized but intentionally unread were
renamed with explicit Serde names rather than hidden by blanket allowances.
The only remaining combined `dead_code`/`unused_imports` allowance is in
`cli-rs/tests/support/mod.rs`, which is compiled independently into 21
integration-test crates that intentionally use different subsets.

### Dependencies

`cargo machete` proved these direct dependencies unused, so they and
now-unreachable lockfile packages were removed:

- `clap_complete`
- `shlex`
- `unicode-casefold`
- `unicode-normalization`

## Deleted Kotlin and Gradle islands

- `ExtractLegacyPluginClassesTask.kt` and its
  `extractLegacyPluginClasses` registration: no task depended on its output.
- `RuntimeJarPathOrdering.kt` and its test: the helper was used only by that
  test after `SyncRuntimeLibsTask` assumed the live runtime-classpath behavior.
- `ParsedExactSymbolSelector.kt` and its test: no production caller parsed to
  this duplicate trusted selector model.
- `KastTelemetryEnums.kt` and its test: the enum duplicated the live
  `IdeaTelemetryDetail` but was used only by its own test.
- Four `relationshipCoverage*` source-set stubs: no test, workflow, runtime
  path, or documentation named the anchor or its three callers.

The stale "legacy Copilot profile alias" test was folded into the adjacent
current `jetbrains-plugin` defaults test. There is no distinct alias.

## Simplified current guidance and policy

- `.agents/adr/AGENTS.md` now treats ADRs as current-only; Git owns historical
  rationale.
- `.agents/docs/AGENTS.md` now names the actual Zensical documentation tree.
- Rust subsystem `AGENTS.md` files no longer route work through deleted ADRs,
  deleted docs, or removed surfaces.
- `index-store/AGENTS.md` now states that the CLI-owned workspace path is the
  database authority.
- `backend-idea/AGENTS.md` no longer cites deleted ADR 0022.
- `.github/ci/issue-401-workflow-model.json` retains its live graph model but
  replaces the historical policy narrative with the current baseline,
  candidate, and provisional-sample facts.
- `.gitignore` no longer preserves absent Copilot outputs, deleted generated
  skills, or tracked Graphify exceptions.

## Corrected audit assumptions

Two initial deletion candidates were restored immediately after stronger
evidence:

1. `config::workspace_database_path` is live across native graph, metrics,
   symbol query, demo, and workspace inventory. It is the central Rust
   expression of CLI database-path authority and remains.
2. `manifest::sha256_file` is live bundle-integrity infrastructure used by
   bundle validation, helper digesting, and IDEA installation. Only the
   Copilot-specific managed-fence verifier was deleted.

These corrections are recorded because an aggressive pass must distinguish a
rejected candidate from a silently missed dependency.

## Retained exceptions

| Item | Current reason |
| --- | --- |
| Public JSON agent output | Explicit compatibility surface with CLI and smoke-test coverage; TOON remains the default. |
| Hidden `doctor` alias | Consumed by the published GitHub Action and focused tests. |
| Legacy-install backups | Prevent data loss while replacing an older activation; removal would make setup destructive. |
| Negative retirement gates | Prevent removed public commands and release authorities from reappearing. |
| Runtime compatibility matrix | Active plugin/CLI admission boundary and CI contract. |
| `scripts/smoke-macos-idea-golden-path.sh` | Manual proof named by current ADR 0032 and repository instructions. |
| `scripts/benchmark-native-graph.py` | Current native graph benchmark. |
| Issue-401 workflow model/checker | Wired into CI and enforces output equivalence and fanout limits. |
| Graphify hook and ignore rules | Current authorized fallback integration; `graphify hook-check` succeeds. |
| Protocol JSON fixtures | Prove distinct generated request and response boundaries even when the AST extractor emits zero nodes. |
| All remaining build-logic task classes | Each has a live Gradle registration or consumer. |
| `FakeAnalysisBackend.kt` | Shared live test fixture; a zero-node generic parser result is not deletion proof. |
| Workspace legacy-label tests | Prove old unqualified strings never regain trusted Gradle identity. |

## Uncertainties

- The final Graphify code-only pass reported 169 supported inputs with zero AST
  nodes, primarily JSON fixtures. None was deleted from parser absence alone.
- External consumers cannot be disproved locally. A public command or format
  covered by a current contract was retained unless the repository contained
  explicit retirement evidence.
- The installed native graph remains unavailable until the already-separated
  CLI/backend database-path repair is installed together and the source index
  is rebuilt. This pass did not create another path authority or bypass the
  failure.

## Verification

The final acceptance run passed on 2026-07-25:

- No retained file references a deleted ADR, removed Kotlin island, or deleted
  Gradle task.
- `:analysis-api:test`, `:build-logic:test`,
  `:backend-idea:compileKotlin`, and the focused IDEA settings test passed.
- Rust formatting, dependency analysis, forced production dead-code and
  unused-import checking, all 270 CLI unit tests, 37 focused integration
  tests, and all 20 setup contract tests passed.
- Both documentation contracts and a clean Zensical site build passed.
- `git diff --check` passed.

Additional audit evidence also passed: a forced Graphify code extraction,
zero-error multigraph diagnostics, and all-target/all-feature Rust Clippy with
warnings denied. Native Kast graph generation remains impaired by the
installed CLI/backend database-path mismatch described above.
