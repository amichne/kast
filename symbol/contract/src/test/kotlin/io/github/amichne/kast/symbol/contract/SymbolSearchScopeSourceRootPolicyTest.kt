package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path

class SymbolSearchScopeSourceRootPolicyTest {
    @Test
    fun `read policy is generation-bound and independent from model ownership`() {
        val root = workspaceRoot()
        val model = compiledModel(root)
        val project = model.sourceRoots.single().project
        val lease = SemanticReadLease(root, generation(11))

        val authored = SymbolSearchScopeRequest(
            lease = lease,
            owner = SymbolSearchOwner.GradleProject(project),
            readableSources = SymbolReadableSources.AUTHORED_ONLY,
        )
        val includingGenerated = authored.copy(
            readableSources = SymbolReadableSources.AUTHORED_AND_GENERATED,
        )

        assertEquals(lease, authored.lease)
        assertEquals(SymbolSearchOwner.GradleProject(project), authored.owner)
        assertEquals(SymbolReadableSources.AUTHORED_ONLY, authored.readableSources)
        assertEquals(SymbolReadableSources.AUTHORED_AND_GENERATED, includingGenerated.readableSources)
    }

    private fun compiledModel(root: CanonicalWorkspaceRoot): WorkspaceSearchScopeModel {
        val compilation = WorkspaceSearchScopeModel.compile(
            root,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = "app.main",
                    linkedBuildRoot = Path.of("/workspace"),
                    gradleProjectPath = ":app",
                    sourceSetName = "main",
                    sourceRoot = Path.of("/workspace/app/src/main/kotlin"),
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                ),
            ),
        )
        return assertInstanceOf<WorkspaceSearchScopeModelCompilation.Compiled>(compilation).model
    }

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun generation(value: Long): EvidenceGeneration = EvidenceGeneration.parse(value).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
