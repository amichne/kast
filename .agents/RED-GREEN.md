# RED-GREEN Evidence

## RED

### Git repository-selector isolation

Command:

```shell
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.ReadOnlyGitCommandTest --no-daemon --console=plain
```

Expected failure: a read-only Git process retains inherited repository-selection variables instead of deriving a selector-free process environment.

Observed failure: `:analysis-api:compileTestKotlin` failed because
`ReadOnlyGitProcessEnvironment` was unresolved and `processBuilder` accepted no
proof-bearing environment argument. The focused command exited 1 after 20s,
mechanically proving the selector-free environment transition was absent.

### Snapshot tree binding

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --no-daemon --console=plain
```

Expected failure: a preparation captured for one clean Git tree exports the already-indexed database under a later clean tree when the workspace moves before completion publication.

Observed failure: pending.

## GREEN

### Git repository-selector isolation

Command:

```shell
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.client.ReadOnlyGitCommandTest --no-daemon --console=plain
```

Observed result: the same focused test passed (`BUILD SUCCESSFUL in 19s`).
Kast then reported `semanticOutcome: COMPLETE` for both changed files with
2/2 analyzed and zero diagnostics after the refreshed generation reached
READY. The commit-gate rerun was also successful (`BUILD SUCCESSFUL in 3s`,
all focused tasks up to date), and repository-shape validation reported zero
violations.

### Snapshot tree binding

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --no-daemon --console=plain
```

Observed result: pending.
