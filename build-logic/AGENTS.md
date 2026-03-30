# Build logic agent guide

`build-logic` owns the convention plugins that shape every Gradle module in the
repo.

## Ownership

Assume every edit in this unit can affect the whole repo.

- Keep this unit focused on build conventions: toolchains, shared test setup,
  packaging, IntelliJ plugin defaults, and reusable dependency bundles.
- Do not put product behavior or runtime code here. If a change affects the
  shipped server, it probably belongs in an application module instead.
- Treat version bumps and plugin changes as cross-repo work. A small edit here
  can alter every module's compile, test, or packaging behavior.
- Keep convention plugins narrow and composable. Add new plugins only when a
  reusable pattern is stable across more than one module.
- Preserve Java 21 and Kotlin toolchain expectations unless the repo is being
  upgraded deliberately.

## Verification

Validate both the immediate target and the wider build impact.

- Run the affected module builds plus a broader repo build when convention
  behavior changes materially.
- For significant build-logic edits, run `./gradlew build`.
