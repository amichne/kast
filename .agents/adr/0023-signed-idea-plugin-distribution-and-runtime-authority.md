# ADR 0023: Signed IDEA plugin distribution and runtime authority

Status: Accepted

Date: 2026-07-15

This ADR records the accepted pre-1.0 contract for the Kast IDEA plugin. The
decision is accepted; the implementation remains intentionally split into the
follow-on slices named below. Until a slice lands, current code may still
implement the superseded behavior, but no new work may extend that behavior.

The decision is grounded in the accepted Wayfinder evidence:

- the [resolved product contract](https://github.com/amichne/kast/issues/370#issuecomment-4976471889);
- the [current-authority inventory](https://github.com/amichne/kast/issues/372#issuecomment-4976914981);
- the [JetBrains signing and repository envelope](https://github.com/amichne/kast/issues/371#issuecomment-4976976074)
  and its [evidence](https://gist.github.com/amichne/aca5e20d0f08bad404d88df3e8cb05fb);
- the accepted [restart-free lifecycle NO-GO](https://github.com/amichne/kast/issues/364#issuecomment-4977440406)
  and its [proof](https://gist.github.com/amichne/50b5b7d925f12a2b4330ce2ca221c0f9);
  and
- the accepted [semantic cockpit contract](https://github.com/amichne/kast/issues/375#issuecomment-4980937865)
  and its [prototype](https://gist.github.com/amichne/e9dd94bf865ba4bac083efcd91946d47).

## Supersession and reconciliation

Records with duplicate numbers are identified by full path and title. This is
deliberate: two accepted ADR 0007 files differ, and the Homebrew authority is
duplicated as accepted ADRs 0012 and 0013.

| Record | This ADR changes | This ADR retains |
| --- | --- | --- |
| `0006-forward-system-definition-and-audit-scope.md`, **Forward system definition and audit scope** | Supersedes exact CLI/plugin coupling, direct Rust SQLite authority, and the narrower IDEA surface. | Compiler-backed product definition, module boundaries, typed AXI contract, and source-backed change rule. |
| `0007-macos-plugin-setup-authority.md`, **macOS plugin setup authority** | Supersedes Homebrew plugin delivery, exact CLI/plugin equality, and CLI-owned plugin repair. | Plugin-owned project-open bootstrap, exact-worktree isolation, fail-closed activation, and exact-root teardown. |
| `0007-macos-onboarding-installer.md`, **macOS onboarding installer** | Does not revive its historical checked-out installer or plugin-install path. | Plugin-owned workspace setup where not superseded later. |
| `0010-brew-style-macos-onboarding-installer.md`, **Brew-style macOS onboarding installer** | Supersedes version-coupled plugin install and update behavior. | Root `install.sh` as the public macOS CLI/Homebrew onboarding entry point. |
| `0012-macos-homebrew-install-authority.md`, **macOS Homebrew install authority** | Supersedes plugin cask convergence, profile linking, joint CLI/plugin receipts, and exact CLI/plugin equality. | Homebrew authority for the CLI binary, exact binary provenance, fail-closed CLI receipt validation, no `sudo`, and safe legacy cleanup. |
| `0013-macos-homebrew-install-authority.md`, **macOS Homebrew install authority** | Supersedes the same duplicated plugin clauses as ADR 0012. | Retains the same CLI-only clauses as ADR 0012. |
| `0019-exact-root-semantic-workspace-admission.md`, **Exact-root semantic workspace admission** | Replaces version equality with typed compatibility. | Exact normalized root, matching plugin metadata and descriptor, ambiguity failure, read-only verification, and plugin authority for project-local mutations. |
| `0012-repo-native-semantic-story-demo.md`, **Repo-native semantic story demo** | Supersedes direct SQLite reads by the Rust demo. | The deterministic story, typed evidence, limits, and truthful failure outcomes. |
| `0016-fail-closed-exact-symbol-lookup.md`, **Fail-closed exact symbol lookup** | Supersedes direct Rust source-index storage ownership. | Closed exact outcomes and canonical symbol identity. |
| `0020-compact-agent-result-projections.md`, **Compact public agent result projections** | Supersedes direct Rust source-index paging and counting. | Typed compact projections, explicit completeness and cardinality, bounded work, and generation-bound continuations. |
| `0021-first-class-workspace-file-discovery.md`, **First-class workspace file discovery** | Supersedes direct Rust composition over SQLite. | Backend project-model authority, exact-root containment, generation, coherence, completeness, drift, limits, and single-owner shutdown. |
| `0022-identity-first-relationship-navigation.md`, **Identity-first relationship navigation** | Supersedes direct Rust SQLite impact reads. | Complete anchored identity, exact selectors, typed relation commands, deterministic paging, typed degradation, and continuation disposal. |

The following accepted decisions continue unchanged and constrain this ADR:

- ADR 0001's machine, repository, and headless scope split;
- ADR 0002's manifest-backed agent-resource trust and fail-loud cutover;
- ADR 0004's managed-guidance ownership;
- ADR 0005's typed AXI surface;
- ADR 0009's plan-first, explicit-apply mutation boundary;
- ADR 0015's server-owned observable mutation lifecycle;
- ADR 0017's semantic admission refresh barrier; and
- ADR 0018's workspace-relative file normalization.

## Decision

Kast has three independent authority planes:

1. The macOS installer and Homebrew own the CLI machine installation and its
   binary receipt.
2. JetBrains plugin signing, the Kast custom plugin repository, and the IDE own
   plugin artifact trust, installation, and updates.
3. Exact-root workspace metadata plus typed runtime negotiation own whether a
   CLI, plugin, server, and workspace may operate together.

No plane may infer another plane's truth from a shared version string, a shared
receipt, an IDE profile link, or direct access to a backend database.

### Signed distribution and IDE-owned updates

Each plugin release is one signed ZIP whose plugin ID remains
`io.github.amichne.kast`. The ZIP is uploaded once as an immutable GitHub
release asset with checksum and build provenance. Re-running a release must
prove byte identity or fail; it must not overwrite the plugin asset with
`--clobber`.

Kast publishes a stable HTTPS custom JetBrains repository whose
`updatePlugins.xml` refers only to immutable release-asset URLs and declares
the compatible IDE build range. The repository metadata is generated from
checked-in typed source, validated against the asset digest and signing
identity, and published only after the release asset is final.

The supported installation story is:

1. The user obtains the first signed plugin ZIP and installs it from disk in
   the IDE.
2. The user explicitly enrolls the Kast signing certificate and the Kast
   custom repository URL using JetBrains-owned UI.
3. The IDE discovers, verifies, stages, and applies later updates.

The CLI, `install.sh`, Homebrew formulae, repair commands, and receipts must not
write JetBrains plugin directories, link a plugin into an IDE profile, mutate
custom-repository configuration, or enroll a certificate. Certificate
rotation requires an explicit overlap or re-enrollment plan and a deterministic
gate; an unknown or invalid signer fails closed.

Marketplace publication is not part of this decision.

### Typed compatibility instead of version equality

Release versions remain aligned for traceability, but equality is not the
runtime compatibility protocol. Admission negotiates these typed facts:

- protocol revision;
- workspace-metadata revision;
- advertised capability set; and
- runtime identity, including implementation version and backend kind.

The release compatibility manifest declares supported plugin, CLI, protocol,
metadata, runtime, and IDE-build combinations. Before 1.0, the default tested
pair is the same release. An adjacent-release pair is supported only when that
exact pair passes the compatibility matrix; alignment alone never grants
support.

An incompatible required protocol or metadata revision fails closed with a
typed update-required outcome. A missing optional capability disables only the
operation that requires it and reports the missing capability. Runtime identity
is evidence, not a substitute for protocol, metadata, or capability checks.
Exact-root admission from ADR 0019 remains mandatory and independent.

### Backend-private index authority

The persisted source index is a backend-private implementation detail. The
active backend and `index-store/` own its schema, transactions, lifecycle, and
rebuild policy. The database is not a cross-process compatibility surface and
its schema version is not part of CLI/plugin negotiation.

The CLI, agent commands, demos, workspace inventory, symbol lookup, impact, and
relationship navigation consume typed `analysis-api`/`analysis-server` methods.
They do not open the SQLite file. A backend may rebuild an incompatible or
untrusted persisted index from authoritative project state. Existing typed
completeness, cardinality, generation, exact-identity, paging, and degradation
contracts remain observable through those APIs.

### Lifecycle contract

Restart-required update application is the supported baseline. Restart-free
unload remains a design target, not a release promise.

The pinned IDEA Ultimate 2025.3 proof established a NO-GO for unconditional
restart-free N-to-N-plus-one updates: the IDE refused unload while resolving
`com.intellij.supportsKotlinPluginMode`. The same proof established that the
IDE can stage an update and present the restart action. Kast therefore advertises
restart-free eligibility only after a full-product, signed-artifact gate proves
the exact release and target IDE build.

An eligible unload must:

1. reject new requests and publish draining state;
2. settle or truthfully terminate admitted reads and mutation operations;
3. dispose server-held continuation state;
4. stop indexing and file watchers;
5. close the running analysis server and backend exactly once;
6. close the source-index store after its consumers; and
7. release project services, UI subscriptions, threads, class loaders, files,
   and sockets before the new version starts.

Any failed precondition selects the IDE's restart fallback. Kast will not use
classloader tricks, manual JAR replacement, a second hidden plugin copy, or an
out-of-process hot-swap mechanism to claim dynamic support.

### Semantic cockpit

The IDEA tool window is a project-local evidence surface, not a generic AI UI.
It reuses typed backend facts and does not become a raw-RPC console or a second
mutation dialect. Its accepted scope contains exactly three stories.

**Readiness** shows runtime and admission truth: exact or bounded-known file
counts from one semantic generation, indexing/refresh phase, completeness,
stale or failed state, and actual declaration evidence. It never fabricates a
symbol-completion percentage.

**Current File** binds all evidence to one canonical workspace-relative path,
document revision, and semantic generation. It shows diagnostics, outline and
declaration identity, exact reusable selectors, and available typed relationship
actions. Stale or incomplete identity disables navigation rather than guessing.

**Agent Activity** shows admitted request evidence: client and context,
normalized exact root, operation/capability, runtime identity, semantic
generation, completeness, result cardinality, duration, outcome, and a safe
navigation target when available. Mutation events are observable according to
ADR 0015, but the cockpit cannot approve, alter, or execute a mutation.

Each story has explicit loading, unavailable, stale, failed, and empty states.
Closing a project or unloading the plugin disposes its subscriptions and
project-local history.

## Source ownership

Some forward owners do not exist yet. Their named follow-on slice creates them;
the path assignment in this ADR prevents competing sources of truth.

| Contract | Authored owner | Generated or consuming surfaces |
| --- | --- | --- |
| Plugin identity, IDE extension registration, unload declarations | `backend-idea/src/main/resources/META-INF/plugin.xml` and `backend-idea/build.gradle.kts` | Built plugin ZIP and JetBrains verifier reports. |
| Signed immutable plugin asset, checksum, provenance, publication ordering | `.github/workflows/release.yml`, `.github/scripts/test-release-workflow-contract.sh`, `.github/scripts/test-release-asset-verifier.sh`, and `scripts/verify-release-assets.sh` | GitHub release assets; no mutable artifact is an owner. |
| Signing identity and custom repository metadata | Future `packaging/jetbrains/plugin-repository.json` plus future `.github/scripts/render-jetbrains-plugin-repository.py` | Generated `updatePlugins.xml` and GitHub Pages output; neither is hand-authored. |
| Supported runtime pairs and IDE build envelope | Future `packaging/jetbrains/runtime-compatibility.json` | Release manifest, repository entries, and admission tests. |
| CLI install authority and receipt | `install.sh`, `packaging/homebrew/Formula/kast.rb`, `packaging/homebrew/release-state.json`, `packaging/homebrew/scripts/`, and `cli-rs/src/install/` | CLI-only Homebrew state. `packaging/homebrew/Casks/kast-plugin.rb` and plugin fields in the joint receipt are migration/deletion targets, not forward owners. |
| Compatibility types and typed failures | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/` | `analysis-server/`, `backend-idea/`, `backend-headless/`, and `cli-rs/src/runtime/` enforce or project the authored types. `cli-rs/protocol/` remains generated output. |
| Exact-root runtime metadata | `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastProjectOpenProfileAutoInit.kt` and the typed compatibility contract | `cli-rs/src/runtime/workspace_admission.rs` and `cli-rs/src/self_mgmt.rs` consume and validate it. |
| Index schema, transactions, and rebuild | `index-store/` and the active backend host | Typed server methods serve Rust consumers. `cli-rs/src/metrics_database/`, `cli-rs/src/symbol_query/`, and `cli-rs/src/workspace_inventory/` are direct-read migration targets. |
| Request admission, draining, mutation truth, and single-owner server close | `analysis-server/`, especially `RunningAnalysisServer`, `RuntimeLifecycleController`, and mutation services | Backend hosts and cockpit activity projections consume lifecycle evidence. |
| IDEA runtime teardown | `backend-idea/`, especially `KastIdeaBackendRuntime`, `KastPluginService`, project services, and `plugin.xml` | Dynamic eligibility tests and restart fallback. |
| Cockpit evidence types | `analysis-api/` typed contracts and `analysis-server/` event provenance | `backend-idea/` owns project-local projections, rendering, navigation, and disposal. |

`packaging/homebrew/release-state.json` remains release/schema authority only
for the CLI and existing non-plugin release products. It must not become a
second plugin repository or compatibility manifest.

## Clean pre-1.0 cutover

The implementation removes, rather than indefinitely adapts:

- the Homebrew plugin cask as an install/update authority;
- IDE-profile plugin links created by Kast;
- joint CLI/plugin receipt validity and exact-version equality;
- CLI plugin-install and plugin-repair mutations;
- public cross-process SQLite reads; and
- obsolete workspace metadata that cannot express the typed negotiation.

One release may contain an explicit one-shot cleanup of Kast-owned legacy links
and receipt fields. Cleanup must be safe, idempotent, and narrowly identified;
it is not a compatibility shim. Old clients, metadata, or indexes fail closed
with an update, re-open, refresh, or rebuild instruction. No indefinite alias,
dual-write, dual-read, version-equality fallback, or hidden migration path is
accepted before 1.0.

## Deterministic release gates

The signed distribution cannot ship until deterministic checks prove all of
the following:

- the plugin ZIP is built from the release tag, contains the expected plugin
  ID, passes JetBrains structure/compatibility verification, is signed by an
  enrolled certificate, and matches its checksum and provenance;
- the plugin release asset is immutable, and the generated repository entry
  names that exact URL, digest, signer, version, and IDE build range;
- the compatibility manifest is schema-valid, every advertised pair has a
  matrix result, unsupported pairs fail closed, and optional-capability skew
  degrades only the affected operation;
- the CLI installer and Homebrew state cannot mutate IDE profiles, plugin
  files, repository configuration, or certificate trust;
- no public Rust path opens the backend SQLite store, while typed consumers
  retain exactness, completeness, cardinality, generation, and bounded work;
- restart staging works on every supported IDE; and
- any release advertised as restart-free passes signed N-to-N-plus-one install,
  update, drain, unload, leak, re-enable, and rollback tests on every named IDE
  build. A failed row records restart-required support, not a waived failure.

The existing gates that follow-on slices extend include:

```console
.github/scripts/test-release-workflow-contract.sh
.github/scripts/test-release-asset-verifier.sh
.github/scripts/test-macos-installer-contract.sh
python3 packaging/homebrew/scripts/test-formulas.py

./scripts/ci-gradle-retry.sh ./gradlew \
  :backend-idea:buildPlugin \
  :backend-idea:verifyPluginStructure \
  :backend-idea:verifyPluginXmlPresent

cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test semantic_workspace_admission_smoke \
  --test runtime_backend_smoke \
  --test runtime_lifecycle_smoke

cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test agent_operation_surface_smoke \
  --test agent_result_projection_smoke \
  --test agent_workspace_files_smoke \
  --test agent_relationship_navigation_smoke

cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- \
  developer release generate contract --check
```

No current checked-in command proves plugin signing, repository-feed
consistency, supported runtime skew, absence of public SQLite reads, or
restart-free unload. The implementing slice must add its dedicated executable
gate before claiming the property.

## Follow-on implementation slices

These are planning boundaries, not authorization to implement them in this
ADR change:

1. **Immutable signed ZIP:** add signing inputs, verification, immutable upload,
   checksum/provenance checks, and certificate-rotation failure tests.
2. **Repository source and publication:** add the typed repository source and
   generator, publish the stable HTTPS feed after immutable assets, and verify
   feed-to-asset consistency. Depends on slice 1.
3. **Typed compatibility preparation:** add protocol, metadata, capability, and
   runtime-identity types; generate the compatibility manifest; version exact-root
   metadata; and test supported and rejected pairs. Depends on slice 2's source
   ownership. These additions remain inactive preparation and do not create a
   second admission rule before cutover.
4. **CLI/plugin authority cutover:** activate IDE-owned signed installation and
   updates plus typed compatibility admission while removing the plugin cask,
   profile mutation, joint receipt, exact-version equality, and plugin repair.
   Keep CLI Homebrew installation and add the narrowly bounded legacy cleanup.
   Depends on slices 1, 2, and 3. Activation and removal are one release
   boundary: no released artifact may expose neither authority or both
   authorities, and no compatibility fallback bridges them.
5. **Backend-private index APIs:** add missing typed server operations, migrate
   one Rust consumer family at a time, then prohibit and remove public SQLite
   readers. Depends on slice 3 for API-skew types and must not be released before
   slice 4's authority cutover.
6. **Lifecycle and eligibility:** implement complete draining/teardown evidence,
   correct the unresolved extension registration, prove restart staging, and
   advertise dynamic support only for passing matrix rows. Depends on slices 1,
   2, 3, and 4.
7. **Cockpit data projections:** add typed readiness, current-file, and admitted
   activity evidence without UI rendering. Depends on slices 3, 4, and 5.
8. **Three-story cockpit UI:** render only Readiness, Current File, and Agent
   Activity with stale/unavailable/failure states and disposal tests. Depends on
   slice 7; it does not expand mutation authority.

Each slice must update the nearest scoped `AGENTS.md` when ownership or gates
actually move. Generated artifacts are updated only from their source owner.

## Out of scope

This decision does not authorize:

- JetBrains Marketplace publication;
- fleet, organization, or multi-project aggregation;
- generic AI chat, completion, prompt, or model-selection UI;
- production implementation in this ADR change;
- a public SQLite/index database contract;
- an unconditional restart-free guarantee; or
- indefinite pre-1.0 compatibility shims.

## Consequences and future change rule

Installation becomes less automatic at first enrollment and more trustworthy
thereafter. Release engineering gains signing, feed, matrix, and lifecycle
gates. Compatibility becomes explicit and testable. The index becomes free to
evolve behind typed APIs. The IDEA surface gains useful evidence without
becoming another agent command or mutation authority.

A future change that alters plugin trust, repository ownership, compatibility
admission, index privacy, lifecycle guarantees, or the three cockpit stories
must supersede this ADR before changing public docs or generated outputs.

## Validation for this ADR

This decision-only change is validated with:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```
