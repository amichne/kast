package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.protocol.codex.ProtocolCloseFailure
import java.io.PrintStream
import kotlinx.serialization.json.*

internal enum class SessionStage {
    ADMISSION,
    HANDSHAKE,
    TRANSPORT,
    SUBSCRIPTION,
    INVOCATION,
    APPROVAL_PREPARE,
    APPROVAL_REQUEST,
    APPROVAL_REDEEM,
    TOOL_DISPLAY,
    RECONCILIATION,
}

internal enum class SessionOutcome {
    STARTED,
    READY,
    DETACHED,
    COMPLETED,
    REJECTED,
    UNCERTAIN,
    RETIRED,
}

internal data class SessionActivity(
    val connection: ClientConnectionId,
    val stage: SessionStage,
    val outcome: SessionOutcome,
    val protocolFailure: ProtocolCloseFailure? = null,
)

internal fun interface SessionActivitySink {
    fun publish(activity: SessionActivity)

    data object Disabled : SessionActivitySink {
        override fun publish(activity: SessionActivity) = Unit
    }
}

/** A bounded payload-free trail; operation arguments and source output never enter it. */
internal class SessionActivityJournal(private val sink: SessionActivitySink) {
    private val events = ArrayDeque<SessionActivity>()

    @Synchronized
    fun publish(activity: SessionActivity) {
        if (events.size == BrokerOperationalLimits.maximumSessionEvents) events.removeFirst()
        events.addLast(activity)
        sink.publish(activity)
    }

    @Synchronized fun snapshot(): List<SessionActivity> = events.toList()
}

internal class JsonLineSessionActivitySink(private val output: PrintStream) : SessionActivitySink {
    @Synchronized
    override fun publish(activity: SessionActivity) {
        output.println(activity.document())
    }
}

internal fun SessionActivity.document(): JsonObject = buildJsonObject {
    put("component", "kast-app-server")
    put("connectionId", connection.value)
    put("stage", stage.name.lowercase())
    put("outcome", outcome.name.lowercase())
    protocolFailure?.let { put("failure", it.javaClass.simpleName) }
    if (protocolFailure is ProtocolCloseFailure.ToolCallProjectionRejected) {
        put("reason", protocolFailure.failure.name)
    }
}
