package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument

/** Retained request meaning used to admit the existing IDE's supported operation set. */
sealed interface HostedRequestEffect {
    data class Operation(val operation: CanonicalOperation) : HostedRequestEffect

    data class ChangePlan(val intent: ChangeIntentDocument) : HostedRequestEffect
}
