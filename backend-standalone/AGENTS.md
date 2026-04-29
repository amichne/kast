# Standalone backend agent guide

`backend-standalone` owns the headless runtime, workspace discovery, and the
standalone-specific PSI/K2 helper code.

## Ownership

Use this unit for headless host concerns and nowhere else.

- Keep host bootstrapping here: standalone server options, runtime
  startup, shutdown hooks, and the internal daemon entrypoint.
- Preserve the current runtime contract: `--key=value` arguments (the
  `--workspace-root=` flag is the sole source of the workspace root and
  defaults to the JVM working directory), normalized absolute workspace
  roots, default Unix domain socket transport at
  `$TMPDIR/kast-<workspace-hash>.sock`, and `--stdio` only for direct
  foreground serving.
- Runtime configuration is sourced from `KastConfig` (TOML at
  `$KAST_CONFIG_HOME/config.toml` plus per-workspace overrides). Do not
  reintroduce Kast-specific environment variables (`KAST_DEBUG`,
  `KAST_OTEL_*`, `KAST_CACHE_DISABLED`, `KAST_WORKSPACE_ROOT`,
  `KAST_INTELLIJ_DISABLE`, `KAST_STANDALONE_RUNTIME_LIBS`,
  `KAST_GRADLE_TOOLING_TIMEOUT_MS`, etc.); add a typed config field
  instead. Standard JVM/terminal env vars (`JAVA_HOME`, `JAVA_OPTS`,
  `NO_COLOR`, `XDG_CONFIG_HOME`) are still respected.
- Keep Gradle workspace discovery here. `GradleWorkspaceDiscovery` and
  `StaticGradleWorkspaceDiscovery` must stay aligned on module names, source
  roots, dependency edges, and large or composite-build fallbacks.
- Keep capability advertising conservative. The standalone backend currently
  implements `RESOLVE_SYMBOL`, `FIND_REFERENCES`, `CALL_HIERARCHY`,
  `DIAGNOSTICS`, `RENAME`, and `APPLY_EDITS`. Keep the advertised
  `CALL_HIERARCHY` semantics honest: bounded traversal, truncation metadata,
  and capability gating must match the real runtime behavior.
- Keep standalone-only PSI/K2 helpers and IntelliJ compatibility shims here,
  including `src/compat/java`, instead of copying IntelliJ classes into shared
  modules.
- Reuse shared transport and edit semantics from `analysis-server` and
  `analysis-api` instead of re-implementing them here.

## Verification

Build the standalone host after changes, and add tests when the surface grows.

- Run `./gradlew :backend-standalone:test` for behavior changes.
- If you touch packaging, compatibility shims, or IntelliJ distribution
  handling, also run `./gradlew :backend-standalone:build`.
