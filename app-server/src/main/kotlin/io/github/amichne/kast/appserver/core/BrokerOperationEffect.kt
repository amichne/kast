package io.github.amichne.kast.appserver.core

import io.github.amichne.kast.protocol.registry.OperationEffect

/** Retains canonical effect metadata admitted by provider qualification; other providers remain unknown. */
internal sealed interface BrokerOperationEffect {
    data object Unknown : BrokerOperationEffect

    data class Canonical(val effect: OperationEffect) : BrokerOperationEffect
}
