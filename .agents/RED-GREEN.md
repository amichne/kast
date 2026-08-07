# RED-GREEN Evidence

## RED

### Current-generation check fast path

Command:

```shell
cargo test --manifest-path cli-rs/Cargo.toml --locked check
```

Expected failure: public `kast check` still emits a mandatory workspace refresh before diagnostics even when the requested file hashes are already covered by the current READY publication.

Observed failure: FAILED as expected. The focused Rust regression did not compile because
`CurrentCheckAttempt` and `WorkspaceStaleness` do not yet exist, proving there is no typed
current-generation decision that distinguishes covered evidence from refreshable staleness.

### Joinable progress-aware transition wait

Command:

```shell
./gradlew :indexer:test --tests io.github.amichne.kast.idea.WorkspaceTransitionIngressTest --no-daemon --console=plain
```

Expected failure: a compatible request arriving during INDEXING is rejected instead of joining, and a progressing reconciliation remains bound to the ordinary request deadline.

Observed failure: FAILED as expected. The focused Kotlin regressions did not compile because
`WorkspaceTransitionIngress` accepts only a raw `Long` timeout and rejects the typed
`ProgressAwareFutureAwaiter`; the in-flight join and progress-aware wait contract is absent.

### Backend-owned workspace-refresh deadline

Command:

```shell
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherRawMutationTest --no-daemon --console=plain
```

Expected failure: the outer RPC dispatcher still cancels `raw/workspace-refresh` at the
ordinary request deadline instead of allowing the backend's finite progress policy to finish.

Observed failure: FAILED as expected. The 25 ms backend refresh was cancelled by the
1 ms ordinary dispatcher deadline and returned a JSON-RPC `TIMEOUT` error instead of
the expected successful `RefreshResult`.

### Failed admission outranks stale transition activity

Command:

```shell
./gradlew :indexer:test --tests 'io.github.amichne.kast.idea.WorkspaceTransitionIngressTest.failed semantic admission outranks a stale active transition observation' --no-daemon --console=plain
```

Expected failure: a failed semantic-admission proof must reject reconciliation even if the
last transition observation still reports an active lifecycle.

Observed failure: FAILED as expected. `WorkspaceTransitionRoute.derive` returned `Join`
instead of `Rejected`, proving the stale lifecycle observation outranked the newer failure.

## GREEN

Commands:

```shell
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherRawMutationTest --no-daemon --console=plain
./gradlew :indexer:test --tests io.github.amichne.kast.idea.WorkspaceTransitionIngressTest --no-daemon --console=plain
cargo fmt --manifest-path cli-rs/Cargo.toml -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
./gradlew check --no-daemon --console=plain
python3 .github/scripts/check-repository-shape.py
kast check analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcRequestWaitPolicy.kt indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/IndexerServerRuntime.kt indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/KastIdeaProjectIndexing.kt indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/transition/WorkspaceTransitionIngress.kt indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service/transition/WorkspaceTransitionRouting.kt
```

Observed result: GREEN. Both focused Kotlin suites passed. Rust formatting and clippy
passed, the complete Rust test suite passed, and a clean second Gradle `check` passed.
Repository shape reported zero file and directory violations. Installed-candidate
diagnostics covered all six changed production Kotlin files with exact hashes and zero
diagnostics in 0.46 seconds; the publication remained semantic generation 22 and source
generation 689 before and after the check. A concurrent live refresh and check against a
temporarily drifted file both completed successfully after approximately 100 seconds,
crossing the former 30-second conflict boundary without conflict and publishing the same
exact file hash.
