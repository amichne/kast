# RED-GREEN Evidence

## RED

Command:

```shell
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.WorkspacePathLayoutTest --no-daemon
```

Expected failure: canonical workspace paths still resolve through the nested local or Git worktree layout instead of `workspaces/<full-path-digest>`.

Observed failure: `WorkspacePathLayoutTest` failed at line 40 because the expected flat path ending in `c151fb883688eba2e5561550e6e3f02b70befc1ada0fec6e00b0226379d46659` resolved instead through `workspaces/git/local/918bf045acf7/worktrees/workspace--27d06f6bc031` (11 tests completed, 1 failed).

Atomic-publication command:

```shell
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.store.WorkspaceWriteTransactionTest --no-daemon
```

Observed failure: test compilation failed because `beginWorkspaceWrite` and `discardWorkspaceWrite` did not exist, proving the store had no transaction spanning a workspace reconciliation.

Rust-reader command:

```shell
cargo test --manifest-path cli-rs/Cargo.toml --locked resolves_publication_from_the_single_workspace_database
```

Observed failure: the reader returned `PUBLISHED_WORKSPACE_UNAVAILABLE` for `semantic-generations/current.json` even though the single workspace database contained a valid publication row (1 failed, 326 filtered out).

Overlay-read command:

```shell
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.RepositoryOverlayReadAuthorityTest --no-daemon
```

Expected failure: an unchanged file retained only in the shared repository base is absent from source, declaration, manifest, and detailed semantic-graph reads.

Observed failure: `RepositoryOverlayReadAuthorityTest` expected identifiers `[UnchangedBase, ChangedMain]` but read only `[ChangedMain]` (1 test completed, 1 failed), proving unchanged shared-base facts were absent.

Semantic-ratchet command:

```shell
./gradlew :index-store:compileKotlin --no-daemon
```

Expected failure: after replacing arbitrary overlay table strings with a closed `SourceIndexReadTable` contract, every raw-string caller must fail compilation until it carries the typed table proof.

Observed failure: `:index-store:compileKotlin` failed at `SqliteSourceIndexStoreState.kt:318` because raw `"file_manifest"` was a `String` where the new closed `SourceIndexReadTable` proof was required. Compilation stopped before any untyped relation could cross the new contract.

Metadata-boundary command:

```shell
./gradlew :index-store:test --tests 'io.github.amichne.kast.indexstore.RepositorySnapshotStoreTest.retained inventory rejects a symlinked manifest before decoding it' --no-daemon
```

Observed failure: test compilation failed because the finite `RepositorySnapshotMetadataFailure.SnapshotManifestInvalid` outcome did not yet exist, proving retained-inventory discovery had no pre-decode non-symlink proof.

Release-benchmark contract command:

```shell
./.github/scripts/test-release-indexing-benchmark-contract.sh
```

Expected failure: benchmark evidence fixtures still publish generation-relative database paths instead of the canonical flat `source-index.db` authority.

Observed failure: the contract reached its publication-path assertion and exited 1 with `published workspace database path is not canonical`, matching exact-head CI job `92799028502`.

Full-Kotlin semantic-ratchet command:

```shell
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.ReadOnlyGitCommandTest --tests io.github.amichne.kast.api.contract.RuntimeStatusResponseTest :indexer:test --tests io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiterTest --tests io.github.amichne.kast.idea.KastIdeaProjectIndexingRuntimeTest --no-daemon --console=plain
```

Expected failure: the full PR still exposes arbitrary Git command lists, Boolean/Int readiness inputs, nullable progress timestamps, raw retry counters, and exception-based expected progress failures instead of proof-carrying transitions.

Observed failure: test compilation failed on the absent `ReadOnlyGitOperation`, `RuntimeReadinessSummary`, `RuntimeProgressWork`, `RuntimeProgressTiming`, `ConsecutiveIndexingFailures`, `RuntimeProgressWaitPolicy`, `MonotonicClock`, and closed `RuntimeProgressAwaitOutcome` contracts. This proves the remaining 18 production Kotlin files had not yet crossed the requested semantic boundary.

## GREEN

Commands:

```shell
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.ReadOnlyGitCommandTest --tests io.github.amichne.kast.api.contract.RuntimeStatusResponseTest :indexer:test --tests io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiterTest --tests io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementAwaiterTest --tests io.github.amichne.kast.idea.KastIdeaProjectIndexingRuntimeTest --tests io.github.amichne.kast.idea.snapshot.ReadOnlyGitCommandIntegrationTest --no-daemon --console=plain
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.WorkspacePathLayoutTest --no-daemon
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStoreTest :indexer:test --tests io.github.amichne.kast.idea.transition.WorkspaceTransitionCoordinatorTest --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --tests io.github.amichne.kast.idea.IndexerServerRuntimeTest --no-daemon
./gradlew test --no-daemon
cargo test --manifest-path cli-rs/Cargo.toml --locked --quiet
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo fmt --manifest-path cli-rs/Cargo.toml -- --check
python3 .github/scripts/check-repository-shape.py
bash -n scripts/release/benchmark-real-repositories.sh
./.github/scripts/test-release-indexing-benchmark-contract.sh
```

Observed result: the focused proof-carrying Git, readiness, progress, Gradle-settlement, retry, and integration suite passed; flat-routing, atomic-publication, overlay-read, typed-alias, and pre-decode manifest-boundary tests passed; the complete Gradle test graph passed in 1m21s; every locked Cargo unit and integration target passed; strict Clippy passed with warnings denied; Rust formatting and benchmark shell syntax passed; repository shape reported `ok: true` with zero file, directory, missing-path, or retired-surface violations. The release indexing benchmark contract passed against flat schema-15 workspace-publication fixtures. All 71 current changed production Kotlin files were reviewed with zero newly introduced primitive, nullable-control, string, discarded-validation, or arbitrary-exception result protocols outside explicit process, serializer, Java, or IntelliJ boundaries.
