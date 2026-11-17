package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Control effect boundaries whose repetition decides warm-call latency. */
enum class ControlStage {
    QUERY_CHILD_LAUNCH,
    PAYLOAD_VERIFICATION,
    COORDINATOR_CONNECTION,
    COORDINATOR_START,
    COORDINATOR_ADMISSION,
    COORDINATED_INVOCATION,
    WORKSPACE_ADMISSION,
    WORKSPACE_PREPARATION,
    SEMANTIC_EXECUTION,
    END_TO_END,
}

enum class ControlStageOutcome {
    COMPLETED,
    REJECTED,
    REUSED,
    CREATED,
}

/**
 * One bounded stage record. It carries no argument, path, environment, request, or source payload. A coordinator
 * generation identifies the retained owner and is not a secret.
 */
@Serializable
data class ControlStageEvent(
    val stage: ControlStage,
    val outcome: ControlStageOutcome,
    val elapsedNanos: Long,
    val pid: Long,
    val bytes: Long? = null,
    val generation: String? = null,
    @Required val event: String = "kast_control_stage",
)

/**
 * Process-entry stage evidence on stderr. `KAST_DEBUG=1` in the process environment enables it; every other value keeps
 * it disabled, so ordinary stderr is unchanged.
 */
object ControlStageTrace {
    @Volatile private var enabled = false

    fun configure(environment: Map<String, String>) {
        enabled = environment["KAST_DEBUG"] == "1"
    }

    fun record(
        stage: ControlStage,
        outcome: ControlStageOutcome,
        startedNanos: Long,
        bytes: Long? = null,
        generation: String? = null,
    ) {
        if (!enabled) return
        val event =
            ControlStageEvent(
                stage,
                outcome,
                System.nanoTime() - startedNanos,
                ProcessHandle.current().pid(),
                bytes,
                generation,
            )
        System.err.println(traceJson.encodeToString(ControlStageEvent.serializer(), event))
    }

    inline fun <T> observe(stage: ControlStage, outcome: (T) -> ControlStageOutcome, action: () -> T): T {
        val started = System.nanoTime()
        val value = action()
        record(stage, outcome(value), started)
        return value
    }
}

private val traceJson = Json { encodeDefaults = true }
