package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** An exhausted relation hop proves absence only after its first relation-bearing stage received an input. */
class QueryRelationSeedTest {
    private val fixture = QueryServiceTest()

    @Test
    fun `relation hop from an exhausted empty selection rejects instead of reporting complete absence`() = runTest {
        for (step in relationSteps) {
            val result =
                service(fixture.discoveryEmpty(qualified = false), unexpectedRelations())
                    .run(fixture.request(namePlan(listOf(step)), workLimit = 8L))

            assertEquals(
                QueryExecutionResult.Rejected(QueryExecutionRejection.RELATION_SEED_EMPTY),
                result,
                "$step from an empty seed",
            )
        }
    }

    @Test
    fun `bare empty selection remains an honest complete absence over its declared kinds`() = runTest {
        val result =
            service(fixture.discoveryEmpty(qualified = false), unexpectedRelations())
                .run(fixture.request(namePlan(emptyList()), workLimit = 8L))

        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
        assertEquals(0, complete.result.symbolRows().size)
    }

    @Test
    fun `incomplete empty selection keeps its discovery qualification ahead of the seed guard`() = runTest {
        val result =
            service(fixture.discoveryEmpty(qualified = true), unexpectedRelations())
                .run(
                    fixture.request(
                        namePlan(listOf(QueryStepSyntax.Related(RelationMeaning.References))),
                        workLimit = 8L,
                    )
                )

        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        assertEquals(listOf(QueryLimitation.DISCOVERY_INCOMPLETE), qualified.coverage.limitations)
    }

    @Test
    fun `a later hop fed by a proven empty first hop remains complete absence`() = runTest {
        val seed = fixture.selector(fixture.selection())
        var reads = 0
        val relations = RelationOperations { child ->
            reads += 1
            assertEquals(RelationMeaning.References, child.meaning, "only the seeded first hop can read")
            val complete = RelationCompilation.complete(emptyBatch(child))
            RelationReadResult.Complete(complete.batch, complete.coverage)
        }
        val plan =
            fixture.exactReferencePlan(
                listOf(seed),
                listOf(
                    QueryStepSyntax.Related(RelationMeaning.References),
                    QueryStepSyntax.Related(RelationMeaning.Callers),
                ),
            )

        val result = service(fixture.discoveryEmpty(qualified = false), relations).run(fixture.request(plan, 100L))

        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
        assertEquals(0, complete.result.symbolRows().size)
        assertEquals(1, reads)
    }

    @Test
    fun `the obligation belongs to the first relation-bearing stage and survives an omitted checkpoint value`() {
        val plan = namePlan(listOf(QueryStepSyntax.Distinct, QueryStepSyntax.Related(RelationMeaning.References)))
        val initial = assertInstanceOf(QueryRelationSeed.Awaiting::class.java, QueryRelationSeed.initial(plan))
        assertInstanceOf(ExactQueryStage.Related::class.java, initial.stage)

        val distinct = (plan as AdmittedQueryPlan.Symbols).stage
        assertEquals(initial, initial.entered(distinct), "an upstream stage cannot discharge the obligation")
        assertEquals(QueryRelationSeed.Seeded, initial.entered(initial.stage))
        assertEquals(QueryRelationSeed.Seeded, QueryRelationSeed.Seeded.entered(distinct))
        assertEquals(QueryRelationSeed.NotRequired, QueryRelationSeed.initial(namePlan(emptyList())))

        val checkpoint =
            PipelineCheckpoint(
                PipelineSeed.Accounted.create(plan),
                fixture.request(plan, workLimit = 8L).lease,
                emptyList(),
                emptyMap(),
                QueryJoinSnapshot(emptyMap()),
                emptySet(),
                0.queryCount(),
            )
        assertEquals(initial, checkpoint.relationSeed)
    }

    private val relationSteps =
        listOf(
            QueryStepSyntax.Related(RelationMeaning.References),
            QueryStepSyntax.Related(RelationMeaning.Callers),
            QueryStepSyntax.Trace(),
        )

    private fun namePlan(steps: List<QueryStepSyntax>): AdmittedQueryPlan =
        fixture.admittedPlan(
            source =
                QuerySourceSyntax.Symbols(
                    QueryDiscoverySyntax(
                        QueryMatch.Name(
                            SymbolDiscoveryPattern.parse("MIGRATION_REJECTED").refined(),
                            SymbolDiscoveryMatch.EXACT_NAME,
                        ),
                        QueryScope.Unrestricted,
                        QueryDeclarationKinds.from(
                                setOf(
                                    CompilerSymbolKind.CLASSLIKE,
                                    CompilerSymbolKind.FUNCTION,
                                    CompilerSymbolKind.PROPERTY,
                                    CompilerSymbolKind.TYPE_ALIAS,
                                )
                            )
                            .refined(),
                    )
                ),
            steps = steps,
            output =
                QueryOutputSyntax.Symbols(
                    QuerySymbolFields.from(setOf(QuerySymbolField.NAME, QuerySymbolField.LOCATION)).refined()
                ),
        )

    private fun service(discovery: SymbolDiscoveryOperations, relations: RelationOperations): QueryService =
        QueryService(
            discovery,
            fixture.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("An empty selection has no candidate to refine") },
            ),
            SourceReadOperations { error("No source read expected") },
            relations,
            unexpectedQueryTraversal(),
            queryTestTraversalCeiling(),
            clock = QueryNanoClock { 0L },
        )

    private fun unexpectedRelations(): RelationOperations = RelationOperations {
        error("An empty seed must not issue a relation read")
    }

    private fun emptyBatch(request: io.github.amichne.kast.relation.contract.RelationRequest): RelationBatch =
        RelationBatch.create(
                request,
                emptyList(),
                RelationByteCount.parse(0L).refined(),
                RelationWorkCount.parse(0L).refined(),
                RelationResultCount.parse(0).refined(),
            )
            .refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
