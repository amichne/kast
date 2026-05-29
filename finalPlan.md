In repository `amichne/kast`, the headless backend packaging currently places `backend-intellij` (and its Kotlin-plugin-dependent transitive dependencies) into `runtime-libs/`, which is loaded by the app class loader. This causes class loader conflicts because `backend-intellij` extensively references `org.jetbrains.kotlin.*` classes (KtFile, KtClass, analyze(), KtTokens, etc.) that are registered by the Kotlin plugin's own class loader.

**Goal:** Move the `headlessPluginRuntime` JARs (backend-intellij, backend-shared, analysis-api, analysis-server, index-store, coroutines) from `runtime-libs/` into `idea-home/plugins/kast-headless/lib/` so they are loaded by the plugin class loader, which declares `<depends>org.jetbrains.kotlin</depends>` and can therefore see all Kotlin plugin classes.

**File: `backend-headless/build.gradle.kts`**

1. In the `syncPortableDist` task (around line 299-312), add the `headlessPluginRuntime` JARs to the `idea-home/plugins/kast-headless/lib/` output directory. Currently only `headlessPluginDescriptorJar` is copied there. Change it so that all resolved `headlessPluginRuntime` JARs are also copied into that plugin lib directory:
   ```kotlin
   from(headlessPluginRuntime) {
       into("idea-home/plugins/kast-headless/lib")
   }
   ```

2. In the `syncRuntimeLibs` task (around line 287-297), remove `headlessPluginRuntime` from `runtimeJars`. The app classpath should only contain the launcher JAR (`headlessLauncherJar`) and platform/IDE libraries — NOT the plugin-dependent code. Adjust `runtimeJars.from(...)` so it no longer includes `headlessPluginRuntime`. You may need to create a separate configuration for the launcher-only runtime dependencies (things that need to be on the app classpath, like the IntelliJ platform libs).

3. Update `requiredClassEntries` in `syncRuntimeLibs` — remove entries like `io/github/amichne/kast/intellij/KastIntelliJBackendRuntime.class` and `io/github/amichne/kast/server/AnalysisServer.class` that will now live in the plugin JAR, not the runtime libs. Or add a similar verification step for the plugin lib directory.

4. Verify that the launcher class (`HeadlessMainKt`) and the platform bootstrap classes (`com.intellij.idea.Main`, `ModernApplicationStarter`, etc.) remain on the app classpath since they are needed before the plugin system initializes.

**File: `backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessMain.kt`**
- No changes needed — the launcher and `HeadlessApplicationStarter` should continue to work. The `HeadlessApplicationStarter` is referenced in `plugin.xml` and will be loaded by the plugin class loader once the plugin system starts.
- However, verify that `HeadlessMain.kt` (the launcher) does NOT directly reference any `backend-intellij` or `org.jetbrains.kotlin` classes. It currently only references `HeadlessRuntime` and `com.intellij.idea.Main` via reflection, which is correct.

**Defensive cleanup (optional but recommended):**

In `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/IntelliJReferenceIndexEnvironment.kt`:
- Replace `KotlinFileType.INSTANCE` with `FileTypeManager.getInstance().findFileTypeByName("Kotlin") ?: return@withReadAccess emptyList()` as a defensive measure.

In `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/KastPluginBackend.kt`:
- Similarly replace `KotlinFileType.INSTANCE` references with dynamic lookup via `FileTypeManager`. Do NOT use `!!` — fail closed with an empty result or a clear error.

**Verification steps:**
1. After building, inspect the portable dist to confirm:
    - `runtime-libs/classpath.txt` does NOT contain `backend-intellij` or its transitive deps
    - `idea-home/plugins/kast-headless/lib/` contains the backend-intellij JAR and its transitive deps
2. Run `./gradlew :backend-headless:build` and `./gradlew :backend-headless:test`
3. Run the smoke test: `scripts/smoke-ubuntu-debian-bundle.sh` if applicable
4. Verify the kast.sh headless build verification still passes (it checks for `idea-home/plugins/kast-headless/lib` directory existence at line 232 of kast.sh)
