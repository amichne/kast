Move four inline Gradle task classes from module build scripts into build-logic/src/main/kotlin/ to reduce build script complexity and follow the established pattern.

## Tasks to move

From `backend-standalone/build.gradle.kts`:
1. `ExtractIdeaDistributionTask` (lines 32-91) - extracts IntelliJ IDEA distribution zip with version caching
2. `ExtractLegacyPluginClassesTask` (lines 295-361) - extracts specific plugin classes from kotlin-compiler.jar

From `backend-intellij/build.gradle.kts`:
3. `WriteBackendVersionTask` (lines 16-30) - writes backend version to a file
4. `VerifyPluginXmlPresentTask` (lines 32-85) - verifies plugin.xml content in built plugin zip

## Implementation steps

### Step 1: Move ExtractIdeaDistributionTask
Create `build-logic/src/main/kotlin/ExtractIdeaDistributionTask.kt`:
- Copy the task class from `backend-standalone/build.gradle.kts` lines 32-91
- Add necessary imports at the top
- Keep the `@CacheableTask` annotation and all input/output properties

Update `backend-standalone/build.gradle.kts`:
- Remove the task class definition (lines 32-91)
- Remove the import for `java.nio.file.AtomicMoveNotSupportedException`, `java.nio.file.Files`, `java.nio.file.StandardCopyOption`, `java.util.zip.ZipFile` if no longer needed
- Add import for the moved task class from build-logic
- Keep the task registration and configuration (lines 93-97) unchanged

### Step 2: Move ExtractLegacyPluginClassesTask
Create `build-logic/src/main/kotlin/ExtractLegacyPluginClassesTask.kt`:
- Copy the task class from `backend-standalone/build.gradle.kts` lines 295-361
- Add necessary imports
- Keep the `@CacheableTask` annotation

Update `backend-standalone/build.gradle.kts`:
- Remove the task class definition (lines 295-361)
- Remove the import for `java.util.zip.ZipFile` if no longer needed
- Add import for the moved task class
- Keep the task registration and configuration (lines 363-369) unchanged

### Step 3: Move WriteBackendVersionTask
Create `build-logic/src/main/kotlin/WriteBackendVersionTask.kt`:
- Copy the task class from `backend-intellij/build.gradle.kts` lines 16-30
- Add necessary imports

Update `backend-intellij/build.gradle.kts`:
- Remove the task class definition (lines 16-30)
- Add import for the moved task class
- Keep the task registration and configuration (lines 149-152) unchanged

### Step 4: Move VerifyPluginXmlPresentTask
Create `build-logic/src/main/kotlin/VerifyPluginXmlPresentTask.kt`:
- Copy the task class from `backend-intellij/build.gradle.kts` lines 32-85
- Add necessary imports

Update `backend-intellij/build.gradle.kts`:
- Remove the task class definition (lines 32-85)
- Remove imports for `java.util.zip.ZipFile`, `java.util.jar.JarInputStream`, and Gradle task annotation imports if no longer needed
- Add import for the moved task class
- Keep the task registration and configuration (lines 173-178) unchanged

## Verification
After each step, run `./gradlew :backend-standalone:build` or `./gradlew :backend-intellij:build` to ensure the build still works correctly. The tasks should behave identically since only their location changes, not their logic.
