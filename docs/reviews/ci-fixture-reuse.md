# Immutable dependency fixtures

The local comparison preserved all 237 semantic cases and their expected outcomes.
Dependency compilation decreased from 47 compiler processes to one.
One separate consumer compilation remained.
The six-minute CI criterion remains open.

## Scope

The baseline revision was `371fb8bfcb7c2c22a12ab373b3d7c49d1fbb528a`.
It contains all 159 cases from the audited `9269df7` revision, plus 78 newer cases.
No original case ID or expected outcome changed between those revisions.
This change keeps the complete current manifest and every semantic assertion.

`:relation:intellij:compileDeclaredEffectFixture` owns one dependency archive.
Its inputs include both authored source copies, compiler libraries, dependency libraries, compiler arguments, the Java launcher, and the JDK release identity.
Different authored copies reject before compilation.
The task removes its previous output before compilation and publishes a complete archive through an atomic move.

Each relation or hosted case copies the archive into its own temporary directory.
Projects, indexes, source roots, leases, queries, and cleanup remain case-owned.
Gradle tracks the archive as a test input and keeps its producer dependency.
Cross-run artifact caching remains disabled until portable archive repeatability has separate evidence.

## Local evidence

Both runs used the same local macOS host, wrapper, GraalVM 25.0.2, and pinned IDEA distribution.
Both commands used `--no-build-cache` and executed all three semantic tasks.
Existing task outputs and operating-system caches differed between runs.
These timings do not isolate every cause of the elapsed-time change.
They do not establish performance on the CI runner.

| Measurement | Baseline | Fixture reuse |
| --- | ---: | ---: |
| Required semantic cases | 237 | 237 |
| Failures or skipped cases | 0 | 0 |
| Dependency compiler processes | 47 | 1 |
| Consumer compiler processes | 1 | 1 |
| Relation suite time | 169.353 seconds | 84.730 seconds |
| Hosted suite time | 115.187 seconds | 44.117 seconds |
| Symbol suite time | 40.097 seconds | 17.672 seconds |
| Gradle elapsed time | 250 seconds | 182 seconds |
| Executed tasks | 94 | 52 |
| Up-to-date tasks | 10 | 53 |

The summed suite times describe work that can overlap.
The symbol fixture implementation did not change.
Its timing difference shows why this comparison cannot attribute every elapsed-time decrease to dependency reuse.

The prepared `effects/DependencyEffectsKt.class` digest matches the baseline digest:

```text
56d3b4c2555efd487088dda3fbfb689611e9c5d1c16066e4c38f14d92ed6222e
```

The Gradle functional tests passed four cases with real compiler processes.
They cover two consumers, unchanged-input reuse, source changes under the same filename, compiler libraries, dependencies, options, and JDK identity.
They also cover failed compilation and different authored copies.
The unchanged-input case reused the configuration cache and started zero compiler processes.
The failure case removed the archive and prevented consumer execution.

Both helper isolation cases passed.
Each module attaches `dependencyFixtureTest` to `check` and `scopedCheck`.
A changed or deleted case-owned archive left the other copy and the prepared archive unchanged.
The JSON contract, architecture, and knowledge guards passed.

## Commands and reports

The baseline and candidate both passed this command:

```sh
mise exec -- ./gradlew verifyKotlinSemanticCoverage --no-build-cache --profile --console=plain
```

The Gradle task tests passed this command:

```sh
mise exec -- ./gradlew -p build-logic test --tests 'support.tasks.nativefixtures.CompileDependencyFixtureTest' --console=plain
```

Local logs, copied JUnit reports, and Gradle profiles reside under `build/reports/ci-performance/`.
The `baseline` and `fixture-reuse` directories separate the two runs.
The unchanged `cases.tsv` identifies the exact 237 required cases.

## Remaining completion criteria

The source-validation split, affected-consumer selection, hosted replay corpus, and final candidate replay remain separate work.
Source feedback must meet 360 seconds for every frozen routine case before the performance task completes.
CI selection, candidate qualification, release versions, and publication admission remain unchanged in this fixture change.
