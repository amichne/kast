package io.github.amichne.kast.indexer.gradle.bootstrap

import com.intellij.util.execution.ParametersListUtil
import io.github.amichne.kast.indexer.project.WorkspaceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GradleProjectStorageIsolationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Gradle import uses the same Kast owned project cache for link and refresh`() {
        val cache = tempDir.resolve("Kast cache with spaces").toAbsolutePath().normalize()

        assertEquals(
            listOf("--project-cache-dir", cache.toString()),
            GradleProjectImportBridge.projectCacheArguments(cache),
        )
        assertEquals(
            listOf("--project-cache-dir", cache.toString()),
            ParametersListUtil.parse(GradleProjectImportBridge.projectCacheArgumentLine(cache)),
        )
        val vmOptions = GradleProjectImportBridge.withIndexerGradleVmOptions("-Xmx2g", cache)
        assertEquals(
            listOf("-Xmx2g", "-Didea.gradle.download.sources.force=false", "-Dorg.gradle.projectcachedir=$cache"),
            ParametersListUtil.parse(vmOptions),
        )
    }

    @Test
    fun `Gradle bootstrap carries one project cache through configuration and initial link`() {
        val workspace = tempDir.resolve("workspace")
        val cache = tempDir.resolve("gradle-project-cache")
        val configuredCaches = mutableListOf<Path>()
        val linkedCaches = mutableListOf<Path>()
        var linked = false
        val snapshots = ArrayDeque(listOf(modelReadiness(), modelReadiness(), modelReadiness(listOf(":app"))))
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { _, observed -> configuredCaches.add(observed) },
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = { snapshots.removeFirst() },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> linked },
            linkAndImportGradleProject = { _, _, observed ->
                linkedCaches.add(observed)
                linked = true
            },
        )

        bootstrap.bootstrap(projectStub(), workspace, WorkspaceKind.GRADLE, cache)

        assertEquals(listOf(cache), configuredCaches)
        assertEquals(listOf(cache), linkedCaches)
    }
}
