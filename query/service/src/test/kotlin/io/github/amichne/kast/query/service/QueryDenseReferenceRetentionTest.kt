package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderConsumption
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real evaluator and retention rule with detached provider evidence; no native search or K2 execution is asserted. */
class QueryDenseReferenceRetentionTest {
    private val fixture = QueryServiceTest()
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(ROOT)).value()
    private val lease = SemanticReadLease(root, EvidenceGeneration.parse(1).value())
    private val file =
        SymbolDiscoveryFileIdentity.Workspace(CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of(FILE)).value())
    private val scope =
        SymbolSearchScope.ExactFile(
            file.path,
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.EXCLUDE,
        )
    private val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                0,
                32,
                "DenseReferenceTarget",
                "fixture.reference.dense.DenseReferenceTarget",
                CompilerSymbolKind.CLASSLIKE,
                CanonicalCompilerSignature.classLike("fixture.reference.dense.DenseReferenceTarget").value(),
            )
            .value()
    private val selected = SymbolSelector.issue(lease, scope, evidence)
    private val locators =
        (0 until 1_001).map { ordinal ->
            val start = 100 + ordinal * 32
            RelationProviderLocator.Reference(
                file,
                ExactDeclarationTextRange.parse(start, start + 20).value(),
                RelationProviderItemDescriptor.parse(
                        "reference:org.jetbrains.kotlin.idea.references.KtSimpleNameReference\u0000" +
                            "file://$FILE\u0000$start\u0000${start + 20}"
                    )
                    .value(),
            )
        }
    private val inventory = RelationProviderState.references(locators)
    private val plan =
        fixture.admittedPlan(
            QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).value()),
            listOf(QueryStepSyntax.Related(RelationMeaning.References)),
            QueryOutputSyntax.Occurrences,
        )
    private val positions = mutableListOf<Long>()
    private val service =
        QueryService(
            fixture.discoveryEmpty(false),
            fixture.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("No discovery is permitted") },
            ),
            SourceReadOperations { error("No source read is permitted") },
            RelationOperations(::read),
            unexpectedQueryTraversal(),
            queryTestTraversalCeiling(),
        )

    @Test
    fun `real checkpoint snapshots share pending owners while detached copies retain full charges`() = runTest {
        val firstPage = service.run(request()) as QueryExecutionResult.Qualified
        val first = (firstPage.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val secondPage =
            service.run(QueryExecutionRequest.create(plan, lease, request().budget, first).value())
                as QueryExecutionResult.Qualified
        val second = (secondPage.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val shared =
            first.tasks.filterIsInstance<PipelineTask.Occurrence>().first { candidate ->
                second.tasks.any { it === candidate }
            }
        val expectedOwner = 512L + 512L + 8L + shared.value.projectedUtf8Size() * 4L
        val graph = QueryImpactRetainedGraph()
        assertEquals(expectedOwner, graph.checkpointTask(shared))
        assertEquals(8L, graph.checkpointTask(shared))
        assertEquals(expectedOwner, graph.checkpointTask(shared.copy()))

        val retained = QueryImpactRetainedGraph()
        val rejected = retained.transaction()
        assertEquals(expectedOwner, rejected.graph.checkpointTask(shared))
        val accepted = retained.transaction()
        assertEquals(expectedOwner, accepted.graph.checkpointTask(shared))
        accepted.commit()
        assertEquals(8L, retained.checkpointTask(shared))

        val checkpoints = QueryImpactRetainedGraph()
        assertEquals(first.retainedBytes, first.retainedBytes(checkpoints))
        assertTrue(second.retainedBytes(checkpoints) < second.retainedBytes)
    }

    @Test
    fun `prepared inventory plus pending confirmed rows has a bounded checkpoint estimate`() = runTest {
        val generous = request(32L * 1_024 * 1_024)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(generous))
        var checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation).checkpoint
        val related = (checkpoint as PipelineCheckpoint).tasks.filterIsInstance<PipelineTask.Related>().single()
        assertEquals(locators.drop(20), related.cursor!!.providerState.prepared)
        assertEquals(scope, related.value.selector.scope)
        repeat(locators.size * 2 + 1) {
            assertTrue(
                checkpoint.retainedBytes < QueryByteLimit.DefaultCheckpoint.value,
                "Required checkpoint estimate=${checkpoint.retainedBytes}; " +
                    "inventory estimate=${inventory.retainedBytes}",
            )
            val request = QueryExecutionRequest.create(plan, lease, generous.budget, checkpoint).value()
            when (val page = service.run(request)) {
                is QueryExecutionResult.Complete -> return@runTest
                is QueryExecutionResult.Qualified ->
                    checkpoint =
                        assertInstanceOf(QueryContinuationState.Resumable::class.java, page.continuation).checkpoint
                is QueryExecutionResult.ImpactRejected -> error("Unexpected impact rejection")
                is QueryExecutionResult.Rejected -> error("Generous detached checkpoint rejected: ${page.reason}")
            }
        }
        error("Finite dense inventory exceeded its independent page ceiling")
    }

    @Test
    fun `dense reference inventory and pending observations fit default checkpoint and drain exactly once`() = runTest {
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(request()))
        // Ordinary ASCII proof text must not spend the six-byte JSON escape bound for every code unit.
        // This is evaluator admission evidence only; native work is measured by the replay suite.
        assertTrue(referenceStarts(first).size >= 4, "The 32 KiB grant must admit at least four full occurrence proofs")
        val continuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation)
        assertTrue(continuation.checkpoint.retainedBytes < QueryByteLimit.DefaultCheckpoint.value)
        val starts = referenceStarts(first)
        var next = continuation.checkpoint
        run {
            repeat(locators.size * 2 + 1) {
                val page = service.run(QueryExecutionRequest.create(plan, lease, request().budget, next).value())
                when (page) {
                    is QueryExecutionResult.Complete -> {
                        starts += referenceStarts(page)
                        return@run
                    }
                    is QueryExecutionResult.Qualified -> {
                        starts += referenceStarts(page)
                        next =
                            assertInstanceOf(QueryContinuationState.Resumable::class.java, page.continuation).checkpoint
                    }
                    is QueryExecutionResult.ImpactRejected -> error("Unexpected impact rejection")
                    is QueryExecutionResult.Rejected -> error("Dense checkpoint rejected: ${page.reason}")
                }
            }
            error("Finite dense inventory exceeded its independent page ceiling")
        }
        assertEquals(locators.map { it.range.startInclusive }.sorted(), starts.sorted())
        assertEquals(starts.size, starts.distinct().size)
        assertEquals((0L..1_000L step 20).toList(), positions)
        assertEquals(scope, selected.scope)
        assertEquals(locators.drop(20), inventory.consumeTwenty().prepared)
    }

    private fun request(checkpointBytes: Long = QueryByteLimit.DefaultCheckpoint.value): QueryExecutionRequest =
        QueryExecutionRequest.create(
                plan,
                lease,
                QueryBudget(
                    ResourceBudget(
                        ResultLimit.parse(20).value(),
                        WorkUnitLimit.parse(32).value(),
                        ElapsedTimeLimitMillis.parse(10_000).value(),
                    ),
                    QueryByteLimit.parse(32_768).value(),
                    QueryByteLimit.parse(checkpointBytes).value(),
                ),
            )
            .value()

    private fun read(read: RelationRequest): RelationReadResult {
        var retained = (read.position as? RelationReadPosition.Resume)?.continuation?.providerState ?: inventory
        positions += retained.consumedLocatorCount.value
        val references =
            retained.prepared
                .take(20)
                .map { locator ->
                    reference(read, locator).also {
                        retained = retained.consume(RelationProviderConsumption.Confirmed(it))
                    }
                }
                .sorted()
        val batch =
            RelationBatch.create(
                    read,
                    references.map { it.declarationFact(read).value() }.sorted(),
                    RelationByteCount.parse(
                            references.sumOf {
                                it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() +
                                    it.declarationFact(read)
                                        .value()
                                        .canonicalProjection()
                                        .toByteArray(Charsets.UTF_8)
                                        .size
                            }
                        )
                        .value(),
                    RelationWorkCount.parse(references.size.toLong()).value(),
                    RelationResultCount.parse(references.size).value(),
                    references,
                )
                .value()
        return if (retained.hasUnfinishedWork) {
            val coverage =
                RelationIncompleteCoverage.resumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        retained.providerCursor,
                        retained,
                    )
                    .value()
            RelationReadResult.Qualified(batch, coverage)
        } else RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun reference(read: RelationRequest, locator: RelationProviderLocator): RelationReferenceOccurrence =
        RelationReferenceOccurrence.confirmed(
                read,
                RelationConfirmedReferenceTarget.fromCompiler(
                        read.subject,
                        io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                            (read.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
                        ),
                    )
                    .value(),
                RelationOccurrence.fromBoundary(
                        locator.file,
                        locator.range.startInclusive,
                        locator.range.endExclusive,
                    )
                    .value(),
                RelationReferenceContext.TYPE,
                RelationReferenceOwnership.DeclarationOwned(owner(read, locator)),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .value()

    private fun owner(read: RelationRequest, locator: RelationProviderLocator): RelationEndpoint.Resolved {
        val ordinal = (locator.range.startInclusive - 100) / 32
        val name = "reference${ordinal.toString().padStart(4, '0')}"
        val identity = "fixture.reference.denseusage.DenseReferences.$name"
        val property =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    locator.range.startInclusive - 20,
                    locator.range.endExclusive + 8,
                    name,
                    identity,
                    CompilerSymbolKind.PROPERTY,
                    CanonicalCompilerSignature.property(
                            identity,
                            null,
                            emptyList(),
                            "fixture.reference.dense.DenseReferenceTarget?",
                        )
                        .value(),
                )
                .value()
        return RelationEndpoint.resolve(lease, read.searchScope, property).value()
    }

    private fun referenceStarts(page: QueryExecutionResult): MutableList<Int> {
        val result =
            when (page) {
                is QueryExecutionResult.Complete -> page.result
                is QueryExecutionResult.Qualified -> page.result
                is QueryExecutionResult.ImpactRejected -> error("Unexpected impact rejection")
                is QueryExecutionResult.Rejected -> error("Expected reference page")
            }
        return (result.rows as QueryRows.Occurrences)
            .values
            .map {
                (it as QueryOccurrence.Reference).value.occurrence.range.startInclusive
            }
            .toMutableList()
    }

    private fun RelationProviderState.consumeTwenty(): RelationProviderState =
        (0 until 20).fold(this) { state, _ -> state.consume() }

    private fun <Value, Failure> Refinement<Value, Failure>.value(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Detached dense fixture rejected: $failure")
        }
}

private const val ROOT = "/private/tmp/kast-semantic-native-oals0m41/fixture"
private const val FILE = "$ROOT/src/main/kotlin/fixture/references/ReadDenseReferences.kt"
