package io.github.amichne.kast.cli.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class KotterDemoSessionController private constructor(
    private val scope: CoroutineScope,
    private val scenario: KotterDemoSessionScenario,
) {
    private val sessionState = MutableStateFlow(scenario.initialState())
    private var activeScenarioJob: Job? = null

    fun start(operationId: String = sessionState.value.activeOperationId) = restartWith(operationId)

    fun switchOperation(operationId: String) {
        restartWith(operationId)
    }

    fun replay() {
        restartWith(sessionState.value.activeOperationId)
    }

    fun states(): StateFlow<KotterDemoSessionState> = sessionState.asStateFlow()

    fun snapshot(): KotterDemoSessionState = sessionState.value

    private fun restartWith(operationId: String) {
        activeScenarioJob?.cancel()
        val operation = scenario.operation(operationId)
        sessionState.value = scenario.initialStateFor(operation.id)
        activeScenarioJob = scope.launch {
            var previousAtMillis = 0L
            operation.events.forEach { event ->
                delay(event.atMillis - previousAtMillis)
                previousAtMillis = event.atMillis
                when (event) {
                    is KotterDemoScenarioEvent.Line -> appendLiveLine(event.text)
                    is KotterDemoScenarioEvent.Milestone -> advancePast(event.phaseId, operation)
                }
            }
        }
    }

    private fun appendLiveLine(text: String) {
        sessionState.update { state ->
            state.copy(liveLines = state.liveLines + text)
        }
    }

    private fun advancePast(phaseId: String, operation: KotterDemoOperationScenario) {
        sessionState.update { state ->
            val updatedPhaseStates = state.phaseStates.toMutableMap().apply {
                put(phaseId, KotterDemoPhaseStatus.COMPLETE)
                operation.nextPhaseAfter(phaseId)?.let { nextPhaseId ->
                    put(nextPhaseId, KotterDemoPhaseStatus.ACTIVE)
                }
            }
            state.copy(
                phaseStates = updatedPhaseStates.toMap(),
                asideLines = state.asideLines + state.liveLines,
                liveLines = emptyList(),
            )
        }
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            scenario: KotterDemoSessionScenario,
        ): KotterDemoSessionController = KotterDemoSessionController(scope, scenario)

        fun createForTest(
            scope: CoroutineScope,
            scenario: KotterDemoSessionScenario,
        ): KotterDemoSessionController = create(scope, scenario)

        fun createForTest(
            scope: CoroutineScope,
            contract: Map<String, Any>,
        ): KotterDemoSessionController = createForTest(scope, KotterDemoSessionScenario.fromTestContract(contract))
    }
}
