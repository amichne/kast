package io.github.amichne.kast.cli.broker.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

internal enum class BrokerInvocationCompletion {
    COMPLETED,
    REJECTED,
    CANCELLED,
}

/** Payload-free lifecycle evidence for one admitted dynamic-tool invocation. */
internal sealed interface BrokerInvocationActivity {
    val context: BrokerInvocationContext
    val address: ToolAddress

    data class Started(
        override val context: BrokerInvocationContext,
        override val address: ToolAddress,
    ) : BrokerInvocationActivity

    data class Finished(
        override val context: BrokerInvocationContext,
        override val address: ToolAddress,
        val completion: BrokerInvocationCompletion,
    ) : BrokerInvocationActivity
}

internal enum class BrokerInvocationActivityPublication {
    PUBLISHED,
    SKIPPED,
    REJECTED,
}

internal fun interface BrokerInvocationActivitySink {
    fun publish(activity: BrokerInvocationActivity): BrokerInvocationActivityPublication

    data object Disabled : BrokerInvocationActivitySink {
        override fun publish(
            activity: BrokerInvocationActivity,
        ): BrokerInvocationActivityPublication = BrokerInvocationActivityPublication.SKIPPED
    }
}

/** Synchronized JSON-line projection for the launchd-owned broker service log. */
internal class JsonLineBrokerInvocationActivitySink(
    private val output: PrintStream,
) : BrokerInvocationActivitySink {
    @Synchronized
    override fun publish(
        activity: BrokerInvocationActivity,
    ): BrokerInvocationActivityPublication {
        val document = buildJsonObject {
            put("component", "kast-broker")
            put(
                "event",
                when (activity) {
                    is BrokerInvocationActivity.Started -> "tool-call-started"
                    is BrokerInvocationActivity.Finished -> "tool-call-finished"
                },
            )
            put("threadId", activity.context.threadId.value)
            put("turnId", activity.context.turnId.value)
            put("callId", activity.context.callId.value)
            put("namespace", activity.address.namespace.value)
            put("tool", activity.address.tool.value)
            if (activity is BrokerInvocationActivity.Finished) {
                put("completion", activity.completion.name.lowercase())
            }
        }
        output.println(document.toString())
        return if (output.checkError()) {
            BrokerInvocationActivityPublication.REJECTED
        } else {
            BrokerInvocationActivityPublication.PUBLISHED
        }
    }
}
