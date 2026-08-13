package io.github.amichne.kast.workspace.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path

class WorkspaceSearchScopeSourceRootPolicyTest {
    @Test
    fun `model provenance wins over source-root path names`() {
        val compilation = WorkspaceSearchScopeModel.compile(
            workspaceRoot(),
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                boundary(
                    sourceRoot = "/workspace/build/generated/authored-by-model",
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                ),
                boundary(
                    sourceRoot = "/workspace/custom/generated-outside-output",
                    provenance = WorkspaceSourceRootProvenance.GENERATED,
                ),
            ),
        )

        val model = assertInstanceOf<WorkspaceSearchScopeModelCompilation.Compiled>(compilation).model
        assertEquals(
            mapOf(
                "/workspace/build/generated/authored-by-model" to WorkspaceSourceRootProvenance.AUTHORED,
                "/workspace/custom/generated-outside-output" to WorkspaceSourceRootProvenance.GENERATED,
            ),
            model.sourceRoots.associate { it.sourceRoot.value to it.provenance },
        )
    }

    @Test
    fun `incomplete unknown and ambiguous model ownership fail closed`() {
        val incomplete = WorkspaceSearchScopeModel.compile(
            workspaceRoot(),
            ImportedWorkspaceModelState.INCOMPLETE,
            listOf(boundary()),
        )
        assertEquals(
            setOf(WorkspaceSearchScopeModelFailure.MODEL_INCOMPLETE),
            assertInstanceOf<WorkspaceSearchScopeModelCompilation.Rejected>(incomplete).failures,
        )

        val unknown = WorkspaceSearchScopeModel.compile(
            workspaceRoot(),
            ImportedWorkspaceModelState.COMPLETE,
            listOf(boundary(provenance = WorkspaceSourceRootProvenance.UNKNOWN)),
        )
        assertTrue(
            WorkspaceSearchScopeModelFailure.UNKNOWN_SOURCE_ROOT_PROVENANCE in
                assertInstanceOf<WorkspaceSearchScopeModelCompilation.Rejected>(unknown).failures,
        )

        val ambiguous = WorkspaceSearchScopeModel.compile(
            workspaceRoot(),
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                boundary(gradleProjectPath = ":app"),
                boundary(ideaModuleName = "other", gradleProjectPath = ":other"),
            ),
        )
        assertTrue(
            WorkspaceSearchScopeModelFailure.AMBIGUOUS_SOURCE_ROOT_OWNER in
                assertInstanceOf<WorkspaceSearchScopeModelCompilation.Rejected>(ambiguous).failures,
        )
    }

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        when (val result = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace"))) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> result.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error(result.failure)
        }

    private fun boundary(
        ideaModuleName: String = "app.main",
        gradleProjectPath: String = ":app",
        sourceRoot: String = "/workspace/app/src/main/kotlin",
        provenance: WorkspaceSourceRootProvenance = WorkspaceSourceRootProvenance.AUTHORED,
    ): WorkspaceSourceRootBoundary = WorkspaceSourceRootBoundary(
        ideaModuleName = ideaModuleName,
        linkedBuildRoot = Path.of("/workspace"),
        gradleProjectPath = gradleProjectPath,
        sourceSetName = "main",
        sourceRoot = Path.of(sourceRoot),
        provenance = provenance,
    )
}
