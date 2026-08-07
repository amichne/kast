# RED-GREEN Evidence

## RED

### Overlay boundary symbol authority

Command:

```shell
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.RepositoryOverlayReadAuthorityTest --no-daemon --console=plain
```

Expected failure: a cached main boundary row suppresses the authoritative base
symbol, discarding its annotation while a changed-file edge targets it.

Observed failure: FAILED as expected. The changed-file edge remained present,
but its unchanged target resolved to the cached boundary row and returned no
annotations instead of `sample.AuthoritativeAnnotation` from the base symbol.

### Ignored non-source snapshot eligibility

Command:

```shell
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.RepositoryOverlayReadAuthorityTest --no-daemon --console=plain
./gradlew :index-store:test --no-daemon --console=plain
./gradlew :indexer:test --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --no-daemon --console=plain
```

Expected failure: an ignored generated Kotlin file under a hard-excluded
directory is treated as source authority and rejects an otherwise reusable
committed snapshot tree.

Observed failure: FAILED as expected. With only `A.kt` committed and
`build/generated/Generated.kt` ignored, the resolver returned `Unavailable`
instead of the expected `Resolved` committed tree; the focused class ran eight
tests with exactly this regression failing.

### Terminal progress-wait authority

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiterTest --no-daemon --console=plain
```

Expected failure: a future or condition that completes during the polling pause
is accepted without rechecking typed project-disposal and deadline evidence.

Observed failure: FAILED as expected. All four deterministic races returned
`RuntimeProgressAwaitOutcome.Completed`: future and condition completion each
outran both project disposal and the no-progress deadline reached during pause.

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
./gradlew :indexer:test --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --no-daemon --console=plain
./gradlew :indexer:test --tests io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiterTest --no-daemon --console=plain
python3 .github/scripts/check-repository-shape.py --root .
./gradlew check --no-daemon --console=plain
kast refresh index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/overlay/RepositoryOverlaySemanticViews.kt
kast refresh indexer/src/main/kotlin/io/github/amichne/kast/idea/snapshot/CommittedGitTreeResolver.kt
kast refresh indexer/src/main/kotlin/io/github/amichne/kast/indexer/gradle/settlement/ProgressAwareFutureAwaiter.kt
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

Observed result: PASSED. The full index-store suite passed; the overlay boundary
target retained its authoritative base annotation and the changed-file relation;
all eight repository snapshot
integration tests passed; overlay replacement and unchanged-file authority tests passed; all seven
terminal wait-authority tests passed; all 19 durable-ownership tests passed;
both readiness/read-action tests
passed; the host-supplied backend regression passed; and the transition timeout,
server mutation deadline, ingress freshness, diagnostic completeness, and typed
content-authority regressions passed. The full Gradle check and repository-shape
contract passed. Exact installed-head semantic analysis also completed all 77
changed production Kotlin files with zero errors, warnings, infos, or skipped
files.
