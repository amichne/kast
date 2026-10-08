package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real evaluator, admission, projection and hosted fitter; scripted ports establish no installed native claim. */
class HostedImpactByteAdmissionTest {
    @Test
    fun `encoded impact envelope fits below its conservative retained storage charge`() = runTest {
        val f = Fixture()
        val response =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { owner -> f.protocol(owner).execute(f.request(), f.symbols.authority, f.budget) },
                fit = { page ->
                    encodeHostedQueryResponse(page, maximumBytes = ReturnedByteLimit.parse(10000).value())
                },
            )
        val canonical = assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertTrue(canonical.document.toByteArray(Charsets.UTF_8).size <= 10000)
        val result =
            Json.parseToJsonElement(canonical.document)
                .jsonObject
                .getValue("body")
                .jsonObject
                .getValue("result")
                .jsonObject
        val item = result.getValue("items").jsonArray.single().jsonObject
        assertEquals("VALUE_PATH", item.getValue("type").jsonPrimitive.content)
        assertEquals(
            "SUPPORTED_DOMAIN_END",
            item.getValue("path").jsonObject.getValue("terminal").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(1, f.seedCalls)
        assertEquals(1, f.flowCalls)
        assertTrue(f.retainedBytes > 10000)
    }

    private class Fixture {
        val symbols = RelationPagingFixture.published()
        val budget = QueryBudget(symbols.budget.resources, QueryByteLimit.parse(10000).value())
        var seedCalls = 0
        var flowCalls = 0
        var retainedBytes = 0L
        private val owner = RelationEndpoint.subject(symbols.selector)
        private val invocation =
            ValueInvocation.fromCompiler(owner, ExactDeclarationTextRange.parse(0, 1).value(), owner).value()
        private val site = invocation.resultSite()
        private val seeds =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    check(seedCalls++ == 0) { "Unexpected seed call" }
                    assertEquals(site.range, request.anchor)
                    return ValueProducerSeedRead.Seeded(
                        ValueProducerSeed.fromCompiler(request, site, invocation).value(),
                        RelationWorkCount.parse(3).value(),
                    )
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("Unexpected model read")
            }
        private val flow = ValueFlowCompilerPort { request ->
            check(flowCalls++ == 0) { "Unexpected flow call" }
            assertEquals(site, request.source)
            val step =
                ValueFlowStep.fromCompiler(
                        site,
                        emptyList(),
                        emptyList(),
                        ValueFlowTerminal.SupportedDomainExhausted,
                        RelationRequest.start(
                            symbols.selector,
                            RelationMeaning.References,
                            request.budget,
                            request.boundary,
                        ),
                        RelationWorkCount.parse(1).value(),
                    )
                    .value()
            ValueFlowRead.Observed(step)
        }

        fun protocol(presentation: QueryPresentationExecution): CanonicalQueryProtocol {
            val service =
                QueryService(
                    discovery = SymbolDiscoveryOperations { error("Unexpected discovery") },
                    exact =
                        object : SymbolExactOperations {
                            override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                                error("Unexpected resolve")

                            override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                                error("Unexpected describe")
                        },
                    source = SourceReadOperations { error("Unexpected source") },
                    relations = RelationOperations { error("Unexpected relation") },
                    traversal = TraversalOperations { error("Unexpected traversal") },
                    traversalCeiling = traversalBudget(),
                    clock = QueryNanoClock { 0 },
                    valueFlow = flow,
                    presentation = presentation,
                )
            return CanonicalQueryProtocol(
                operations =
                    io.github.amichne.kast.query.contract.QueryOperations { request ->
                        val result = service.run(request)
                        val complete =
                            assertInstanceOf(
                                io.github.amichne.kast.query.contract.QueryExecutionResult.Complete::class.java,
                                result,
                            )
                        retainedBytes =
                            (complete.result.rows as io.github.amichne.kast.query.contract.QueryRows.ValuePaths)
                                .values
                                .single()
                                .retainedBytes
                        result
                    },
                authority = symbols.references,
                producerSeeds = seeds,
            )
        }

        private fun traversalBudget() =
            TraversalBudget(
                budget.resources.resultLimit,
                TraversalByteLimit.parse(10000).value(),
                budget.resources.workUnitLimit,
                budget.resources.elapsedTimeLimit,
                TraversalDepthLimit.parse(1).value(),
                TraversalFrontierLimit.parse(3).value(),
                symbols.budget,
            )

        fun request() =
            QueryRunRequest.Run(
                QueryFromDocument.Impact(
                    QueryImpactSourceDocument(
                        bounded(
                            listOf(
                                QueryImpactProducerDocument(
                                    symbols.exact,
                                    symbols.exact,
                                    ImpactSourceRangeDocument(
                                        ProtocolOffset.parse(0).value(),
                                        ProtocolOffset.parse(1).value(),
                                    ),
                                )
                            )
                        ),
                        bounded(emptyList()),
                        QueryExpansionScopeDocument.Workspace,
                        QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                        bounded(emptyList()),
                    )
                ),
                bounded(emptyList()),
                QueryOutputDocument.ValuePaths,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
                retention = QueryRetentionModeDocument.RETAIN,
                completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
            )
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()
