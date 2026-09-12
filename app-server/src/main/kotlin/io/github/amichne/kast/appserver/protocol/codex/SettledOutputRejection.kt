package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.protocol.registry.OperationEffect

/** Only an output-contract rejection after ProviderCall.Completed can use this effect proof. */
internal fun BrokerFailure.OutputContractRejected.certainty(): InvocationCertainty =
    when (val settled = settledEffect) {
        BrokerOperationEffect.Unknown -> InvocationCertainty.UNCERTAIN
        is BrokerOperationEffect.Canonical ->
            when (settled.effect) {
                OperationEffect.NONE,
                OperationEffect.INTELLIJ_READ -> InvocationCertainty.KNOWN
                OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
                OperationEffect.INTELLIJ_WRITE,
                OperationEffect.FILESYSTEM_WRITE,
                OperationEffect.PERSISTENCE_WRITE,
                OperationEffect.WORKSPACE_MODEL_WRITE,
                OperationEffect.PROCESS_CONTROL -> InvocationCertainty.UNCERTAIN
            }
    }
