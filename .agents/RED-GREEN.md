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

## GREEN

Commands:

```shell
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.WorkspacePathLayoutTest --no-daemon
./gradlew :index-store:test --tests io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStoreTest :indexer:test --tests io.github.amichne.kast.idea.transition.WorkspaceTransitionCoordinatorTest --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --tests io.github.amichne.kast.idea.IndexerServerRuntimeTest --no-daemon
./gradlew test --no-daemon
cargo test --manifest-path cli-rs/Cargo.toml --locked --quiet
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo fmt --manifest-path cli-rs/Cargo.toml -- --check
python3 .github/scripts/check-repository-shape.py
bash -n scripts/release/benchmark-real-repositories.sh
```

Observed result: flat-routing, atomic-publication, overlay-read, typed-alias, and pre-decode manifest-boundary tests passed; the complete Gradle test graph passed; every locked Cargo unit and integration target passed; strict Clippy passed with warnings denied; Rust formatting and benchmark shell syntax passed; repository shape reported `ok: true` with zero file, directory, missing-path, or retired-surface violations. The 51 changed production Kotlin files were audited with zero newly added nullable or primitive control protocols.
