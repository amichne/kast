---
title: Remaining work
description: Gaps between the current bootstrap and the intended production
  shape.
icon: lucide/construction
---

# Remaining work

This page lists implementation areas that remain incomplete. The verification
baseline covers shared contract fixtures, standalone bootstrap and packaging
smoke checks, and operator documentation for real repositories.

## Call hierarchy support

The contract and route exist for call hierarchy, but no production backend
implements it yet.

- **Status:** Not implemented in any production backend
- **Current state:** The API model includes `CALL_HIERARCHY`, and the server
  exposes `/api/v1/call-hierarchy`
- **Where:** `analysis-api/src/main/kotlin/io/github/amichne/kast/api/AnalysisBackend.kt`,
  `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisApplication.kt`
- **Missing:** Real call graph discovery in IntelliJ and standalone backends
- **Next step:** Implement call hierarchy in IntelliJ first using K2 Analysis API
  (`analyze(ktFile)` context for incoming/outgoing call traversal), then bring
  standalone to parity. Shared traversal logic should go in `:analysis-common`.

## Standalone dependency hardening

The standalone backend resolves the IntelliJ 2025.3 distribution from the
Gradle transform cache populated by building `backend-intellij` first. The
compat JAR strategy (bridging `analysis-api-standalone-for-ide` internal APIs
against stable IJ 2025.3) has been retained and version-bumped.

- **Status:** Partial
- **Current state:** `analysis-api-standalone-for-ide` is pinned to
  `2.3.20-ij253-119` (latest ij253 build). The compat JAR bridges
  `PathResolver.resolvePath(4-arg)` and related internal IJ APIs that the AA
  still calls against old `kotlin-compiler.jar` forms.
- **Where:** `backend-standalone/build.gradle.kts`,
  `backend-standalone/src/compat/java/`
- **Missing:** A strategy for resolving the IJ distribution without depending on
  the Gradle transform cache (currently requires running
  `./gradlew :backend-intellij:build` first)
- **Next step:** Investigate using `intellijPlatform` dependency resolution in
  `backend-standalone` to download the IJ distribution deterministically, or
  wait for a future `analysis-api-standalone-for-ide` release that aligns with
  stable IJ 2025.3 APIs (which would allow the compat JAR to be removed entirely)

## ~~IntelliJ diagnostics~~

**Done.** The IntelliJ backend now collects Kotlin semantic diagnostics using
the K2 Analysis API (`collectDiagnostics(EXTENDED_AND_COMMON_CHECKERS)`) and
falls back to PSI parse errors for non-Kotlin files.

## ~~IntelliJ rename fidelity~~

**Done.** Rename planning uses `RenameProcessor` for full IDE refactoring
semantics including override-aware renames and JVM-specific edge cases.

## ~~IntelliJ read-action hardening~~

**Done.** All reads use `ReadAction.nonBlocking(Callable { ... }).inSmartMode(project)`.
`DumbService` usage was removed.

## ~~IntelliJ K2 compatibility verification~~

**Done.** `plugin.xml` declares `<supportsKotlinPluginMode supportsK2="true" />`,
`buildSearchableOptions` passes, and IntelliJ backend tests include a full
startup smoke test. Target: IJ 2025.3 (`sinceBuild = "253"`).

## Network hardening

Safe defaults exist and are now enforced by code.

- **Status:** Done
- **Current state:** `AnalysisServerConfig` validates at construction time that
  non-loopback hosts require a non-empty token. Binding to `0.0.0.0` or any
  non-loopback address without a token throws `IllegalArgumentException` at
  startup.
- **Where:** `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt`

## ~~Dependency simplification~~

**Done** (partial). Several dependency-handling improvements were completed:

- `analysis-api-standalone-for-ide` bumped from `2.3.20-ij253-87` to
  `2.3.20-ij253-119` (latest ij253 build). The compat JAR is retained — see
  "Standalone dependency hardening" above.
- `kas.ktor-service` convention plugin deleted; `analysis-server` now declares
  Ktor dependencies directly using version catalog references (`libs.bundles.ktor.server`).
- `build-logic` convention plugins (`kas.kotlin-library`, `kas.intellij-plugin`)
  now read JUnit and IntelliJ `sinceBuild` versions from the shared
  `gradle/libs.versions.toml` via `VersionCatalogsExtension`.
- `build-logic/build.gradle.kts` plugin dependencies now reference the version
  catalog instead of hardcoded strings.
- New `:analysis-common` module extracts shared PSI and K2 Analysis API utilities
  (`resolveTarget`, `nameRange`, `declarationEdit`, `fqName`, `kind`,
  `typeDescription`, `toApiDiagnostics`, `toApiSeverity`, `toKastLocation`) that
  were duplicated between both backends. `StandaloneSymbolMapper.kt` was deleted
  and both backends now import from `analysis-common`.
