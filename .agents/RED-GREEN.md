# RED-GREEN Evidence

## RED

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --no-daemon --console=plain
```

Expected failure: a reconciliation captured for clean Git tree A exports the already-indexed database under later clean Git tree B when the workspace moves after READY but before completion publication.

Observed failure: `RepositorySnapshotIntegrationTest` failed because preparation captured tree `b3a87c7d`, the workspace moved to clean tree `21bbc365`, and publication returned `Completed` under `21bbc365` instead of `Skipped(CommittedTreeMoved(b3a87c7d, 21bbc365))`. The focused command exited 1 after 1m5s (7 tests, 1 failed), proving export was not bound to the tree that the completed source index reconciled.

## GREEN

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.idea.RepositorySnapshotIntegrationTest --tests io.github.amichne.kast.idea.WorkspaceTransitionWorkerBuildSemanticTest --no-daemon --console=plain
```

Observed result: the exact focused suite passed (`BUILD SUCCESSFUL in 21s`).
The snapshot race test proved tree A capability rejects later tree B, then the
same preparation captured a new tree B capability that published after the B
index reconciliation. The worker test proved READY completion receives the
same snapshot capability carried by its reconciled candidate. Repository-shape
validation reported zero violations. The complete `:indexer:test` suite then
passed (`BUILD SUCCESSFUL in 1m10s`). After replacing nullable completion
control with a closed pending-completion state, the focused suite passed again
in 29s. Kast compiler-backed analysis completed for all 5 changed production
Kotlin files with 5/5 analyzed and zero diagnostics. The final complete
`:indexer:test` rerun passed (`BUILD SUCCESSFUL in 1m8s`, 523 tests).
