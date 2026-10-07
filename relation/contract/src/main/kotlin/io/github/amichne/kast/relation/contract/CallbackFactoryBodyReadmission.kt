package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/**
 * The full dependency snapshot revalidates boundary inputs; every source target receives current compiler authority.
 */
internal class CallbackFactoryBodyReadmission(private val endpoints: CallbackEndpointReadmissions) {
    private val evidence = CallbackEvidenceReadmission(endpoints)

    fun read(previous: CallbackFactoryBodyCalls): CallbackReadmission<CallbackFactoryBodyCalls> =
        when (previous) {
            CallbackFactoryBodyCalls.NotApplicable -> Refinement.Refined(previous)
            is CallbackFactoryBodyCalls.Exhaustive ->
                readmitEach(previous.calls, ::call).then { calls ->
                    when (
                        val admitted =
                            CallbackFactoryBodyCalls.Exhaustive.fromCompiler(
                                previous.body,
                                calls,
                                CallbackInvocationScan.EXHAUSTIVE,
                            )
                    ) {
                        is Refinement.Refined -> admitted
                        is Refinement.Rejected ->
                            Refinement.Rejected(CallbackSummaryReadmissionFailure.FactoryBody(admitted.failure))
                    }
                }
        }

    private fun call(previous: CallbackFactoryBodyCall): CallbackReadmission<CallbackFactoryBodyCall> =
        when (previous) {
            is CallbackFactoryBodyCall.Named ->
                endpoints.endpoint(previous.target).then { target ->
                    Refinement.Refined(CallbackFactoryBodyCall.Named(previous.occurrence, target))
                }
            is CallbackFactoryBodyCall.Captured ->
                evidence.formal(previous.formal).then { formal ->
                    evidence.invocation(previous.invocation).then { invocation ->
                        Refinement.Refined(CallbackFactoryBodyCall.Captured(invocation, formal))
                    }
                }
            is CallbackFactoryBodyCall.Boundary -> Refinement.Refined(previous)
        }
}
