package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImportedModelResultTest {
    private val gradle = IntellijWorkspaceRefreshPort.GRADLE
    private val project =
        DataNode(ProjectKeys.PROJECT, ProjectData(gradle, "fixture", "/workspace", "/workspace"), null)

    @Test
    fun `an import without a structure failed`() {
        assertEquals(WorkspaceRefreshEffectResult.FAILED, importedModelResult(null))
    }

    @Test
    fun `an import that names the project without a module failed`() {
        assertEquals(WorkspaceRefreshEffectResult.FAILED, importedModelResult(project))
    }

    @Test
    fun `an import with its root module succeeded`() {
        project.createChild(
            ProjectKeys.MODULE,
            ModuleData(":", gradle, "JAVA_MODULE", "fixture", "/workspace", "/workspace"),
        )
        assertEquals(WorkspaceRefreshEffectResult.SUCCEEDED, importedModelResult(project))
    }
}
