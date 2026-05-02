# Plan: Reduce CLI Duplication + Install Copilot-Extension + IntelliJ Settings UI

## Context

The user's original goal was to find and eliminate duplication in the CLI layer — specifically to extract shared abstractions for the install/embedded-resource pattern so new commands don't re-implement the same file-management logic. That structural refactor is the foundation; the concrete new features (copilot-extension install command, IntelliJ settings UI) are built on top of it.

Currently `EmbeddedSkillResources` and `InstallSkillService` encode a full install pipeline inline: manifest reading, resource extraction, version marker checks, recursive delete, force-flag logic. Adding `InstallCopilotExtensionService` without extracting shared base classes would duplicate ~120 lines verbatim. The plan therefore starts with the abstraction, then adds the new feature on top of the clean base.

---

## Part 0 — Shared Abstractions (prerequisite for everything else)

### 0a. `EmbeddedResourceBundle` (replaces inline logic in `EmbeddedSkillResources`)

**New file:** `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/EmbeddedResourceBundle.kt`

```kotlin
internal abstract class EmbeddedResourceBundle(
    val version: String,
    protected val resourceRoot: String,
    val manifest: List<String>,
    val versionMarkerFileName: String,
    private val resourceReader: (String) -> InputStream?,
) {
    fun writeTree(targetDir: Path) {
        Files.createDirectories(targetDir)
        manifest.forEach { relativePath ->
            val targetPath = targetDir.resolve(relativePath)
            targetPath.parent?.let(Files::createDirectories)
            openResource(relativePath).use { input ->
                Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.writeString(targetDir.resolve(versionMarkerFileName), "$version${System.lineSeparator()}")
    }

    private fun openResource(relativePath: String): InputStream =
        resourceReader(relativePath) ?: throw CliFailure(
            code = "INSTALL_ERROR",
            message = "Bundled resource not found: /$resourceRoot/$relativePath",
        )
}
```

**Refactor:** `EmbeddedSkillResources` becomes a thin subclass that supplies `RESOURCE_ROOT`, `MANIFEST`, `VERSION_MARKER_FILE_NAME`, and the `::class.java.getResourceAsStream` lambda. Its public API (`version`, `writeSkillTree`) is preserved unchanged so callers don't break.

### 0b. `InstallEmbeddedResourceService` (replaces inline logic in `InstallSkillService`)

**New file:** `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallEmbeddedResourceService.kt`

Extracts the full directory-management pipeline out of `InstallSkillService`:
- version-marker read (`readInstalledVersion`)
- symlink / directory / file detection
- force-flag gate
- recursive delete (`deletePathRecursively`)

Concrete subclasses supply `bundle: EmbeddedResourceBundle`, the error code prefix, and the `resolveDefaultTargetDir` strategy (each install type may differ).

**Shared result type:** `InstallEmbeddedResult` (rename of `InstallSkillResult` with a type-alias or sealed base) — existing `InstallSkillResult` keeps its name and `@Serializable` annotation; `InstallCopilotExtensionResult` mirrors the same three fields.

---

## Part 1 — `install copilot-extension` CLI command

### Files to create

| File | Purpose |
|---|---|
| `kast-cli/.../EmbeddedCopilotExtensionResources.kt` | Subclass of `EmbeddedResourceBundle`; `RESOURCE_ROOT = "packaged-copilot-extension"`, `VERSION_MARKER_FILE_NAME = ".kast-copilot-version"` |
| `kast-cli/.../InstallCopilotExtensionService.kt` | Subclass of `InstallEmbeddedResourceService`; `resolveDefaultTargetDir` targets `<cwd>/.github` |
| `kast-cli/.../options/InstallCopilotExtensionOptions.kt` | `data class InstallCopilotExtensionOptions(val targetDir: Path?, val force: Boolean)` |
| `kast-cli/.../results/InstallCopilotExtensionResult.kt` | `@Serializable data class` mirroring `InstallSkillResult` fields |

### Files to modify

**`tty/CliCommand.kt`** — add:
```kotlin
data class InstallCopilotExtension(val options: InstallCopilotExtensionOptions) : CliCommand
```

**`tty/CliCommandCatalog.kt`** — add one `CliCommandMetadata` entry:
```kotlin
CliCommandMetadata(
    path = listOf("install", "copilot-extension"),
    group = CliCommandGroup.CLI_MANAGEMENT,
    summary = "Install the kast Copilot agents and hooks into the current workspace.",
    ...
    options = listOf(copilotTargetDirOption, yesOption),
)
```
(`copilotTargetDirOption` is a new private val describing `--target-dir` with description pointing to `.github/`)

**`tty/CliCommandParser.kt`** — add to `parseKnownCommand`:
```kotlin
listOf("install", "copilot-extension") -> CliCommand.InstallCopilotExtension(parsed.installCopilotExtensionOptions())
```
Add `ParsedArguments.installCopilotExtensionOptions()` (reads `target-dir` and `yes`).

**`tty/CliService.kt`** — add:
```kotlin
private val installCopilotExtensionService: InstallCopilotExtensionService = InstallCopilotExtensionService()
fun installCopilotExtension(options: InstallCopilotExtensionOptions): InstallCopilotExtensionResult =
    installCopilotExtensionService.install(options)
```

**`tty/CliExecution.kt`** (`DefaultCliCommandExecutor.execute`) — add:
```kotlin
is CliCommand.InstallCopilotExtension -> CliExecutionResult(
    output = CliOutput.JsonValue(cliService.installCopilotExtension(command.options)),
)
```

### Gradle resource sync (`kast-cli/build.gradle.kts`)

Add a `syncPackagedCopilotExtensionResources` Sync task that copies hook scripts from `.github/hooks/` and agent markdown files from `.github/agents/` into `build/generated/packaged-copilot-extension-resources/packaged-copilot-extension/`. Wire into `processResources`.

**Note:** `.github/agents/` does not yet exist. Create stub agent markdown files (`kast.md`, `explore.md`, `plan.md`, `edit.md`) in `.github/agents/` as part of this task. Their content can be minimal placeholders — the packaging infrastructure is the deliverable here.

MANIFEST for `EmbeddedCopilotExtensionResources`:
```kotlin
val MANIFEST = listOf(
    "agents/kast.md",
    "agents/explore.md",
    "agents/plan.md",
    "agents/edit.md",
    "hooks/hooks.json",
    "hooks/session-start.sh",
    "hooks/record-paths.sh",
    "hooks/require-skills.sh",
    "hooks/session-end.sh",
    "hooks/resolve-kast-cli-path.sh",
)
```

---

## Part 2 — IntelliJ Settings UI

### 2a. `KastSettingsState`

**New file:** `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/KastSettingsState.kt`

A project-level `@State`/`@Service` `PersistentStateComponent<KastSettingsState>`. Fields mirror `KastConfigOverride` — all nullable. Provides bridge between `.idea/` storage and the TOML file:

```kotlin
@State(name = "KastSettings", storages = [Storage("kast.xml")])
@Service(Service.Level.PROJECT)
class KastSettingsState : PersistentStateComponent<KastSettingsState> {
    // Server
    var serverMaxResults: Int? = null
    var serverRequestTimeoutMillis: Long? = null
    var serverMaxConcurrentRequests: Int? = null
    // Indexing
    var indexingPhase2Enabled: Boolean? = null
    var indexingPhase2BatchSize: Int? = null
    var indexingIdentifierIndexWaitMillis: Long? = null
    var indexingReferenceBatchSize: Int? = null
    var indexingRemoteEnabled: Boolean? = null
    var indexingRemoteSourceIndexUrl: String? = null
    // Cache
    var cacheEnabled: Boolean? = null
    var cacheWriteDelayMillis: Long? = null
    var cacheSourceIndexSaveDelayMillis: Long? = null
    // Watcher
    var watcherDebounceMillis: Long? = null
    // Gradle
    var gradleToolingApiTimeoutMillis: Long? = null
    var gradleMaxIncludedProjects: Int? = null
    // Telemetry
    var telemetryEnabled: Boolean? = null
    var telemetryScopes: String? = null
    var telemetryDetail: String? = null
    var telemetryOutputFile: String? = null
    // Backends
    var backendsStandaloneEnabled: Boolean? = null
    var backendsStandaloneRuntimeLibsDir: String? = null
    var backendsIntellijEnabled: Boolean? = null

    override fun getState() = this
    override fun loadState(state: KastSettingsState) = XmlSerializerUtil.copyBean(state, this)

    fun toOverride(): KastConfigOverride { ... }  // builds override from nullable fields
}
```

### 2b. `KastSettingsConfigurable`

**New file:** `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/KastSettingsConfigurable.kt`

Implements `com.intellij.openapi.options.Configurable` (project-level). Uses IntelliJ Kotlin UI DSL (`panel { }` from `com.intellij.ui.dsl.builder`).

Key methods:
- `createComponent()` — builds a `panel { }` with one `group()` per config section (Server, Indexing, Cache, Watcher, Gradle, Telemetry, Backends). Each field becomes an `intTextField`, `longTextField`, `checkBox`, or `textField` row.
- `isModified()` — compares current UI values against `KastSettingsState.getInstance(project)`.
- `reset()` — reads from `KastConfig.load(workspaceRoot)` to show resolved (default-merged) values; also syncs to `KastSettingsState`.
- `apply()` — writes TOML overrides to workspace config.toml (via `workspaceDirectoryResolver.workspaceDataDirectory(workspaceRoot).resolve("config.toml")`). If server.* or backends.* fields changed, calls `KastPluginService.getInstance(project).restartServer()`.

**Workspace root resolution:** `project.basePath ?: return` (same pattern as `KastPluginService.startServer()`).

**TOML generation:** Build minimal TOML from non-null `KastSettingsState` fields; don't write fields that are unchanged from defaults. Use simple `buildString { appendLine("[server]") ... }` — no external TOML library needed for writing (only Hoplite is needed for reading).

### 2c. `plugin.xml` registrations

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- existing entries ... -->
    <projectService serviceImplementation="io.github.amichne.kast.intellij.KastSettingsState"/>
    <projectConfigurable
        instance="io.github.amichne.kast.intellij.KastSettingsConfigurable"
        id="io.github.amichne.kast.settings"
        displayName="Kast"
        parentId="tools"/>
</extensions>

<actions>
    <group id="io.github.amichne.kast.actions" text="Kast" popup="true">
        <add-to-group group-id="ToolsMenu" anchor="last"/>
        <action id="io.github.amichne.kast.installSkill"
                class="io.github.amichne.kast.intellij.actions.InstallSkillAction"
                text="Install Kast Skill"
                description="Install the kast agent skill into this project"/>
        <action id="io.github.amichne.kast.installCopilotExtension"
                class="io.github.amichne.kast.intellij.actions.InstallCopilotExtensionAction"
                text="Install Copilot Extension"
                description="Install kast Copilot agents and hooks into this project"/>
    </group>
</actions>
```

---

## Part 3 — IntelliJ Actions

### `InstallSkillAction` and `InstallCopilotExtensionAction`

**New files:**
- `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/actions/InstallSkillAction.kt`
- `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/actions/InstallCopilotExtensionAction.kt`

Both extend `AnAction`. Common pattern extracted into a private helper or a shared `KastInstallAction` abstract base:

```kotlin
abstract class KastInstallAction : AnAction() {
    abstract fun buildArgs(workspaceRoot: Path): List<String>
    abstract fun successMessage(workspaceRoot: Path): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workspaceRoot = project.basePath?.let { Path.of(it) } ?: return
        val kastBinary = resolveKastBinary() ?: run {
            notifyError(project, "kast binary not found. Set KAST_CLI_PATH or ensure kast is on PATH.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = ProcessBuilder(listOf(kastBinary) + buildArgs(workspaceRoot))
                .redirectErrorStream(true).start().waitFor()
            if (result == 0) notifyInfo(project, successMessage(workspaceRoot))
            else notifyError(project, "kast command failed (exit $result)")
        }
    }

    private fun resolveKastBinary(): String? =
        System.getenv("KAST_CLI_PATH")?.takeIf { it.isNotBlank() }
            ?: findOnPath("kast")
}
```

`InstallSkillAction.buildArgs` → `["install", "skill", "--target-dir=<ws>", "--yes=true"]`  
`InstallCopilotExtensionAction.buildArgs` → `["install", "copilot-extension", "--target-dir=<ws>/.github", "--yes=true"]`

Notifications use `NotificationGroupManager.getInstance().getNotificationGroup("Kast")` + `createNotification()`. Register a notification group in plugin.xml.

---

## Part 4 — Tests

### `InstallCopilotExtensionServiceTest`

**New file:** `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/InstallCopilotExtensionServiceTest.kt`

Follows exact pattern of `InstallSkillServiceTest.kt`. Test cases:
1. Install copies bundled tree and writes `.kast-copilot-version`
2. Install skips when same version already installed
3. Install overwrites with force=true
4. Install fails without force when different version installed
5. Default target dir is `<cwd>/.github`

### `KastSettingsConfigurableTest`

**New file:** `backend-intellij/src/test/kotlin/io/github/amichne/kast/intellij/KastSettingsConfigurableTest.kt`

Minimal IntelliJ light-test:
- `isModified()` returns false after `reset()` with default config
- `isModified()` returns true after changing a value
- `apply()` + `reset()` round-trips values correctly (verify state fields, not TOML file)

### `VerifyPluginXmlPresentTask` update

In `backend-intellij/build.gradle.kts`, add checks in the `verify()` method:
```kotlin
check("KastSettingsConfigurable" in content) { "plugin.xml is missing KastSettingsConfigurable" }
check("KastSettingsState" in content) { "plugin.xml is missing KastSettingsState" }
check("InstallSkillAction" in content) { "plugin.xml is missing InstallSkillAction" }
```

---

## Part 5 — Documentation

- `docs/for-agents/install-the-skill.md` — add section for `kast install copilot-extension`
- `docs/getting-started/install.md` — mention new command
- `AGENTS.md` — add `EmbeddedCopilotExtensionResources` to contract surface inventory
- `.github/copilot-instructions.md` — note new `install copilot-extension` command in contract surface list

---

## Implementation Order

1. `EmbeddedResourceBundle` abstract class + refactor `EmbeddedSkillResources` to extend it
2. `InstallEmbeddedResourceService` abstract class + refactor `InstallSkillService` to extend it
3. Create `.github/agents/*.md` stub files
4. `EmbeddedCopilotExtensionResources`, `InstallCopilotExtensionOptions`, `InstallCopilotExtensionResult`, `InstallCopilotExtensionService`
5. Wire CLI: `CliCommand`, `CliCommandCatalog`, `CliCommandParser`, `CliService`, `CliExecution`
6. Gradle resource sync task in `kast-cli/build.gradle.kts`
7. `KastSettingsState`, `KastSettingsConfigurable`
8. `KastInstallAction`, `InstallSkillAction`, `InstallCopilotExtensionAction`
9. `plugin.xml` registrations
10. Tests
11. Docs

---

## Critical Files

| File | Action |
|---|---|
| `kast-cli/.../EmbeddedResourceBundle.kt` | CREATE — shared abstract base |
| `kast-cli/.../EmbeddedSkillResources.kt` | MODIFY — extend base, keep public API |
| `kast-cli/.../InstallEmbeddedResourceService.kt` | CREATE — shared install base |
| `kast-cli/.../InstallSkillService.kt` | MODIFY — extend base |
| `kast-cli/.../EmbeddedCopilotExtensionResources.kt` | CREATE |
| `kast-cli/.../InstallCopilotExtensionService.kt` | CREATE |
| `kast-cli/.../options/InstallCopilotExtensionOptions.kt` | CREATE |
| `kast-cli/.../results/InstallCopilotExtensionResult.kt` | CREATE |
| `kast-cli/.../tty/CliCommand.kt` | MODIFY — add variant |
| `kast-cli/.../tty/CliCommandCatalog.kt` | MODIFY — register command |
| `kast-cli/.../tty/CliCommandParser.kt` | MODIFY — parse + options builder |
| `kast-cli/.../tty/CliService.kt` | MODIFY — wire service |
| `kast-cli/.../tty/CliExecution.kt` | MODIFY — dispatch case |
| `kast-cli/build.gradle.kts` | MODIFY — copilot resource sync |
| `backend-intellij/.../KastSettingsState.kt` | CREATE |
| `backend-intellij/.../KastSettingsConfigurable.kt` | CREATE |
| `backend-intellij/.../actions/KastInstallAction.kt` | CREATE |
| `backend-intellij/.../actions/InstallSkillAction.kt` | CREATE |
| `backend-intellij/.../actions/InstallCopilotExtensionAction.kt` | CREATE |
| `backend-intellij/src/main/resources/META-INF/plugin.xml` | MODIFY |
| `backend-intellij/build.gradle.kts` | MODIFY — `VerifyPluginXmlPresentTask` |
| `.github/agents/*.md` | CREATE — stub agent files |

---

## Verification

1. `./gradlew :kast-cli:test` — all existing tests pass; new `InstallCopilotExtensionServiceTest` passes
2. `./gradlew :kast-cli:processResources` — both `packaged-skill/` and `packaged-copilot-extension/` appear in build output
3. `kast install copilot-extension --yes=true` (against a temp dir) — exits 0, creates `.kast-copilot-version`
4. `./gradlew :backend-intellij:verifyPluginXmlPresent` — new checks pass
5. `./gradlew :backend-intellij:test` — `KastSettingsConfigurableTest` passes
6. `kast help install` — shows `copilot-extension` as subcommand
