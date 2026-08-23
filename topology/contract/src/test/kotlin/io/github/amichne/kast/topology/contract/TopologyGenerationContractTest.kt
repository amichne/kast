package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class TopologyGenerationContractTest {
    @Test
    fun `exact complete per-file coverage produces publication authority`() {
        val workspace = workspace()
        val root = sourceRoot()
        val first = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'a')
        val second = sourceFile(workspace, root, "alpha/src/main/kotlin/Beta.kt", 'b')
        val firstComplete = CompleteTopologyFile.admit(first, emptyList(), emptyList()).refined()
        val secondComplete = CompleteTopologyFile.admit(second, emptyList(), emptyList()).refined()

        val generation = CompleteTopologyGeneration.admit(
            workspace,
            listOf(second, first),
            listOf(firstComplete, secondComplete),
        ).refined()
        val repeated = CompleteTopologyGeneration.admit(
            workspace,
            listOf(first, second),
            listOf(secondComplete, firstComplete),
        ).refined()

        assertEquals(listOf(first, second), generation.files.map(CompleteTopologyFile::file))
        assertEquals(workspace.readLease, generation.identity.lease)
        assertEquals(workspace.sourceState, generation.identity.sourceState)
        assertEquals(generation.digest, repeated.digest)
        assertEquals(generation.canonicalProjection(), repeated.canonicalProjection())
    }

    @Test
    fun `missing terminal file cannot acquire publication authority`() {
        val workspace = workspace()
        val root = sourceRoot()
        val first = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'a')
        val second = sourceFile(workspace, root, "alpha/src/main/kotlin/Beta.kt", 'b')
        val firstComplete = CompleteTopologyFile.admit(first, emptyList(), emptyList()).refined()

        val rejected = CompleteTopologyGeneration.admit(
            workspace,
            listOf(first, second),
            listOf(firstComplete),
        )

        val failure = when (rejected) {
            is Refinement.Refined -> error("missing coverage unexpectedly admitted")
            is Refinement.Rejected -> rejected.failure
        }
        assertEquals(setOf(second.path), failure.missing)
        assertEquals(emptySet<WorkspaceSourcePath>(), failure.unexpected)
    }

    private fun sourceFile(
        workspace: PublishedWorkspace,
        sourceRoot: SourceRoot,
        path: String,
        hashDigit: Char,
    ): TopologySourceFile = TopologySourceFile.admit(
        workspace,
        sourceRoot,
        WorkspaceSourcePath.parse(path).refined(),
        WorkspaceSourceContentHash.parse(hashDigit.toString().repeat(64)).refined(),
    ).refined()

    private fun workspace(): PublishedWorkspace {
        val candidate = WorkspaceCandidate(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            WorkspaceStateIdentity.parse("workspace-state").refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot()),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(7).refined())
    }

    private fun sourceRoot(): SourceRoot = SourceRoot.admit(
        GradleSourceRootEvidence(
            ideaModuleName = "alpha.main",
            workspaceRelativeBuildRoot = ".",
            gradleProjectPath = ":alpha",
            sourceSetName = "main",
            workspaceRelativeSourceRoot = "alpha/src/main/kotlin",
            provenance = SourceRootProvenance.Authored,
        ),
    ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
