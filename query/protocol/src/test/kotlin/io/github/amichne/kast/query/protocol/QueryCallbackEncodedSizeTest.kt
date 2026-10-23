package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackDirectInvocationBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class QueryCallbackEncodedSizeTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `recursive observed and stored owner wire fits the unchanged conservative projection bound`() = runTest {
        for (stored in listOf(false, true)) {
            val observation = composedObservation(stored)
            val script =
                Script(
                    listOf(listOf(row)),
                    resultForPage = { rows, _ ->
                        QueryResult(QueryRows.Symbols.of(rows), emptyList(), relationObservations = listOf(observation))
                    },
                )
            val outcome =
                CanonicalQueryProtocol(script.operations, fixture.references)
                    .execute(strict, fixture.authority, budget, policy())
            val projected =
                assertInstanceOf(
                    ProjectedOperationOutcome.Complete::class.java,
                    CanonicalQueryCliDocuments.project(outcome),
                    "Callback projection must retain its admitted outcome: $outcome",
                )
            val actual =
                Json.parseToJsonElement(projected.document.value)
                    .jsonObject
                    .getValue("relation_observations")
                    .jsonArray
                    .single()
            val encodedBytes = actual.toString().toByteArray(Charsets.UTF_8).size.toLong()
            assertTrue(
                encodedBytes <= observation.projectedUtf8Size(),
                "encoded=$encodedBytes estimate=${observation.projectedUtf8Size()} stored=$stored",
            )
            assertEquals(1, actual.jsonObject.getValue("callback_observations").jsonArray.size)
            script.assertDrained()
        }
    }

    private fun composedObservation(stored: Boolean): QueryRelationObservation {
        val request =
            RelationRequest.start(fixture.selector, RelationMeaning.Callees, RelationPagingFixture.published().budget)
        val original = observation(true).callbackObservations.single()
        val flow = nestedFlow(stored)
        val callback =
            RelationCallbackObservation.fromNativeBoundary(
                    request,
                    original.occurrence,
                    original.target,
                    original.lexicalOwner,
                    original.callbackBody,
                    original.policy,
                    CallbackInvocationFlowRead.Observed(flow),
                    original.resultProjection,
                )
                .refined()
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(0).refined(),
                    callbackObservations = listOf(callback),
                )
                .refined()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }

    private fun nestedFlow(stored: Boolean): CallbackInvocationFlow {
        val base = (boundFlow() as CallbackInvocationFlowRead.Observed).flow
        var activation: RelationCallableBody = RelationCallableBody.Named.fromCompiler(wrapperEvidence()).refined()
        var proofs = emptyList<CallbackBodyBinding>()
        for (level in 0..2) {
            val body = anonymous(140 + level * 5, 190 - level * 5)
            val binding =
                CallbackDirectInvocationBinding.fromCompiler(
                        fixture.authority.identity,
                        occurrence(body.range.startInclusive - 1, body.range.endExclusive + 1),
                        activation,
                    )
                    .refined()
            val owner =
                if (stored && level == 2) storedOwner(body, binding, proofs) else directOwner(body, binding, proofs)
            activation = body
            proofs = listOf(owner)
        }
        return CallbackInvocationFlow.fromCompiler(
                base.basis,
                base.body,
                base.binding,
                listOf(CallbackParameterInvocation.fromCompiler(occurrence(160, 170), activation).refined()),
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                CallbackInvocationScan.EXHAUSTIVE,
            )
            .refined()
            .withOwnerBindings(proofs)
            .refined()
    }

    private fun directOwner(
        body: RelationCallableBody.Anonymous,
        direct: CallbackDirectInvocationBinding,
        owners: List<CallbackBodyBinding>,
    ): CallbackBodyBinding {
        val binding = CallbackBindingEvidence.Direct(direct)
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    fixture.authority.identity,
                    body,
                    binding,
                    emptyList(),
                    if (owners.isEmpty()) emptySet() else setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    CallbackInvocationScan.NOT_APPLICABLE,
                )
                .refined()
                .withOwnerBindings(owners)
                .refined()
        return CallbackBodyBinding.fromCompiler(
                body,
                CallbackBodySupply.DirectInvocation(direct.occurrence),
                binding,
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            )
            .refined()
            .withExecution(flow)
            .refined()
    }

    private fun storedOwner(
        body: RelationCallableBody.Anonymous,
        direct: CallbackDirectInvocationBinding,
        owners: List<CallbackBodyBinding>,
    ): CallbackBodyBinding {
        val named = RelationCallableBody.Named.fromCompiler(wrapperEvidence()).refined()
        val endpoint = RelationEndpoint.resolve(fixture.authority, fixture.selector.scope, named.evidence).refined()
        val source = ValueSite.fromCompiler(endpoint, body.range, ValueRole.ExpressionResult).refined()
        val origin = ImmutableCallbackValueOrigin.Anonymous(body)
        val value = ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList()).refined()
        val flow =
            ImmutableCallbackInvocationFlow.fromCompiler(
                    origin,
                    source,
                    listOf(ImmutableCallbackInvocationUse.Direct(value, direct)),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
                .withOwnerBindings(owners)
                .refined()
        return CallbackBodyBinding.fromCompiler(
                body,
                CallbackBodySupply.Stored,
                CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                setOf(
                    CallbackInvocationFlowCause.STORED_CALLBACK,
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                ),
            )
            .refined()
            .withExecution(flow, named)
            .refined()
    }

    private fun anonymous(start: Int, end: Int): RelationCallableBody.Anonymous {
        val site = range(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(fixture.selector.file, site),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(fixture.selector.file, site, signature).refined()
    }
}
