package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolQualificationFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

/** Ordered, bounded effect stages for one broker-service startup. */
internal enum class BrokerStartupStage {
    READINESS_ACQUISITION,
    KAST_QUALIFICATION,
    GRADLE_DEFINITION,
    CATALOG,
    CODEX_QUALIFICATION,
    THREAD_STORE,
    UPSTREAM,
    PUBLIC_SERVER,
    READINESS_PUBLICATION,
}

/** Closed startup rejection evidence, retaining a Codex protocol's exact finite cause. */
internal sealed interface BrokerStartupRejection {
    data class Server(
        val failure: InstalledBrokerServerFailure,
    ) : BrokerStartupRejection

    data class CodexQualification(
        val failure: CodexProtocolQualificationFailure,
    ) : BrokerStartupRejection
}

/** Payload-free lifecycle evidence emitted at the broker startup coordinator boundary. */
internal sealed interface BrokerStartupActivity {
    val stage: BrokerStartupStage

    data class Started(
        override val stage: BrokerStartupStage,
    ) : BrokerStartupActivity

    data class Completed(
        override val stage: BrokerStartupStage,
    ) : BrokerStartupActivity

    data class Rejected(
        override val stage: BrokerStartupStage,
        val rejection: BrokerStartupRejection,
    ) : BrokerStartupActivity
}

internal enum class BrokerStartupActivityPublication {
    PUBLISHED,
    SKIPPED,
    REJECTED,
}

internal fun interface BrokerStartupActivitySink {
    fun publish(activity: BrokerStartupActivity): BrokerStartupActivityPublication

    data object Disabled : BrokerStartupActivitySink {
        override fun publish(activity: BrokerStartupActivity): BrokerStartupActivityPublication =
            BrokerStartupActivityPublication.SKIPPED
    }
}

/** Synchronized structured broker-startup progress for the launchd service log. */
internal class JsonLineBrokerStartupActivitySink(
    private val output: PrintStream,
) : BrokerStartupActivitySink {
    @Synchronized
    override fun publish(activity: BrokerStartupActivity): BrokerStartupActivityPublication {
        val document = buildJsonObject {
            put("component", "kast-broker")
            put("event", "broker-startup-stage")
            put("stage", activity.stage.wireName())
            put(
                "outcome",
                when (activity) {
                    is BrokerStartupActivity.Started -> "started"
                    is BrokerStartupActivity.Completed -> "completed"
                    is BrokerStartupActivity.Rejected -> "rejected"
                },
            )
            if (activity is BrokerStartupActivity.Rejected) {
                put("reason", activity.rejection.wireName())
            }
        }
        output.println(document.toString())
        return if (output.checkError()) {
            BrokerStartupActivityPublication.REJECTED
        } else {
            BrokerStartupActivityPublication.PUBLISHED
        }
    }
}

/** Best-effort effect adapter: telemetry cannot change broker startup semantics. */
internal class BrokerStartupActivityPublisher(
    private val sink: BrokerStartupActivitySink,
) {
    fun started(stage: BrokerStartupStage) = publish(BrokerStartupActivity.Started(stage))

    fun completed(stage: BrokerStartupStage) = publish(BrokerStartupActivity.Completed(stage))

    fun rejected(
        stage: BrokerStartupStage,
        rejection: BrokerStartupRejection,
    ) = publish(BrokerStartupActivity.Rejected(stage, rejection))

    private fun publish(activity: BrokerStartupActivity) {
        try {
            sink.publish(activity)
        } catch (_: RuntimeException) {
            // Telemetry is deliberately unable to alter broker startup.
        }
    }
}

private fun BrokerStartupStage.wireName(): String = name.lowercase().replace('_', '-')

private fun BrokerStartupRejection.wireName(): String = when (this) {
    is BrokerStartupRejection.Server -> failure.name.lowercase().replace('_', '-')
    is BrokerStartupRejection.CodexQualification ->
        "codex-${failure.name.lowercase().replace('_', '-')}"
}
