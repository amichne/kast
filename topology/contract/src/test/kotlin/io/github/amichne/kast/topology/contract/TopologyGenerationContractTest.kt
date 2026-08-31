package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

    @Test
    fun `extraction request rejects same-path foreign evidence`() {
        val workspace = workspace()
        val root = sourceRoot()
        val admitted = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'a')
        val foreign = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'b')
        val candidates = TopologyCandidateSet.admit(workspace, listOf(admitted)).refined()

        assertEquals(
            Refinement.Rejected(TopologyExtractionRequestFailure.FILE_NOT_ADMITTED),
            candidates.extractionRequest(foreign),
        )
    }

    @Test
    fun `complete generation rejects same-path foreign evidence`() {
        val workspace = workspace()
        val root = sourceRoot()
        val candidate = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'a')
        val foreign = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'b')
        val foreignComplete = CompleteTopologyFile.admit(
            foreign,
            emptyList(),
            emptyList(),
        ).refined()

        val rejected = CompleteTopologyGeneration.admit(
            workspace,
            listOf(candidate),
            listOf(foreignComplete),
        )

        val failure = when (rejected) {
            is Refinement.Refined -> error("foreign candidate evidence unexpectedly admitted")
            is Refinement.Rejected -> rejected.failure
        }
        val mismatch = failure.candidateEvidenceMismatches.single()
        assertEquals(candidate, mismatch.candidate)
        assertEquals(foreign, mismatch.completed)
    }

    @Test
    fun `exact locations distinguish symbols that share canonical compiler identity`() {
        val workspace = workspace()
        val root = sourceRoot()
        val first = sourceFile(workspace, root, "alpha/src/main/kotlin/First.kt", 'a')
        val second = sourceFile(workspace, root, "alpha/src/main/kotlin/Second.kt", 'b')
        val firstSymbol = symbol(first, "shared", 0)
        val secondSymbol = symbol(second, "shared", 20)
        val firstComplete = CompleteTopologyFile.admit(
            first,
            listOf(firstSymbol),
            emptyList(),
        ).refined()
        val secondComplete = CompleteTopologyFile.admit(
            second,
            listOf(secondSymbol),
            emptyList(),
        ).refined()

        val generation = CompleteTopologyGeneration.admit(
            workspace,
            listOf(first, second),
            listOf(firstComplete, secondComplete),
        ).refined()

        assertEquals(2, generation.symbols.size)
        assertEquals(
            setOf("alpha/src/main/kotlin/First.kt", "alpha/src/main/kotlin/Second.kt"),
            generation.symbols.mapTo(linkedSetOf()) { it.file.path.value },
        )
        assertEquals(1, generation.symbols.map { it.evidence.compilerIdentity }.distinct().size)
    }

    @Test
    fun `edge endpoint must equal the indexed symbol at its exact identity`() {
        val workspace = workspace()
        val root = sourceRoot()
        val file = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'a')
        val indexed = symbol(file, "alpha", 0)
        val contradictory = symbol(file, "bravo", 0)
        val edge = TopologyEdge.fromBoundary(
            TopologyEdgeKind.REFERENCE,
            contradictory,
            indexed,
            0,
            1,
        ).refined()
        val complete = CompleteTopologyFile.admit(file, listOf(indexed), listOf(edge)).refined()

        val rejected = CompleteTopologyGeneration.admit(
            workspace,
            listOf(file),
            listOf(complete),
        )

        val failure = when (rejected) {
            is Refinement.Refined -> error("contradictory edge endpoint unexpectedly admitted")
            is Refinement.Rejected -> rejected.failure
        }
        assertEquals(setOf(contradictory), failure.mismatchedEdgeEndpoints)
    }

    @Test
    fun `complete file rejects contradictory symbols at one node identity`() {
        val workspace = workspace()
        val root = sourceRoot()
        val file = sourceFile(workspace, root, "alpha/src/main/kotlin/Alpha.kt", 'a')
        val alpha = symbol(file, "alpha", 0)
        val bravo = symbol(file, "bravo", 0)

        val rejected = CompleteTopologyFile.admit(
            file,
            listOf(alpha, bravo),
            emptyList(),
        )

        val failure = assertInstanceOf(
            Refinement.Rejected::class.java,
            rejected,
        ).failure
        assertEquals(setOf(CompleteTopologyFileFailure.DUPLICATE_SYMBOL_IDENTITY), failure)
    }

    private fun symbol(
        file: TopologySourceFile,
        name: String,
        start: Int,
    ): TopologySymbol {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
            file.workspace.lease.workspaceRoot,
            absolute,
            absolute.toUri().toString(),
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            fileIdentity,
            start,
            start + name.length,
            name,
            "sample.shared",
            CompilerSymbolKind.FUNCTION,
            CanonicalCompilerSignature.function(
                "sample.shared",
                null,
                emptyList(),
                emptyList(),
                0,
            ).refined(),
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
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
