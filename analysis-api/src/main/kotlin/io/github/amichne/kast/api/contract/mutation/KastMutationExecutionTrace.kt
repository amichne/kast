package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
data class KastMutationExecutionTrace(
    val enteredStages: List<KastMutationProgressStage> = emptyList(),
    val editApplicationState: KastMutationEditApplicationState = KastMutationEditApplicationState.NOT_STARTED,
) {
    init {
        require(enteredStages.distinct() == enteredStages) { "Mutation progress stages must not repeat" }
        require(enteredStages.zipWithNext().all { (left, right) -> left.ordinal < right.ordinal }) {
            "Mutation progress stages must follow lifecycle order"
        }
        val enteredEditApplication = KastMutationProgressStage.EDIT_APPLICATION in enteredStages
        require(enteredEditApplication == (editApplicationState != KastMutationEditApplicationState.NOT_STARTED)) {
            "Mutation edit state must agree with entered progress stages"
        }
    }

    val currentStage: KastMutationProgressStage?
        get() = enteredStages.lastOrNull()

    fun entering(stage: KastMutationProgressStage): KastMutationExecutionTrace {
        val previousStage = currentStage
        require(stage !in enteredStages) { "Mutation progress stage $stage was already entered" }
        require(previousStage == null || previousStage.ordinal < stage.ordinal) {
            "Mutation progress stage $stage cannot follow $previousStage"
        }
        return copy(
            enteredStages = enteredStages + stage,
            editApplicationState = if (stage == KastMutationProgressStage.EDIT_APPLICATION) {
                KastMutationEditApplicationState.STARTED
            } else {
                editApplicationState
            },
        )
    }

    fun editApplicationCompleted(): KastMutationExecutionTrace {
        require(editApplicationState == KastMutationEditApplicationState.STARTED) {
            "Mutation edit application can complete only after it starts"
        }
        return copy(editApplicationState = KastMutationEditApplicationState.COMPLETED)
    }
}
