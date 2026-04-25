package io.github.amichne.kast.cli.demo

import kotlinx.serialization.Serializable

internal enum class KotterDemoPhaseStatus {
    PENDING,
    ACTIVE,
    COMPLETE,
}

/**
 * A transcript line carrying semantic tone for colored rendering.
 * Shared across the scenario event model, session state, and screen model
 * so tone flows from data creation to display without conversion.
 */
@Serializable
internal data class KotterDemoTranscriptLine(
    val text: String,
    val tone: KotterDemoStreamTone = KotterDemoStreamTone.DETAIL,
    val codePreview: String? = null,
)

internal sealed interface KotterDemoScenarioEvent {
    val atMillis: Long
    val phaseId: String

    data class Line(
        override val atMillis: Long,
        override val phaseId: String,
        val text: String,
        val tone: KotterDemoStreamTone = KotterDemoStreamTone.DETAIL,
        val codePreview: String? = null,
    ) : KotterDemoScenarioEvent

    data class Milestone(
        override val atMillis: Long,
        override val phaseId: String,
    ) : KotterDemoScenarioEvent
}

internal data class KotterDemoOperationScenario(
    val id: String,
    val phases: List<String>,
    val events: List<KotterDemoScenarioEvent>,
) {
    init {
        require(id.isNotBlank()) { "Operation id must not be blank." }
        require(phases.isNotEmpty()) { "Operation $id must declare at least one phase." }
        require(phases.distinct().size == phases.size) { "Operation $id phase ids must be unique." }
        require(events.all { it.atMillis >= 0 }) { "Operation $id events must use non-negative timestamps." }
        require(events.zipWithNext().all { (left, right) -> left.atMillis <= right.atMillis }) {
            "Operation $id events must be sorted by atMillis."
        }

        val phaseIds = phases.toSet()
        require(events.all { it.phaseId in phaseIds }) { "Operation $id event phases must belong to the declared phase list." }
    }

    fun initialPhaseStates(): Map<String, KotterDemoPhaseStatus> =
        phases.mapIndexed { index, phaseId ->
            phaseId to if (index == 0) KotterDemoPhaseStatus.ACTIVE else KotterDemoPhaseStatus.PENDING
        }.toMap()

    fun nextPhaseAfter(phaseId: String): String? =
        phases.indexOf(phaseId)
            .takeIf { it >= 0 }
            ?.let { index -> phases.getOrNull(index + 1) }
}

internal data class KotterDemoSessionScenario(
    val initialOperationId: String,
    val operations: List<KotterDemoOperationScenario>,
) {
    private val operationsById: Map<String, KotterDemoOperationScenario> = operations.associateBy(KotterDemoOperationScenario::id)

    init {
        require(operations.isNotEmpty()) { "Session scenario must declare at least one operation." }
        require(operationsById.size == operations.size) { "Session scenario operation ids must be unique." }
        require(initialOperationId in operationsById) { "Initial operation $initialOperationId is not declared." }
    }

    fun operation(operationId: String): KotterDemoOperationScenario =
        operationsById[operationId] ?: error("Unknown demo operation: $operationId")

    fun initialStateFor(operationId: String): KotterDemoSessionState {
        val operation = operation(operationId)
        return KotterDemoSessionState(
            activeOperationId = operation.id,
            phaseStates = operation.initialPhaseStates(),
            liveLines = emptyList(),
            asideLines = emptyList(),
        )
    }

    fun initialState(): KotterDemoSessionState = initialStateFor(initialOperationId)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromTestContract(contract: Map<String, Any>): KotterDemoSessionScenario {
            val operations = (contract["operations"] as? List<*>)
                ?.map { rawOperation ->
                    val operation = rawOperation as? Map<*, *>
                        ?: error("Operation contract entries must be maps.")
                    KotterDemoOperationScenario(
                        id = operation.string("id"),
                        phases = operation.stringList("phases"),
                        events = operation.eventList("events"),
                    )
                }
                .orEmpty()

            return KotterDemoSessionScenario(
                initialOperationId = contract.string("initialOperationId"),
                operations = operations,
            )
        }

        private fun Map<*, *>.string(key: String): String =
            this[key]?.toString() ?: error("Missing contract field: $key")

        private fun Map<*, *>.stringList(key: String): List<String> =
            (this[key] as? List<*>)?.map { it?.toString() ?: error("Null value in $key") }
                ?: error("Missing list contract field: $key")

        private fun Map<*, *>.eventList(key: String): List<KotterDemoScenarioEvent> =
            (this[key] as? List<*>)?.map { rawEvent ->
                val event = rawEvent as? Map<*, *>
                    ?: error("Scenario event entries must be maps.")
                when (event.string("type")) {
                    "line" -> KotterDemoScenarioEvent.Line(
                        atMillis = event.long("atMillis"),
                        phaseId = event.string("phase"),
                        text = event.string("text"),
                        tone = event.toneOrDefault("tone"),
                        codePreview = event.optionalString("codePreview"),
                    )
                    "milestone" -> KotterDemoScenarioEvent.Milestone(
                        atMillis = event.long("atMillis"),
                        phaseId = event.string("phase"),
                    )
                    else -> error("Unknown scenario event type: ${event.string("type")}")
                }
            } ?: error("Missing event list contract field: $key")

        private fun Map<*, *>.long(key: String): Long =
            (this[key] as? Number)?.toLong() ?: error("Missing numeric contract field: $key")

        private fun Map<*, *>.toneOrDefault(key: String): KotterDemoStreamTone =
            (this[key] as? String)?.let { name ->
                runCatching { KotterDemoStreamTone.valueOf(name.uppercase()) }.getOrNull()
            } ?: KotterDemoStreamTone.DETAIL

        private fun Map<*, *>.optionalString(key: String): String? =
            this[key]?.toString()
    }
}

internal data class KotterDemoSessionState(
    val activeOperationId: String,
    val phaseStates: Map<String, KotterDemoPhaseStatus>,
    val liveLines: List<KotterDemoTranscriptLine>,
    val asideLines: List<KotterDemoTranscriptLine>,
) {
    fun allLines(): List<KotterDemoTranscriptLine> = asideLines + liveLines

    fun liveTexts(): List<String> = liveLines.map { it.text }
    fun asideTexts(): List<String> = asideLines.map { it.text }
    fun allTexts(): List<String> = allLines().map { it.text }
}
