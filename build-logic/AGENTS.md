# Build logic agent guide

`build-logic` owns the `kast.*` convention plugins and the reusable Gradle
tasks that shape every module in the repo.

## Ownership

Assume every edit in this unit can affect the whole repo.

- Keep this unit focused on shared build behavior: toolchains, test setup,
  fat-jar packaging, runtime-lib syncing, wrapper generation, and reusable
  dependency bundles.
- `kast.headless-app` is the shared packaging contract for app modules. Keep
  task names and output layout stable across every consumer.
- `SyncRuntimeLibsTask` and `WriteWrapperScriptTask` define the runtime-libs
  and wrapper layout that `kast.sh`, `kast`, and portable dist packaging
  expect.
- Product behavior and workspace-specific runtime logic belong in application,
  backend, or CLI modules.
- Treat version bumps and plugin changes as cross-repo work. A small edit here
  can alter every module's compile, test, or packaging behavior.
- Java 21 and shared version-catalog linkage are the build baseline.

## Verification

Validate both the immediate target and the wider build impact.

- For test-tag selection behavior, run
  `./gradlew -p build-logic test --tests DefaultTestTagSelectionTest`.
- Convention plugin test filtering supports `-PincludeTags=<tag1,tag2>` and
  `-PexcludeTags=<tag1,tag2>`. Default runs skip `concurrency`,
  `performance`, and `parity`; explicit include tags select those suites.
- Run the affected module tasks that consume the changed convention, starting
  with `./gradlew :backend-headless:syncRuntimeLibs :backend-headless:portableDistZip`
  for runtime-lib or portable distribution changes.
- For significant build-logic edits, run `./gradlew build`.
