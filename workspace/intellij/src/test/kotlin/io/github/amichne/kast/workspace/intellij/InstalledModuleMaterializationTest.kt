package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ProjectData
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class InstalledModuleMaterializationTest {
    @Test
    fun `external projects lookup exception is a closed materialization failure`(
        @TempDir workspace: Path,
    ) {
        val imports = AtomicInteger()

        val materialization = materializeImportedModules(
            InstalledModuleAvailability.UNAVAILABLE,
            workspace.toRealPath(),
            InstalledExternalProjectsReader {
                throw IllegalStateException("getExternalProjectsData failed")
            },
            InstalledExternalProjectImporter {
                imports.incrementAndGet()
                InstalledExternalProjectImport.IMPORTED
            },
        )

        assertEquals(InstalledModuleMaterialization.FAILED, materialization)
        assertEquals(0, imports.get())
    }

    @Test
    fun `nul external project path is a closed materialization failure`(
        @TempDir workspace: Path,
    ) {
        val imports = AtomicInteger()
        val structure = projectStructure(workspace.toRealPath())

        val materialization = materializeImportedModules(
            InstalledModuleAvailability.UNAVAILABLE,
            workspace.toRealPath(),
            InstalledExternalProjectsReader {
                listOf(FixtureExternalProjectInfo(structure, "\u0000"))
            },
            InstalledExternalProjectImporter {
                imports.incrementAndGet()
                InstalledExternalProjectImport.IMPORTED
            },
        )

        assertEquals(InstalledModuleMaterialization.FAILED, materialization)
        assertEquals(0, imports.get())
    }

    @Test
    fun `exact normalized external project imports its structure`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val structure = projectStructure(root)

        val materialization = materializeImportedModules(
            InstalledModuleAvailability.UNAVAILABLE,
            root,
            InstalledExternalProjectsReader {
                listOf(FixtureExternalProjectInfo(structure, root.resolve(".").toString()))
            },
            InstalledExternalProjectImporter { observed ->
                assertEquals(structure, observed)
                InstalledExternalProjectImport.IMPORTED
            },
        )

        assertEquals(InstalledModuleMaterialization.IMPORTED, materialization)
    }

    private fun projectStructure(workspace: Path): DataNode<ProjectData> {
        val data = ProjectData(
            GradleConstants.SYSTEM_ID,
            "fixture",
            workspace.toString(),
            workspace.toString(),
        )
        return DataNode(ProjectKeys.PROJECT, data, null)
    }

    private class FixtureExternalProjectInfo(
        private val structure: DataNode<ProjectData>,
        private val path: String,
    ) : ExternalProjectInfo {
        override fun getProjectSystemId(): ProjectSystemId = GradleConstants.SYSTEM_ID

        override fun getExternalProjectPath(): String = path

        override fun getExternalProjectStructure(): DataNode<ProjectData> = structure

        override fun getLastSuccessfulImportTimestamp(): Long = 1L

        override fun getLastImportTimestamp(): Long = 1L

        override fun getBuildNumber(): String = "test"

        override fun copy(): ExternalProjectInfo = this
    }
}
