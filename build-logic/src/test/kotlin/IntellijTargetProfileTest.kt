package support.tasks.idea

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.io.TempDir
import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Path

class IntellijTargetProfileTest {
    @TempDir lateinit var directory: Path
    private val json = Json { encodeDefaults = true }
    private val profiles = listOf(Target("262", "262.1.2", "262.1.2-IJ"), Target("263", "263.3.4", "263.3.4-IJ"))

    @Test
    fun `default explicit and supplied SDK selection retain one target tuple`() {
        val encoded = json.encodeToString(Configuration(targets = profiles))
        val default = IntellijTargetProfiles.select(encoded, null) as IntellijTargetSelection.Selected
        assertEquals("262.1.2", default.profile.ideBuild)
        val supplied = IntellijTargetProfiles.select(encoded, null, listOf("263.3.4")) as IntellijTargetSelection.Selected
        assertEquals("263", supplied.profile.intellijReleaseLine)
        assertEquals("263.3.4-IJ", supplied.profile.kotlinPluginBuild)
        val explicit = IntellijTargetProfiles.select(encoded, "263", listOf("263.3.4", "263.3.4")) as IntellijTargetSelection.Selected
        assertEquals("263.3.4", explicit.profile.ideBuild)
    }

    @Test
    fun `unknown targets conflicting override and mixed SDK homes reject before compilation`() {
        val encoded = json.encodeToString(Configuration(targets = profiles))
        assertEquals(IntellijTargetSelection.Rejected(IntellijTargetSelectionFailure.TARGET_UNAVAILABLE),
            IntellijTargetProfiles.select(encoded, "264"))
        assertEquals(IntellijTargetSelection.Rejected(IntellijTargetSelectionFailure.SDK_BUILD_MISMATCH),
            IntellijTargetProfiles.select(encoded, "262", listOf("263.3.4")))
        assertEquals(IntellijTargetSelection.Rejected(IntellijTargetSelectionFailure.SDK_BUILD_MISMATCH),
            IntellijTargetProfiles.select(encoded, null, listOf("262.1.2", "263.3.4")))
    }

    @Test
    fun `duplicate unsupported schema defaults and cross-family pins reject`() {
        fun rejected(value: Configuration, failure: IntellijTargetSelectionFailure) = assertEquals(
            IntellijTargetSelection.Rejected(failure), IntellijTargetProfiles.select(json.encodeToString(value), null))
        rejected(Configuration(targets = listOf(profiles[0], profiles[0])), IntellijTargetSelectionFailure.DUPLICATE_TARGET)
        rejected(Configuration(schemaVersion = 2), IntellijTargetSelectionFailure.UNSUPPORTED_SCHEMA)
        rejected(Configuration(defaultTarget = "264"), IntellijTargetSelectionFailure.DEFAULT_TARGET_UNAVAILABLE)
        rejected(Configuration(targets = listOf(Target("262", "262.1.2", "263.3.4-IJ"))),
            IntellijTargetSelectionFailure.TARGET_IDENTITY_MISMATCH)
    }

    @Test
    fun `a supplied SDK is a read source and never the extraction producer output`() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val supplied = directory.resolve("vendor-sdk").toFile()
        val profile = (IntellijTargetProfiles.select(json.encodeToString(Configuration(targets = profiles)), "263")
            as IntellijTargetSelection.Selected).profile
        val target = SelectedIntellijTarget(profile, supplied, true)

        target.configureBuildDirectory(project)

        assertEquals(supplied, target.sdkHome(project).get().asFile)
        assertEquals(supplied, target.nativeFixtureHome(project).get().asFile)
        assertNotEquals(supplied, target.extractedSdkHome(project).get().asFile)
        assertEquals(project.gradle.gradleUserHomeDir.resolve("kast/intellij-targets/263.3.4"),
            target.extractedSdkHome(project).get().asFile)
        assertEquals(project.projectDir.resolve("build/ide-targets/263"), project.layout.buildDirectory.get().asFile)
    }

    @Test
    fun `targeted native fixture consumers retain the line-specific producer address`() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val profile = (IntellijTargetProfiles.select(json.encodeToString(Configuration(targets = profiles)), "263")
            as IntellijTargetSelection.Selected).profile

        assertEquals(project.projectDir.resolve("relation/intellij/build/ide-targets/263/native-test/idea"),
            SelectedIntellijTarget(profile, null, true).nativeFixtureHome(project).get().asFile)
        assertEquals(project.projectDir.resolve("relation/intellij/build/native-test/idea"),
            SelectedIntellijTarget(profile, null, false).nativeFixtureHome(project).get().asFile)
    }
}

@Serializable
private data class Configuration(val schemaVersion: Int = 1, val defaultTarget: String = "262", val targets: List<Target> = listOf(Target()))

@Serializable
private data class Target(val intellijReleaseLine: String = "262", val ideBuild: String = "262.1.2", val kotlinPluginBuild: String = "262.1.2-IJ")
