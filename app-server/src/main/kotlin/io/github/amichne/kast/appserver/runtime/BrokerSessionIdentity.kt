package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal data class InvocationIdentity(
    val thread: io.github.amichne.kast.appserver.core.BrokerThreadId,
    val turn: io.github.amichne.kast.appserver.core.BrokerTurnId,
    val call: io.github.amichne.kast.appserver.core.BrokerCallId,
) {
    fun persistenceKey(): String =
        JsonArray(listOf(thread.value, turn.value, call.value).map(::JsonPrimitive)).toString()
}

internal enum class Handshake {
    RESPONSE_PENDING,
    INITIALIZED_PENDING,
    READY,
    CLOSED,
}
