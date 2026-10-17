package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspacePreparationBudget
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadline
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import io.github.amichne.kast.protocol.contract.WorkspacePreparationTiming
import kotlinx.serialization.Serializable

@Serializable
internal enum class NativePreparationOutcome {
    STARTED,
    STAGE_CHANGED,
    COMPLETE,
    BLOCKED,
}

@Serializable
internal data class NativePreparationObservation(
    val requestId: String,
    val outcome: NativePreparationOutcome,
    val preparation: WorkspacePreparationTiming,
    val failure: IdeLifecycleFailure? = null,
)

/** One native operation retains its parent allowance across opening, import and final admission. */
internal class NativeWorkspacePreparation
private constructor(
    val requestId: String,
    val deadline: WorkspacePreparationDeadline,
    val nanoTime: () -> Long,
    private val observe: (NativePreparationObservation) -> Unit,
) {
    private var stage = IdeLifecycleStage.OPENING

    fun enter(next: IdeLifecycleStage) {
        if (stage == next) return
        stage = next
        record(NativePreparationOutcome.STAGE_CHANGED)
    }

    fun finish(result: IdeLifecycleResult): IdeLifecycleResult {
        val failure = (result as? IdeLifecycleResult.Blocked)?.reason
        val timing = (result as? IdeLifecycleResult.Blocked)?.preparation ?: timing()
        record(
            if (failure == null) NativePreparationOutcome.COMPLETE else NativePreparationOutcome.BLOCKED,
            failure,
            timing,
        )
        return if (result is IdeLifecycleResult.Blocked) result.copy(preparation = timing) else result
    }

    fun timing(): WorkspacePreparationTiming =
        deadline.timing(stage, WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION, nanoTime())

    fun remaining():
        Refinement<ElapsedTimeLimitMillis, io.github.amichne.kast.protocol.contract.WorkspacePreparationExpiry> =
        deadline.remaining(nanoTime())

    private fun record(
        outcome: NativePreparationOutcome,
        failure: IdeLifecycleFailure? = null,
        preparation: WorkspacePreparationTiming = timing(),
    ) = observe(NativePreparationObservation(requestId, outcome, preparation, failure))

    companion object {
        fun admit(
            requestId: String,
            allowanceMillis: Long,
            nanoTime: () -> Long = System::nanoTime,
            observe: (NativePreparationObservation) -> Unit = {},
            admittedAtNanos: Long = nanoTime(),
        ): Refinement<NativeWorkspacePreparation, IdeLifecycleFailure> =
            when (
                val limit = ElapsedTimeLimitMillis.parse(minOf(allowanceMillis, WorkspacePreparationBudget.limit.value))
            ) {
                is Refinement.Rejected -> Refinement.Rejected(IdeLifecycleFailure.DEADLINE_EXCEEDED)
                is Refinement.Refined ->
                    Refinement.Refined(
                        NativeWorkspacePreparation(
                                requestId,
                                WorkspacePreparationDeadline.start(limit.value, admittedAtNanos),
                                nanoTime,
                                observe,
                            )
                            .also { it.record(NativePreparationOutcome.STARTED) }
                    )
            }
    }
}

/** The application captures admission before dispatch; an expired queued operation cannot start native effects. */
internal suspend fun prepareNativeWorkspace(
    requestId: String,
    allowanceMillis: Long,
    admittedAtNanos: Long,
    observe: (NativePreparationObservation) -> Unit = {},
    nanoTime: () -> Long = System::nanoTime,
    action: suspend (NativeWorkspacePreparation) -> IdeLifecycleResult,
): IdeLifecycleResult =
    when (
        val admitted = NativeWorkspacePreparation.admit(requestId, allowanceMillis, nanoTime, observe, admittedAtNanos)
    ) {
        is Refinement.Rejected -> IdeLifecycleResult.Blocked(admitted.failure)
        is Refinement.Refined -> {
            val operation = admitted.value
            val result =
                when (operation.remaining()) {
                    is Refinement.Rejected -> IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
                    is Refinement.Refined -> action(operation)
                }
            operation.finish(result)
        }
    }
