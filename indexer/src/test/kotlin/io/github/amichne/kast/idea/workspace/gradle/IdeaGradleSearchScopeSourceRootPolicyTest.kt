package io.github.amichne.kast.idea.workspace.gradle

import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path

class IdeaGradleSearchScopeSourceRootPolicyTest {
    @Test
    fun `bridge maps exact Gradle model provenance without path inference`() {
        val compilation = model(
            association(
                ":app",
                sourceRoot(
                    "/workspace/build/generated/authored-by-model",
                    IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Authored(
                        listOf(IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.SOURCE),
                    ),
                ),
                sourceRoot(
                    "/workspace/custom/generated-outside-output",
                    IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Generated(
                        listOf(IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.SOURCE_GENERATED),
                    ),
                ),
            ),
        ).toWorkspaceSearchScopeModel(workspaceRoot())

        val compiled = assertInstanceOf<WorkspaceSearchScopeModelCompilation.Compiled>(compilation)
        assertEquals(
            setOf(
                WorkspaceSourceRootProvenance.AUTHORED,
                WorkspaceSourceRootProvenance.GENERATED,
            ),
            compiled.model.sourceRoots.mapTo(mutableSetOf()) { it.provenance },
        )
    }

    @Test
    fun `unknown and ambiguous bridge ownership reject before scope compilation`() {
        val unknown = model(
            association(
                ":app",
                sourceRoot(
                    "/workspace/app/src/main/kotlin",
                    IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Unknown(
                        "missing classification",
                        emptyList(),
                    ),
                ),
            ),
        ).toWorkspaceSearchScopeModel(workspaceRoot())
        assertTrue(
            WorkspaceSearchScopeModelFailure.UNKNOWN_SOURCE_ROOT_PROVENANCE in
                assertInstanceOf<WorkspaceSearchScopeModelCompilation.Rejected>(unknown).failures,
        )

        val sameRoot = sourceRoot(
            "/workspace/shared/src",
            IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Authored(
                listOf(IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.SOURCE),
            ),
        )
        val ambiguous = model(
            association(":app", sameRoot, moduleName = "app"),
            association(":other", sameRoot, moduleName = "other"),
        ).toWorkspaceSearchScopeModel(workspaceRoot())
        assertTrue(
            WorkspaceSearchScopeModelFailure.AMBIGUOUS_SOURCE_ROOT_OWNER in
                assertInstanceOf<WorkspaceSearchScopeModelCompilation.Rejected>(ambiguous).failures,
        )
    }

    private fun model(
        vararg associations: IdeaGradleProjectLoadBridge.GradleModuleAssociation,
    ): IdeaGradleProjectLoadBridge.GradleWorkspaceModel = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
        listOf(Path.of("/workspace")),
        true,
        emptyList(),
        emptyList(),
        emptyList(),
        associations.asList(),
    )

    private fun association(
        projectPath: String,
        vararg roots: IdeaGradleProjectLoadBridge.GradleSourceRoot,
        moduleName: String = "app",
    ): IdeaGradleProjectLoadBridge.GradleModuleAssociation =
        IdeaGradleProjectLoadBridge.GradleModuleAssociation(
            moduleName,
            Path.of("/workspace"),
            Path.of("/workspace/$moduleName"),
            projectPath,
            false,
            false,
            listOf(
                IdeaGradleProjectLoadBridge.GradleSourceSetAssociation("main", roots.asList()),
            ),
        )

    private fun sourceRoot(
        path: String,
        provenance: IdeaGradleProjectLoadBridge.GradleSourceRootProvenance,
    ): IdeaGradleProjectLoadBridge.GradleSourceRoot =
        IdeaGradleProjectLoadBridge.GradleSourceRoot(Path.of(path), provenance)

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        when (val result = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace"))) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error(result.failure)
        }
}
