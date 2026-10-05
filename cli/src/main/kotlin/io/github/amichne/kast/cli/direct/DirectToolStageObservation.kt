package io.github.amichne.kast.cli.direct

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/** Bounded startup and invocation witnesses; recordings contain no request or source payload. */
internal enum class DirectToolStage {
    MAIN,
    SESSION_COMPOSITION,
    HOSTED_CATALOG_COMPOSITION,
    ROOT_BINDING,
    WORKSPACE_CLIENT_COMPOSITION,
    CAPABILITIES_COMPOSITION,
    CHANGE_TOOL_COMPOSITION,
    DIRECT_CATALOG_PROJECTION,
    BRIDGE_COMPOSITION,
    REQUEST_ADMISSION,
    WORKSPACE_DISCOVERY,
    PREPARATION_START,
    INVOCATION,
    RESULT_PROJECTION,
    OUTPUT_SERIALIZATION,
}

internal enum class DirectToolStageOutcome {
    RETURNED,
    THREW,
}

@Name("io.github.amichne.kast.DirectToolStage")
@Label("Kast direct tool stage")
@Category("Kast")
@StackTrace(false)
internal class DirectToolStageEvent : Event() {
    @Label("Stage") var stage: String = ""
    @Label("Outcome") var outcome: String = ""
}

/** Return means completion of this boundary, including a typed product rejection. */
internal inline fun <Value> observeDirectToolStage(stage: DirectToolStage, crossinline action: () -> Value): Value {
    if (!FlightRecorder.isInitialized()) return action()
    val event = DirectToolStageEvent()
    if (!event.isEnabled) return action()
    event.stage = stage.name
    event.begin()
    var outcome = DirectToolStageOutcome.THREW
    try {
        return action().also { outcome = DirectToolStageOutcome.RETURNED }
    } finally {
        event.outcome = outcome.name
        event.end()
        event.commit()
    }
}
