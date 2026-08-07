# RED-GREEN Evidence

## RED

### Changed production Kotlin semantic audit

Command:

```shell
changed_files=(); while IFS= read -r file; do [[ -f "$file" ]] && changed_files+=("$file"); done < <(git diff --name-only "$(git merge-base HEAD origin/main)"..HEAD -- '*/src/main/**/*.kt'); kast check "${changed_files[@]}"
```

Expected failure: every changed production Kotlin file is compiler-clean with
no discarded primitive boundary derivations.

Observed failure: FAILED as expected. Exact installed-head analysis completed
all 77 files but reported `UNUSED_VARIABLE` for the unconsumed raw `targetPath`
derivation in `SourceIndexSnapshotStore.exportSnapshotDatabase`.

### Repository overlay replacement authority

Command:

```shell
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.RepositoryOverlayReplacementAuthorityTest --no-daemon --console=plain
```

Expected failure: replacing a file or declaration in the main overlay still retains the base symbol annotation, base edge occurrence, and base declaration-supertype edge.

Observed failure: FAILED as expected. The replacement graph returned both
`sample.NewAnnotation` and `sample.OldAnnotation`; the assertion stopped before
the same fixture could accept the stale base relation and old supertype.

### Runtime ownership discovery totality

Command:

```shell
cargo test --manifest-path cli-rs/Cargo.toml --locked --test runtime_durable_ownership_smoke remaining_review_regression
```

Expected failure: an unrelated same-UID process with non-UTF8 argv aborts orphan discovery, and an incomplete live registration plus a dead registration can be reported CLEAN.

Observed failure: FAILED as expected. With an unrelated non-UTF8 process alive,
runtime discovery returned `RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE: macOS process
argument is not UTF-8`. In the isolated partial-registration fixture, executable
repair removed the dead evidence and returned `CLEAN` while the hidden runtime
remained alive.

### IDEA readiness and project-model read authority

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.idea.backend.KastRuntimeReadinessTest --tests io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridgeReadActionTest --no-daemon --console=plain
```

Expected failure: mutation readiness remains READY during IDEA indexing, and Gradle progress observation does not provide a read-action-owned project-model observation seam.

Observed failure: FAILED as expected. Mutation readiness was `READY` while the
model lane was `IN_PROGRESS`, and the project-model observation seam was absent
(`NoSuchMethodException: readProjectModelInventory(Supplier)`).

### macOS doctor backend projection

Command:

```shell
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_readiness_smoke doctor_retains_host_supplied_macos_backend
```

Expected failure: a valid macOS backend with no runtime-libs directory is absent from human doctor output.

Observed failure: FAILED as expected. Human release readiness contained the
flattened receipt but no `Backend indexer` installed-version line because the
doctor projection discarded the backend whose runtime-libs path was absent.

### Transition freshness and transport deadlines

Commands:

```shell
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_transition_response_policy
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.RpcRequestWaitPolicyTest --no-daemon --console=plain
./gradlew :indexer:test --tests io.github.amichne.kast.idea.WorkspaceTransitionIngressTest --tests io.github.amichne.kast.idea.backend.KastDiagnosticsCompletenessTest --no-daemon --console=plain
./gradlew :indexer:test --tests io.github.amichne.kast.idea.backend.diagnostics.DiagnosticContentAuthorityTest --no-daemon --console=plain
```

Expected failure: post-mutation RPCs retain the ordinary deadline, a source request after cycle refresh is joined without enqueueing its freshness signal, and stale cached PSI is certified with a new disk hash.

Observed failure: FAILED as expected. Rust gave `raw/apply-edits` 35 seconds
instead of the 3,605-second transition allowance; the server derived
`ServerDeadline(1)` instead of `BackendProgressDeadline`. The active-cycle
source request did not invoke its bound enqueue callback. The Rust check
classifier returned `Covered` for a successful but `INCOMPLETE` result whose
file state was `PENDING_INDEX`. The deterministic Kotlin content-authority
regression did not compile because the typed observation and authority do not
yet exist.

## GREEN

Commands:

```shell
kast refresh index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/lifecycle/SourceIndexSnapshotStore.kt
changed_files=(); while IFS= read -r file; do [[ -f "$file" ]] && changed_files+=("$file"); done < <(git diff --name-only "$(git merge-base HEAD origin/main)" -- '*/src/main/**/*.kt'); kast check "${changed_files[@]}"
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.RepositoryOverlayReplacementAuthorityTest --tests io.github.amichne.kast.indexstore.RepositoryOverlayUnchangedFileTest --no-daemon --console=plain
cargo test --manifest-path cli-rs/Cargo.toml --locked --test runtime_durable_ownership_smoke
./gradlew :indexer:test --tests io.github.amichne.kast.idea.backend.KastRuntimeReadinessTest --tests io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectImportBridgeReadActionTest --no-daemon --console=plain
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_readiness_smoke doctor_retains_host_supplied_macos_backend
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_transition_response_policy
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.RpcRequestWaitPolicyTest --tests io.github.amichne.kast.server.dispatcher.raw.AnalysisDispatcherRawMutationRecoveryTest --no-daemon --console=plain
./gradlew :indexer:test --tests io.github.amichne.kast.idea.WorkspaceTransitionIngressTest --tests io.github.amichne.kast.idea.backend.KastDiagnosticsCompletenessTest --tests io.github.amichne.kast.idea.backend.diagnostics.DiagnosticContentAuthorityTest --no-daemon --console=plain
```

Observed result: PASSED. Overlay replacement and unchanged-file authority tests
passed; all 19 durable-ownership tests passed; both readiness/read-action tests
passed; the host-supplied backend regression passed; and the transition timeout,
server mutation deadline, ingress freshness, diagnostic completeness, and typed
content-authority regressions passed. Exact installed-head semantic analysis also
completed all 77 changed production Kotlin files with zero errors, warnings,
infos, or skipped files.
