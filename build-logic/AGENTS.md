# Build logic agent guide

`build-logic` owns the `kast.*` convention plugins and the reusable Gradle
tasks that shape every module in the repo.

## Ownership

Assume every edit in this unit can affect the whole repo.

- Keep this unit focused on shared build behavior: toolchains, test setup,
  fat-jar packaging, runtime-lib syncing, wrapper generation, and reusable
  dependency bundles.
- `kast.standalone-app` is the shared packaging contract for app modules. Keep
  task names and output layout stable unless every consumer is updated
  together.
- `SyncRuntimeLibsTask` and `WriteWrapperScriptTask` define the runtime-libs
  and wrapper layout that `build.sh`, `kast`, and portable dist packaging
  expect.
- Do not put product behavior or workspace-specific runtime logic here. If a
  change affects shipped analysis behavior, it probably belongs in an
  application or backend module instead.
- Treat version bumps and plugin changes as cross-repo work. A small edit here
  can alter every module's compile, test, or packaging behavior.
- Preserve Java 21 and the shared version-catalog linkage unless the build is
  being upgraded deliberately.

## Verification

Validate both the immediate target and the wider build impact.

- Run the affected module tasks that consume the changed convention, starting
  with `./gradlew :kast:syncRuntimeLibs :kast:writeWrapperScript` for wrapper
  or runtime-lib changes.
- For significant build-logic edits, run `./gradlew build`.
