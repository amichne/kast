package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RelationDirectCallbackBatchTest {
    private val case = CallbackInvocationFlowFixture()

    @Test
    fun `direct callback policy requires its supporting exact named fact`() {
        val request = request()
        val callback = directCallback(request)
        assertEquals(
            Refinement.Rejected(RelationBatchFailure.INVALID_CALLBACK_EXCLUSION),
            batch(request, callback, emptyList()),
        )
    }

    @Test
    fun `direct callback policy preserves its exact supporting named fact`() =
        with(case) {
            val request = request()
            val callback = directCallback(request)
            val fact =
                RelationFact.create(
                        request,
                        request.subject,
                        target,
                        callback.occurrence,
                        RelationProvenance.K2_AUTHORED_SOURCE,
                    )
                    .value()
            val admitted = assertInstanceOf(Refinement.Refined::class.java, batch(request, callback, listOf(fact)))
            val retained = admitted.value as RelationBatch
            assertEquals(listOf(fact), retained.facts)
            assertEquals(listOf(callback), retained.callbackObservations)
        }

    private fun directCallback(request: RelationRequest): RelationCallbackObservation =
        with(case) {
            val direct =
                CallbackDirectInvocationBinding.fromCompiler(lease.identity, occurrence(20, 80), supplyingOwner).value()
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        lease.identity,
                        body,
                        CallbackBindingEvidence.Direct(direct),
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.NOT_APPLICABLE,
                    )
                    .value()
            RelationCallbackObservation.fromNativeBoundary(
                    request,
                    occurrence(35, 36),
                    target.evidence,
                    caller.evidence,
                    occurrence(body.range.startInclusive, body.range.endExclusive),
                    CallbackNamedCallPolicy.AdmittedDirect,
                    CallbackInvocationFlowRead.Observed(flow),
                )
                .value()
        }

    private fun batch(
        request: RelationRequest,
        callback: RelationCallbackObservation,
        facts: List<RelationFact>,
    ) =
        with(case) {
            val bytes =
                callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() +
                    facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
            RelationBatch.create(
                request,
                facts,
                RelationByteCount.parse(bytes).value(),
                RelationWorkCount.parse(1L).value(),
                RelationResultCount.parse(facts.size).value(),
                callbackObservations = listOf(callback),
            )
        }

    private fun request(): RelationRequest =
        with(case) {
            RelationRequest.start(
                caller,
                RelationMeaning.Callees,
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(4).value(),
                        WorkUnitLimit.parse(4L).value(),
                        ElapsedTimeLimitMillis.parse(1000L).value(),
                    ),
                    RelationByteLimit.parse(100_000L).value(),
                ),
            )
        }
}
