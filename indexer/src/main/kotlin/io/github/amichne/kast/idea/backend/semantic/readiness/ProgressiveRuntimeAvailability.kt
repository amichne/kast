package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.protocol.ConflictException

/** Current-only functionality that may become usable before persisted evidence finishes. */
internal enum class CurrentRuntimeLane {
    COMPILER,
    WORKSPACE_FILES,
}

/**
 * Construction transition: `Long -> CurrentRuntimeRevision`.
 *
 * Construction is owned by [ProgressiveRuntimeAvailability], which admits only positive,
 * monotonically increasing revisions. Raw extraction is permitted only at runtime-status and
 * trace serialization boundaries.
 */
@JvmInline
internal value class CurrentRuntimeRevision private constructor(val value: Long) {
    internal fun next(): CurrentRuntimeRevision = CurrentRuntimeRevision(Math.addExact(value, 1L))

    companion object {
        internal fun first(): CurrentRuntimeRevision = CurrentRuntimeRevision(1L)
    }
}

/** One compiler/model epoch shared by the current-only lanes. */
internal data class CurrentRuntimeEpoch(
    val revision: CurrentRuntimeRevision,
)

internal enum class CurrentRuntimeBlocker {
    RUNTIME_FAILED,
    PROJECT_DISPOSED,
    DUMB_MODE,
}

internal sealed interface CurrentRuntimeHostState {
    data object Current : CurrentRuntimeHostState

    data class Unavailable(
        val blocker: CurrentRuntimeBlocker,
    ) : CurrentRuntimeHostState
}

internal sealed interface CurrentRuntimeLaneState {
    data object Building : CurrentRuntimeLaneState

    data class Available(
        val epoch: CurrentRuntimeEpoch,
    ) : CurrentRuntimeLaneState

    data class Blocked(
        val blocker: CurrentRuntimeBlocker,
    ) : CurrentRuntimeLaneState
}

internal sealed interface CurrentRuntimeInvalidation {
    data class Invalidated(
        val epoch: CurrentRuntimeEpoch,
    ) : CurrentRuntimeInvalidation

    data class AlreadyUnavailable(
        val state: CurrentRuntimeLaneState,
    ) : CurrentRuntimeInvalidation
}

internal sealed interface CurrentRuntimeExecutionFailure {
    data class Unavailable(
        val lane: CurrentRuntimeLane,
        val state: CurrentRuntimeLaneState,
    ) : CurrentRuntimeExecutionFailure

    data class Invalidated(
        val lane: CurrentRuntimeLane,
        val admitted: CurrentRuntimeEpoch,
        val observed: CurrentRuntimeLaneState,
    ) : CurrentRuntimeExecutionFailure
}

internal sealed interface CurrentRuntimeExecution<out T> {
    data class Completed<T>(
        val epoch: CurrentRuntimeEpoch,
        val payload: T,
    ) : CurrentRuntimeExecution<T>

    data class Rejected(
        val failure: CurrentRuntimeExecutionFailure,
    ) : CurrentRuntimeExecution<Nothing>
}

/**
 * Owns current compiler and workspace-model availability independently of persisted index lanes.
 *
 * Operations acquire one immutable epoch and revalidate it after computing their detached result.
 * Invalidation therefore makes an in-flight result unservable without waiting for that operation.
 */
internal class ProgressiveRuntimeAvailability private constructor(
    initialState: CurrentRuntimeLaneState,
    initialRevision: CurrentRuntimeRevision?,
) {
    constructor() : this(CurrentRuntimeLaneState.Building, null)

    private val monitor = Any()
    private var state: CurrentRuntimeLaneState = initialState
    private var latestRevision: CurrentRuntimeRevision? = initialRevision

    fun observe(): CurrentRuntimeLaneState = synchronized(monitor) { state }

    /**
     * Proof transition: `CurrentRuntimeLaneState -> CurrentRuntimeEpoch`.
     *
     * Establishes a new positive revision at which the imported model and compiler are current.
     * The returned epoch is the only capability accepted by current-lane post-execution
     * revalidation. Raw revision extraction is permitted only at status/trace boundaries.
     */
    fun publishCurrent(): CurrentRuntimeEpoch = synchronized(monitor) {
        val revision = latestRevision?.next() ?: CurrentRuntimeRevision.first()
        val epoch = CurrentRuntimeEpoch(revision)
        latestRevision = revision
        state = CurrentRuntimeLaneState.Available(epoch)
        epoch
    }

    fun invalidate(): CurrentRuntimeInvalidation = synchronized(monitor) {
        when (val observed = state) {
            is CurrentRuntimeLaneState.Available -> {
                state = CurrentRuntimeLaneState.Building
                CurrentRuntimeInvalidation.Invalidated(observed.epoch)
            }
            CurrentRuntimeLaneState.Building,
            is CurrentRuntimeLaneState.Blocked,
                -> CurrentRuntimeInvalidation.AlreadyUnavailable(observed)
        }
    }

    fun block(blocker: CurrentRuntimeBlocker): CurrentRuntimeLaneState.Blocked = synchronized(monitor) {
        CurrentRuntimeLaneState.Blocked(blocker).also { state = it }
    }

    /**
     * Proof transition:
     * `(ProgressiveRuntimeAvailability, CurrentRuntimeLane, suspend () -> T) -> CurrentRuntimeExecution<T>`.
     *
     * A [CurrentRuntimeExecution.Completed] result proves the operation began and ended in the
     * same available compiler/model epoch. Expected unavailability and concurrent invalidation are
     * closed [CurrentRuntimeExecutionFailure] data. Only the backend protocol boundary may unwrap
     * a completed payload or translate rejection to a wire error.
     */
    suspend fun <T> execute(
        lane: CurrentRuntimeLane,
        operation: suspend (CurrentRuntimeEpoch) -> T,
    ): CurrentRuntimeExecution<T> {
        val admitted = synchronized(monitor) {
            when (val observed = state) {
                is CurrentRuntimeLaneState.Available -> observed.epoch
                CurrentRuntimeLaneState.Building,
                is CurrentRuntimeLaneState.Blocked,
                    -> return CurrentRuntimeExecution.Rejected(
                        CurrentRuntimeExecutionFailure.Unavailable(lane, observed),
                    )
            }
        }
        val payload = operation(admitted)
        return synchronized(monitor) {
            val observed = state
            if (observed is CurrentRuntimeLaneState.Available && observed.epoch == admitted) {
                CurrentRuntimeExecution.Completed(admitted, payload)
            } else {
                CurrentRuntimeExecution.Rejected(
                    CurrentRuntimeExecutionFailure.Invalidated(lane, admitted, observed),
                )
            }
        }
    }

    companion object {
        fun alreadyCurrent(): ProgressiveRuntimeAvailability {
            val revision = CurrentRuntimeRevision.first()
            return ProgressiveRuntimeAvailability(
                initialState = CurrentRuntimeLaneState.Available(CurrentRuntimeEpoch(revision)),
                initialRevision = revision,
            )
        }
    }
}

/** Backend boundary that unwraps only revalidated current-lane executions. */
internal class CurrentRuntimeGate(
    private val availability: ProgressiveRuntimeAvailability,
    private val hostState: () -> CurrentRuntimeHostState = { CurrentRuntimeHostState.Current },
) {
    suspend fun <T> compiler(operation: suspend (CurrentRuntimeEpoch) -> T): T =
        execute(CurrentRuntimeLane.COMPILER, operation)

    suspend fun <T> workspaceFiles(operation: suspend (CurrentRuntimeEpoch) -> T): T =
        execute(CurrentRuntimeLane.WORKSPACE_FILES, operation)

    /**
     * Proof transition:
     * `(ProgressiveRuntimeAvailability, CurrentRuntimeLane, suspend (CurrentRuntimeEpoch) -> T) -> T`.
     *
     * Extracts a payload only from [CurrentRuntimeExecution.Completed], whose epoch was revalidated
     * after execution. Closed [CurrentRuntimeExecutionFailure] values are translated to the legacy
     * protocol exception only at this backend boundary.
     */
    private suspend fun <T> execute(
        lane: CurrentRuntimeLane,
        operation: suspend (CurrentRuntimeEpoch) -> T,
    ): T {
        requireCurrentHost(lane)
        return when (val execution = availability.execute(lane, operation)) {
            is CurrentRuntimeExecution.Completed -> {
                requireCurrentHost(lane)
                execution.payload
            }
            is CurrentRuntimeExecution.Rejected -> throw ConflictException(
                message = when (execution.failure) {
                    is CurrentRuntimeExecutionFailure.Unavailable ->
                        "${lane.name.lowercase()} is not available in the current workspace epoch"
                    is CurrentRuntimeExecutionFailure.Invalidated ->
                        "Workspace moved during the ${lane.name.lowercase()} operation; retry against the next current epoch"
                },
                details = mapOf(
                    "currentRuntimeLane" to lane.name,
                    "currentRuntimeFailure" to execution.failure.toString(),
                ),
            )
        }
    }

    private fun requireCurrentHost(lane: CurrentRuntimeLane) {
        val observed = hostState()
        if (observed is CurrentRuntimeHostState.Unavailable) {
            throw ConflictException(
                message = "${lane.name.lowercase()} is not available in the current IntelliJ state",
                details = mapOf(
                    "currentRuntimeLane" to lane.name,
                    "currentRuntimeBlocker" to observed.blocker.name,
                ),
            )
        }
    }
}
