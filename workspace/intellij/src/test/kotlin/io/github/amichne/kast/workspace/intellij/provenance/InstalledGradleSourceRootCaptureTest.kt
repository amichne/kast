package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GRADLE_SOURCE_ROOT_PRODUCER_IMPORT_KEY
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerEvidence
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerIdentity
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerImport
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerRole
import io.github.amichne.kast.workspace.intellij.provenance.InstalledGradleSourceRootCapture
import io.github.amichne.kast.workspace.intellij.provenance.InstalledGradleSourceRootCaptureFailure
import io.github.amichne.kast.workspace.intellij.provenance.sourceRootBoundaries
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.gradlePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstalledGradleSourceRootCaptureTest {
    @TempDir
    lateinit var workspaceDirectory: Path

    @Test
    fun `malformed linked build path is a closed source-root rejection`() {
        val workspace = workspaceDirectory.toAbsolutePath().normalize().toString()
        val nodes = fixtureGradleNodes(workspace, "\u0000")
        val info = FixtureExternalProjectInfo(nodes.project, workspace)

        val capture = info.sourceRootBoundaries()

        assertEquals(
            InstalledGradleSourceRootCaptureFailure.INVALID_LINKED_BUILD_ROOT,
            assertInstanceOf<InstalledGradleSourceRootCapture.Rejected>(capture).failure,
        )
    }

    @Test
    fun `generated producer evidence reaches the compiled workspace scope`() {
        val workspacePath = workspaceDirectory.toRealPath()
        val workspace = workspacePath.toString()
        val generatedRoot = workspacePath.resolve("src/producer-owned")
        Files.createDirectories(generatedRoot)
        val nodes = fixtureGradleNodes(workspace, workspace)
        val contentRoot = ContentRootData(GradleConstants.SYSTEM_ID, workspace).apply {
            storePath(ExternalSystemSourceType.SOURCE, generatedRoot.toString())
        }
        nodes.sourceSet.createChild(ProjectKeys.CONTENT_ROOT, contentRoot)
        nodes.module.createChild(
            GRADLE_SOURCE_ROOT_PRODUCER_IMPORT_KEY,
            GradleSourceRootProducerImport.Captured(
                listOf(
                    GradleSourceRootProducerEvidence(
                        identity = GradleSourceRootProducerIdentity(
                            projectDirectory = workspacePath.toFile(),
                            projectPath = ":",
                            sourceSetName = "main",
                            sourceRoot = generatedRoot.toFile(),
                            role = GradleSourceRootProducerRole.CODE,
                        ),
                        provenance = GradleSourceRootProducerProvenance.GENERATED,
                    ),
                ),
            ),
        )
        val capture = assertInstanceOf<InstalledGradleSourceRootCapture.Captured>(
            FixtureExternalProjectInfo(nodes.project, workspace).sourceRootBoundaries(),
        )
        val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(workspacePath)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure)
        }

        val scope = WorkspaceSearchScopeModel.compile(
            root,
            ImportedWorkspaceModelState.COMPLETE,
            capture.boundaries,
        )

        assertEquals(
            WorkspaceSourceRootProvenance.GENERATED,
            assertInstanceOf<WorkspaceSearchScopeModelCompilation.Compiled>(scope)
                .model.sourceRoots.single().provenance,
        )
    }

    @Test
    fun `subproject producer evidence matches installed project ownership`() {
        val workspacePath = workspaceDirectory.toRealPath()
        val projectPath = workspacePath.resolve("library")
        val sourceRoot = projectPath.resolve("src/main/java")
        Files.createDirectories(sourceRoot)
        val nodes = fixtureGradleNodes(
            workspace = workspacePath.toString(),
            linkedBuildRoot = projectPath.toString(),
            gradleProjectPath = ":library",
        )
        val contentRoot = ContentRootData(GradleConstants.SYSTEM_ID, projectPath.toString()).apply {
            storePath(ExternalSystemSourceType.SOURCE, sourceRoot.toString())
        }
        nodes.sourceSet.createChild(ProjectKeys.CONTENT_ROOT, contentRoot)
        nodes.module.createChild(
            GRADLE_SOURCE_ROOT_PRODUCER_IMPORT_KEY,
            GradleSourceRootProducerImport.Captured(
                listOf(
                    GradleSourceRootProducerEvidence(
                        identity = GradleSourceRootProducerIdentity(
                            projectDirectory = projectPath.toFile(),
                            projectPath = ":library",
                            sourceSetName = "main",
                            sourceRoot = sourceRoot.toFile(),
                            role = GradleSourceRootProducerRole.CODE,
                        ),
                        provenance = GradleSourceRootProducerProvenance.AUTHORED,
                    ),
                ),
            ),
        )

        val capture = assertInstanceOf<InstalledGradleSourceRootCapture.Captured>(
            FixtureExternalProjectInfo(nodes.project, workspacePath.toString())
                .sourceRootBoundaries(),
        )

        assertEquals(
            WorkspaceSourceRootProvenance.AUTHORED,
            capture.boundaries.single().provenance,
        )
    }

    private fun fixtureGradleNodes(
        workspace: String,
        linkedBuildRoot: String,
        gradleProjectPath: String = ":",
    ): FixtureGradleNodes {
        val sourceSetPath = if (gradleProjectPath == ":") ":main" else "$gradleProjectPath:main"
        val projectData = ProjectData(
            GradleConstants.SYSTEM_ID,
            "fixture",
            workspace,
            workspace,
        )
        val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
        val moduleData = ModuleData(
            gradleProjectPath,
            GradleConstants.SYSTEM_ID,
            "fixture-module-type",
            "fixture",
            workspace,
            workspace,
        ).apply {
            gradlePath = gradleProjectPath
        }
        val moduleNode = projectNode.createChild(ProjectKeys.MODULE, moduleData)
        val sourceSetNode = moduleNode.createChild(
            GradleSourceSetData.KEY,
            GradleSourceSetData(
                sourceSetPath,
                "fixture-source-set-type",
                sourceSetPath,
                "fixture.main",
                workspace,
                linkedBuildRoot,
            ),
        )
        return FixtureGradleNodes(projectNode, moduleNode, sourceSetNode)
    }

    private data class FixtureGradleNodes(
        val project: DataNode<ProjectData>,
        val module: DataNode<ModuleData>,
        val sourceSet: DataNode<GradleSourceSetData>,
    )

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
