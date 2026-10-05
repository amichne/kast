package io.github.amichne.kast.appserver.ide

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/** Effect-boundary stages, with no workspace paths, requests, or peer payloads in recordings. */
internal enum class ExistingIdeStage {
    LIFECYCLE_PREPARATION,
    LIFECYCLE_INSPECTION,
    DESCRIPTOR_ADMISSION,
    STATUS_EXCHANGE,
    OPERATION_EXCHANGE,
}

internal enum class ExistingIdeStageOutcome {
    RETURNED,
    THREW,
}

@Name("io.github.amichne.kast.ExistingIdeStage")
@Label("Kast existing IDE stage")
@Category("Kast")
@StackTrace(false)
internal class ExistingIdeStageEvent : Event() {
    @Label("Stage") var stage: String = ""
    @Label("Outcome") var outcome: String = ""
}

internal inline fun <Value> observeExistingIdeStage(stage: ExistingIdeStage, crossinline action: () -> Value): Value {
    if (!FlightRecorder.isInitialized()) return action()
    val event = ExistingIdeStageEvent()
    if (!event.isEnabled) return action()
    event.stage = stage.name
    event.begin()
    var outcome = ExistingIdeStageOutcome.THREW
    try {
        return action().also { outcome = ExistingIdeStageOutcome.RETURNED }
    } finally {
        event.outcome = outcome.name
        event.end()
        event.commit()
    }
}
