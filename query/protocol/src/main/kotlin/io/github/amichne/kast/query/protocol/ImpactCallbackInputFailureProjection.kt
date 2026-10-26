package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallbackInputFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure

internal fun ValueCallbackInputFailure.impactDocument(): ImpactCallbackInputFailureDocument =
    when (this) {
        is ValueCallbackInputFailure.Callback ->
            ImpactCallbackInputFailureDocument.Callback(cause.protocolCallbackDocument())
        is ValueCallbackInputFailure.Contract ->
            ImpactCallbackInputFailureDocument.Contract(cause.protocolCallbackDocument())
        is ValueCallbackInputFailure.Graph -> {
            val reason =
                when (val projected = cause.protocolGraphCause()) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> QueryCallbackGraphCauseDocument.InvalidGraphFailure(projected.failure)
                }
            if (cause is StaticCallbackGraphFailure.ImmutableUnresolved)
                ImpactCallbackInputFailureDocument.ImmutableGraph(reason)
            else ImpactCallbackInputFailureDocument.Graph(reason)
        }
    }
