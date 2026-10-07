package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
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
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame

internal class TopologyRelationCompilerFixture(
    val selector: SymbolSelector,
    val meaning: RelationMeaning,
    val compiler: SqliteTopologyRelationCompiler,
    val readCount: () -> Int,
) {
    val reads: Int
        get() = readCount()

    fun request(limit: Int, bytes: Long = 10_000, work: Long = 100): RelationRequest =
        RelationRequest.start(
            selector,
            meaning,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(limit).refined(),
                    WorkUnitLimit.parse(work).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                RelationByteLimit.parse(bytes).refined(),
            ),
        )
}

internal fun topologyRelationFixture(
    meaning: RelationMeaning,
    sourceHash: String = "a",
    generationNumber: Long = 19,
    mixedGraph: Boolean = false,
    subjectName: String = if (meaning == RelationMeaning.Callees) "Source" else "Target",
    subjectFile: String = subjectName,
): TopologyRelationCompilerFixture {
    val generation = generation(meaning, sourceHash, generationNumber, mixedGraph)
    val snapshot =
        object : PublishedTopologySnapshot {
            override val identity = generation.identity
            override val manifest = TopologySnapshotManifest.from(generation)
        }
    val content = TopologySnapshotContent.admit(snapshot, generation.files).refined()
    var reads = 0
    val compiler =
        assertInstanceOf(
                SqliteTopologyRelationCompilerOpening.Opened::class.java,
                SqliteTopologyRelationCompiler.open(snapshot) {
                    reads++
                    assertSame(snapshot, it)
                    TopologySnapshotContentRead.Loaded(content)
                },
            )
            .compiler
    val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    val subject =
        generation.symbols.single {
            it.evidence.name.value == subjectName && it.file.path.value == "src/main/kotlin/$subjectFile.kt"
        }
    return TopologyRelationCompilerFixture(
        SymbolSelector.issue(generation.identity.lease, scope, subject.evidence),
        meaning,
        compiler,
    ) {
        reads
    }
}

private fun generation(
    meaning: RelationMeaning,
    sourceHash: String,
    generationNumber: Long,
    mixedGraph: Boolean,
): CompleteTopologyGeneration {
    val workspace = workspace(generationNumber)
    val sourceFile = sourceFile(workspace, "src/main/kotlin/Source.kt", sourceHash)
    val targetFile = sourceFile(workspace, "src/main/kotlin/Target.kt", "b")
    val source = symbol(sourceFile, "Source")
    val target = symbol(targetFile, "Target")
    val kind =
        when (meaning) {
            RelationMeaning.References -> TopologyEdgeKind.REFERENCE
            RelationMeaning.Callers,
            RelationMeaning.Callees -> TopologyEdgeKind.CALL
            RelationMeaning.TypeUses -> TopologyEdgeKind.TYPE_USE
            RelationMeaning.Implementations,
            RelationMeaning.Inheritors -> TopologyEdgeKind.INHERITANCE
            RelationMeaning.Overrides -> TopologyEdgeKind.OVERRIDE
        }
    val edges = (20..26).map { TopologyEdge.fromBoundary(kind, source, target, it, it + 1).refined() }.sorted()
    val completed =
        if (mixedGraph) {
            mixedFiles(workspace, source, target, edges)
        } else {
            listOf(
                CompleteTopologyFile.admit(sourceFile, listOf(source), edges).refined(),
                CompleteTopologyFile.admit(targetFile, listOf(target), emptyList()).refined(),
            )
        }
    return CompleteTopologyGeneration.admit(workspace, completed.map { it.file }, completed).refined()
}

private fun mixedFiles(
    workspace: PublishedWorkspace,
    source: TopologySymbol,
    target: TopologySymbol,
    edges: List<TopologyEdge>,
): List<CompleteTopologyFile> {
    val duplicateFile = sourceFile(workspace, "src/main/kotlin/Duplicate.kt", "d")
    val duplicate = symbol(duplicateFile, "Source")
    assertEquals(source.evidence.compilerIdentity, duplicate.evidence.compilerIdentity)
    return listOf(
        CompleteTopologyFile.admit(
                source.file,
                listOf(source),
                (edges + TopologyEdge.fromBoundary(TopologyEdgeKind.REFERENCE, source, target, 30, 31).refined())
                    .sorted(),
            )
            .refined(),
        CompleteTopologyFile.admit(
                target.file,
                listOf(target),
                listOf(TopologyEdge.fromBoundary(TopologyEdgeKind.CALL, target, source, 40, 41).refined()),
            )
            .refined(),
        CompleteTopologyFile.admit(
                duplicateFile,
                listOf(duplicate),
                (1..19)
                    .map {
                        TopologyEdge.fromBoundary(TopologyEdgeKind.CALL, duplicate, target, it, it + 1).refined()
                    }
                    .sorted(),
            )
            .refined(),
    )
}

private fun workspace(generationNumber: Long): PublishedWorkspace {
    val sourceRoot =
        SourceRoot.admit(
                GradleSourceRootEvidence(
                    "root.main",
                    ".",
                    ":",
                    "main",
                    "src/main/kotlin",
                    SourceRootProvenance.Authored,
                )
            )
            .refined()
    val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    return PublishedWorkspace.publish(
        ReconciledWorkspace.admit(
                WorkspaceCandidate(root, WorkspaceStateIdentity.parse("state").refined()),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot),
            )
            .refined(),
        EvidenceGeneration.parse(generationNumber).refined(),
    )
}

private fun sourceFile(workspace: PublishedWorkspace, path: String, hash: String): TopologySourceFile =
    TopologySourceFile.admit(
            workspace,
            workspace.sourceRoots.single(),
            WorkspaceSourcePath.parse(path).refined(),
            WorkspaceSourceContentHash.parse(hash.repeat(64)).refined(),
        )
        .refined()

private fun symbol(file: TopologySourceFile, name: String): TopologySymbol {
    val root = file.workspace.lease.workspaceRoot
    val path = Path.of(root.value).resolve(file.path.value)
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.fromBoundary(root, path, path.toUri().toString()).refined(),
                0,
                100,
                name,
                "sample.$name",
                CompilerSymbolKind.CLASSLIKE,
                CanonicalCompilerSignature.classLike("sample.$name").refined(),
            )
            .refined()
    return TopologySymbol.admit(file, evidence).refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected fixture refinement, got $failure")
    }
