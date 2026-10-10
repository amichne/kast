package io.github.amichne.kast.runtime.hosted.workspace

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Detached task transitions: import application and matching native task end are separate obligations. */
internal class WorkspaceRefreshImportCompletion<TaskId : Any>(
    private val complete: (WorkspaceRefreshEffectResult) -> Unit,
    private val observe: (WorkspaceRefreshImportObservation) -> Unit,
) {
    private sealed interface Outcome {
        data object Awaiting : Outcome

        data class Observed(val result: WorkspaceRefreshEffectResult) : Outcome
    }

    private sealed interface State<out Id> {
        data class Waiting(val outcome: Outcome = Outcome.Awaiting) : State<Nothing>

        data class Running<Id>(val id: Id, val outcome: Outcome = Outcome.Awaiting) : State<Id>

        data class Ended<Id>(val id: Id) : State<Id>

        data class Cancelling<Id>(val id: Id) : State<Id>

        data object Finished : State<Nothing>
    }

    private enum class Retirement {
        ACTIVE,
        RETIRED,
    }

    private var state: State<TaskId> = State.Waiting()
    private var retirement = Retirement.ACTIVE

    @Synchronized
    fun started(id: TaskId) {
        val current = state
        if (current is State.Waiting) state = State.Running(id, current.outcome)
    }

    @Synchronized
    fun cancelled(id: TaskId) {
        val current = state
        if (current is State.Running && current.id == id) state = State.Cancelling(id)
    }

    @Synchronized
    fun ended(id: TaskId) {
        when (val current = state) {
            is State.Running ->
                if (current.id == id) {
                    when (val outcome = current.outcome) {
                        Outcome.Awaiting -> state = State.Ended(id)
                        is Outcome.Observed -> publish(outcome.result)
                    }
                }
            is State.Cancelling -> if (current.id == id) publish(WorkspaceRefreshEffectResult.CANCELLED)
            is State.Waiting,
            is State.Ended,
            State.Finished -> Unit
        }
    }

    @Synchronized
    fun finished(result: WorkspaceRefreshEffectResult) {
        when (val current = state) {
            is State.Waiting -> state = State.Waiting(merge(current.outcome, result))
            is State.Running -> state = current.copy(outcome = merge(current.outcome, result))
            is State.Ended -> publish(result)
            is State.Cancelling,
            State.Finished -> Unit
        }
    }

    private fun merge(previous: Outcome, result: WorkspaceRefreshEffectResult): Outcome =
        when (previous) {
            Outcome.Awaiting -> Outcome.Observed(result)
            is Outcome.Observed ->
                if (previous.result == result) previous else Outcome.Observed(WorkspaceRefreshEffectResult.FAILED)
        }

    /** Retirement never grants an execution permit to the disposed incarnation. */
    @Synchronized
    fun retired() {
        if (state == State.Finished || retirement == Retirement.RETIRED) return
        retirement = Retirement.RETIRED
        observe(WorkspaceRefreshImportObservation(outcome = WorkspaceRefreshEffectResult.RETIRED))
        complete(WorkspaceRefreshEffectResult.RETIRED)
    }

    private fun publish(result: WorkspaceRefreshEffectResult) {
        if (state == State.Finished) return
        state = State.Finished
        observe(WorkspaceRefreshImportObservation(outcome = result))
        complete(result)
    }
}

@Serializable
internal data class WorkspaceRefreshImportObservation(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stage: WorkspaceRefreshImportStage = WorkspaceRefreshImportStage.IMPORT_CALLBACK,
    val outcome: WorkspaceRefreshEffectResult,
)

@Serializable
internal enum class WorkspaceRefreshImportStage {
    IMPORT_CALLBACK
}
