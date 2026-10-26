package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactConditionModelTest {
    @Test
    fun `modeled transfer and transformation retain source prerequisites on result and identity`() {
        val f = QueryImpactExecutionFixture()
        val call = f.call("transform", 70, 80)
        val conditions = conditions(f)
        val producer = f.producer.site.withState(conditions = conditions).value()
        val input = f.argument(call).withState(conditions = conditions).value()
        val models = models(f, call)
        val edge = f.edge(producer, input, ValueTransferKind.ARGUMENT)
        val evidence =
            RepresentationEvidence.origin(producer, f.producer.invocation, models.origin).value().transfer(edge).value()
        val route =
            QueryImpactRoute(
                producer,
                listOf(QueryImpactStep.Compiler(edge)),
                QueryImpactRepresentation.Present(evidence),
            )
        for (rule in models.continuations) {
            val source =
                QueryImpactSource.admit(
                        producers = listOf(f.producer),
                        representationModels = listOf(models.origin, rule),
                        boundaryModels = emptyList(),
                        domain = RelationSearchBoundary.WORKSPACE_EXPANSION,
                    )
                    .value()
            val arrivals = modeledImpactArrivals(route, source).value()
            val next = (arrivals.tasks.single() as PipelineTask.ImpactExplore).route
            assertSame(conditions, next.site.conditions)
            assertSame(conditions, next.site.identity.conditions)
            assertEquals(call.range, next.site.range)
            assertEquals(ValueRole.ExpressionResult, next.site.role)
            assertEquals(
                conditions,
                (next.representation as QueryImpactRepresentation.Present).evidence.site.conditions,
            )
            val graph = QueryImpactRetainedGraph()
            graph.site(route.site)
            val reserve = graph.site(call.resultSite()) + graph.conditions(conditions) + graph.conditions(conditions)
            val actualGraph = QueryImpactRetainedGraph()
            actualGraph.site(route.site)
            assertTrue(reserve >= actualGraph.site(next.site))
        }
    }

    private data class Models(val origin: RepresentationRule.Origin, val continuations: List<RepresentationRule>)

    private fun models(f: QueryImpactExecutionFixture, call: ValueInvocation): Models {
        val identity = ContractModelIdentity(id("conditional"), ModelVersion.parse(1).value(), id("review:1"))
        val vocabulary = RepresentationDomain.admit(identity, listOf(id("before"), id("after"))).value()
        val before = vocabulary.state(id("before")).value()
        val after = vocabulary.state(id("after")).value()
        val origin =
            RepresentationRule.Origin.admit(
                    ModelRuleReference(identity, id("origin")),
                    f.bind(f.producer.invocation.callable, ModelValuePosition.Result),
                    before,
                )
                .value()
        val boundInput = f.bind(call.callable, ModelValuePosition.Argument(ValueArgumentPosition.parse(0).value()))
        val boundOutput = f.bind(call.callable, ModelValuePosition.Result)
        val rules =
            listOf(
                RepresentationRule.Transfer.admit(ModelRuleReference(identity, id("transfer")), boundInput, boundOutput)
                    .value(),
                RepresentationRule.Transformation.admit(
                        reference = ModelRuleReference(identity, id("transform")),
                        input = boundInput,
                        output = boundOutput,
                        from = before,
                        to = after,
                    )
                    .value(),
            )
        return Models(origin, rules)
    }

    private fun conditions(f: QueryImpactExecutionFixture): ValueConditions {
        val receiver =
            ValueSite.fromCompiler(
                    f.owner,
                    f.owner.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .value()
        val fact =
            ValueCondition.StableReceiver.fromCompiler(
                    source = receiver,
                    useRange = f.producer.site.range,
                    kind = ValueReceiverKind.EXTENSION,
                    originalType = CanonicalCompilerType.fromCompiler("fixture.Choice").value(),
                    refinedType = CanonicalCompilerType.fromCompiler("fixture.Selected").value(),
                )
                .value()
        return ValueConditions.Unconditional.requiring(fact).value()
    }

    private fun id(raw: String): ModelIdentifier = ModelIdentifier.parse(raw).value()

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
