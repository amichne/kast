package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverageFailure
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure published-content adapter behavior; this fixture asserts no physical publication or native compiler effects. */
class SqliteTopologyRelationCompilerTest {
    @Test
    fun `published pages one and five exhaust each meaning with the exact retained inventory`() = runTest {
        for (meaning in RelationMeaning.all) {
            for (limit in listOf(1, 5)) assertExhaustion(meaning, limit)
        }
    }

    private suspend fun assertExhaustion(meaning: RelationMeaning, limit: Int) {
        val fixture = fixture(meaning)
        val initial = fixture.request(limit)
        var request = initial
        var previous: RelationProviderState? = null
        val observed = mutableListOf<RelationFact>()
        var pages = 0
        while (pages < 8) {
            val result = fixture.compiler.read(request)
            val batch =
                when (result) {
                    is RelationCompilation.Complete -> result.batch
                    is RelationCompilation.Qualified -> result.batch
                    is RelationCompilation.Rejected -> error("Unexpected rejection: ${result.reason}")
                }
            assertTrue(batch.facts.size <= limit)
            batch.facts.forEach {
                assertSame(request.subject, it.subject)
                assertEquals(meaning, it.meaning)
                assertEquals(initial.subject.lease.identity, it.authority)
                assertEquals(RelationProvenance.K2_AUTHORED_SOURCE, it.provenance)
            }
            observed += batch.facts
            pages++
            if (result is RelationCompilation.Complete) break
            val continuation = assertProgress(result as RelationCompilation.Qualified, observed.size, previous)
            previous = continuation.providerState
            request = RelationRequest.resume(fixture.selector, meaning, initial.budget, continuation).refined()
        }
        assertEquals((7 + limit - 1) / limit, pages)
        assertEquals((20..26).toList(), observed.map { it.occurrence.range.startInclusive })
        assertEquals((21..27).toList(), observed.map { it.occurrence.range.endExclusive })
        assertEquals(List(7) { "Source" }, observed.map { it.source.name.value })
        assertEquals(List(7) { "Target" }, observed.map { it.target.name.value })
        assertEquals(7, observed.map { it.canonicalProjection() }.distinct().size)
        assertEquals(1, fixture.reads)
    }

    private fun assertProgress(
        result: RelationCompilation.Qualified,
        observed: Int,
        previous: RelationProviderState?,
    ): RelationContinuation {
        val coverage = assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, result.coverage)
        assertEquals(setOf(RelationLimitation.RESULT_LIMIT_REACHED), coverage.limitations)
        val continuation = coverage.continuation
        val retained = continuation.providerState
        assertEquals(RelationProviderKind.PUBLISHED_TOPOLOGY_V1, retained.provider)
        assertEquals(observed.toLong(), retained.consumedLocatorCount.value)
        assertEquals(observed + 1L, continuation.nextProviderCursor.nextPosition.value)
        assertEquals(7 - observed, retained.prepared.size)
        val next = assertInstanceOf(RelationProviderLocator.PublishedFact::class.java, retained.prepared.first())
        assertEquals(20 + observed, next.range.startInclusive)
        previous?.let {
            assertEquals(Refinement.Refined(retained), retained.advanceFrom(it))
            assertNotEquals(it.providerCursor.consumedPrefixDigest, retained.providerCursor.consumedPrefixDigest)
        }
        return continuation
    }

    @Test
    fun `same generation with a different published snapshot cannot consume retained facts`() = runTest {
        val original = fixture(RelationMeaning.Callees)
        val changed = fixture(RelationMeaning.Callees, sourceHash = "c")
        val initial = original.request(1)
        val first = assertInstanceOf(RelationCompilation.Qualified::class.java, original.compiler.read(initial))
        val continuation =
            assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, first.coverage).continuation
        val resumed = RelationRequest.resume(original.selector, initial.meaning, initial.budget, continuation).refined()
        assertEquals(original.selector.lease, changed.selector.lease)
        val retained = continuation.providerState
        val remaining = retained.prepared.toList()

        assertEquals(
            RelationCompilation.Rejected(RelationCompilerRejection.CONTINUATION_CURSOR_MOVED),
            changed.compiler.read(resumed),
        )
        assertEquals(remaining, retained.prepared)
        assertEquals(1L, retained.consumedLocatorCount.value)
        assertEquals(1, changed.reads)
    }

    @Test
    fun `an empty result cannot advertise another published leases retained inventory`() = runTest {
        val original = fixture(RelationMeaning.Callees)
        val foreign = fixture(RelationMeaning.Callees, generationNumber = 20)
        val first =
            assertInstanceOf(RelationCompilation.Qualified::class.java, original.compiler.read(original.request(1)))
        val retained =
            assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, first.coverage)
                .continuation
                .providerState
        val request = foreign.request(1)
        val emptyBatch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0).refined(),
                    RelationWorkCount.parse(0).refined(),
                    RelationResultCount.parse(0).refined(),
                )
                .refined()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_AUTHORITY_MISMATCH),
            RelationCompilation.qualifiedResumable(
                emptyBatch,
                setOf(RelationLimitation.WORK_LIMIT_REACHED),
                retained.providerCursor,
                retained,
            ),
        )
        val publication =
            assertInstanceOf(RelationProviderLocator.PublishedFact::class.java, retained.prepared.first()).publication
        val emptyInventory = RelationProviderState.publishedFacts(publication, emptyList()).refined()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_AUTHORITY_MISMATCH),
            RelationCompilation.qualifiedResumable(
                emptyBatch,
                setOf(RelationLimitation.WORK_LIMIT_REACHED),
                emptyInventory.providerCursor,
                emptyInventory,
            ),
        )
    }

    @Test
    fun `a page whose first fact exceeds its byte budget is finitely terminal`() = runTest {
        val fixture = fixture(RelationMeaning.Callees)
        val result =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                fixture.compiler.read(fixture.request(1, bytes = 1)),
            )
        assertTrue(result.batch.facts.isEmpty())
        val coverage = assertInstanceOf(RelationIncompleteCoverage.TerminalIncomplete::class.java, result.coverage)
        assertEquals(setOf(RelationLimitation.BYTE_LIMIT_REACHED), coverage.limitations)
    }

    private class Fixture(
        val selector: SymbolSelector,
        val meaning: RelationMeaning,
        val compiler: SqliteTopologyRelationCompiler,
        val readCount: () -> Int,
    ) {
        val reads: Int
            get() = readCount()

        fun request(limit: Int, bytes: Long = 10_000): RelationRequest =
            RelationRequest.start(
                selector,
                meaning,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(limit).refined(),
                        WorkUnitLimit.parse(100).refined(),
                        ElapsedTimeLimitMillis.parse(1_000).refined(),
                    ),
                    RelationByteLimit.parse(bytes).refined(),
                ),
            )
    }

    private fun fixture(meaning: RelationMeaning, sourceHash: String = "a", generationNumber: Long = 19): Fixture {
        val generation = generation(meaning, sourceHash, generationNumber)
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
        val name = if (meaning == RelationMeaning.Callees) "Source" else "Target"
        val subject = generation.symbols.single { it.evidence.name.value == name }
        return Fixture(SymbolSelector.issue(generation.identity.lease, scope, subject.evidence), meaning, compiler) {
            reads
        }
    }

    private fun generation(
        meaning: RelationMeaning,
        sourceHash: String,
        generationNumber: Long,
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
        return CompleteTopologyGeneration.admit(
                workspace,
                listOf(sourceFile, targetFile),
                listOf(
                    CompleteTopologyFile.admit(sourceFile, listOf(source), edges).refined(),
                    CompleteTopologyFile.admit(targetFile, listOf(target), emptyList()).refined(),
                ),
            )
            .refined()
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
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected fixture refinement, got $failure")
    }
