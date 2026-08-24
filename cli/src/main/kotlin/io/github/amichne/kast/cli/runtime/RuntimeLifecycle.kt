package io.github.amichne.kast.cli

enum class RuntimeLifecycleState { RUNNING, STOPPED, STALE }

sealed interface RuntimeProcessTermination {
    data object Terminated : RuntimeProcessTermination
    data object Rejected : RuntimeProcessTermination
    data object Interrupted : RuntimeProcessTermination
}

fun interface RuntimeOwnedProcess {
    /** Requests termination of one process already proven to own the exact endpoint. */
    fun terminate(): RuntimeProcessTermination
}

sealed interface RuntimeProcessObservation {
    data object Absent : RuntimeProcessObservation

    data class Owned(
        val process: RuntimeOwnedProcess,
    ) : RuntimeProcessObservation

    data object Ambiguous : RuntimeProcessObservation
}

fun interface RuntimeProcessAuthority {
    /**
     * Proof transition: `RuntimeEndpoint -> RuntimeProcessObservation`.
     *
     * Establishes zero or one same-user process whose command carries the endpoint's exact root,
     * socket, and runtime identity. Ambiguous or inaccessible process state fails closed. Raw
     * process arguments remain inside the process-observation adapter.
     */
    fun observe(endpoint: RuntimeEndpoint): RuntimeProcessObservation
}

enum class RuntimeStatusFailure { ARTIFACT_OBSERVATION_FAILED }

sealed interface RuntimeStatusResult {
    data class Observed(
        val state: RuntimeLifecycleState,
    ) : RuntimeStatusResult

    data class Rejected(
        val failure: RuntimeStatusFailure,
    ) : RuntimeStatusResult
}

enum class RuntimeStopFailure {
    ACTIVE_ENDPOINT,
    PROCESS_AMBIGUOUS,
    PROCESS_TERMINATION_FAILED,
    ENDPOINT_MARKER_RETIREMENT_FAILED,
    INTERRUPTED,
}

sealed interface RuntimeStopResult {
    data class Stopped(
        val removed: Set<RuntimeEndpointMarker> = emptySet(),
    ) : RuntimeStopResult

    data class Rejected(
        val failure: RuntimeStopFailure,
    ) : RuntimeStopResult
}

enum class RuntimeCleanFailure {
    ACTIVE_ENDPOINT,
    PROCESS_AMBIGUOUS,
    ARTIFACT_CLEAN_FAILED,
    INTERRUPTED,
}

sealed interface RuntimeCleanResult {
    data class Cleaned(
        val removed: Set<RuntimeEndpointArtifact>,
    ) : RuntimeCleanResult

    data class Rejected(
        val failure: RuntimeCleanFailure,
    ) : RuntimeCleanResult
}

interface RuntimeLifecycleController {
    /** Observes the exact endpoint without starting or acquiring a runtime. */
    fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult

    /** Stops only a process proven to own the exact endpoint. */
    fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult

    /** Removes only inactive artifacts derived from the exact endpoint. */
    fun clean(endpoint: RuntimeEndpoint): RuntimeCleanResult
}

/** Minimal exact-root lifecycle coordination over existing process and UDS boundaries. */
class ExactRootRuntimeLifecycle internal constructor(
    private val endpointProbe: RuntimeEndpointProbe,
    private val processAuthority: RuntimeProcessAuthority,
    private val artifacts: RuntimeEndpointArtifacts = PosixRuntimeEndpointArtifacts,
) : RuntimeLifecycleController {
    constructor() : this(JdkUnixDomainEndpointProbe, JdkRuntimeProcessAuthority)

    override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult {
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            return RuntimeStatusResult.Observed(RuntimeLifecycleState.RUNNING)
        }
        val state = when (val observation = artifacts.observeMarkers(endpoint)) {
            RuntimeEndpointMarkerObservation.Rejected -> return RuntimeStatusResult.Rejected(
                RuntimeStatusFailure.ARTIFACT_OBSERVATION_FAILED,
            )
            is RuntimeEndpointMarkerObservation.Observed -> if (observation.present.isEmpty()) {
                RuntimeLifecycleState.STOPPED
            } else {
                RuntimeLifecycleState.STALE
            }
        }
        return RuntimeStatusResult.Observed(state)
    }

    override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult =
        when (val observation = processAuthority.observe(endpoint)) {
            RuntimeProcessObservation.Absent -> stoppedAfterObservedAbsence(
                endpoint,
                RuntimeProcessObservation.Absent,
            )
            RuntimeProcessObservation.Ambiguous -> RuntimeStopResult.Rejected(
                RuntimeStopFailure.PROCESS_AMBIGUOUS,
            )
            is RuntimeProcessObservation.Owned -> when (observation.process.terminate()) {
                RuntimeProcessTermination.Terminated -> stoppedAfterTermination(
                    endpoint,
                    RuntimeProcessTermination.Terminated,
                )
                RuntimeProcessTermination.Interrupted -> RuntimeStopResult.Rejected(
                    RuntimeStopFailure.INTERRUPTED,
                )
                RuntimeProcessTermination.Rejected -> RuntimeStopResult.Rejected(
                    RuntimeStopFailure.PROCESS_TERMINATION_FAILED,
                )
            }
        }

    override fun clean(endpoint: RuntimeEndpoint): RuntimeCleanResult {
        val reachability: RuntimeEndpointReachability.Unreachable = when (
            endpointProbe.probe(endpoint)
        ) {
            RuntimeEndpointReachability.Reachable -> return RuntimeCleanResult.Rejected(
                RuntimeCleanFailure.ACTIVE_ENDPOINT,
            )
            RuntimeEndpointReachability.Unreachable -> RuntimeEndpointReachability.Unreachable
        }
        val absence: RuntimeProcessObservation.Absent = when (
            processAuthority.observe(endpoint)
        ) {
            RuntimeProcessObservation.Absent -> RuntimeProcessObservation.Absent
            RuntimeProcessObservation.Ambiguous -> return RuntimeCleanResult.Rejected(
                RuntimeCleanFailure.PROCESS_AMBIGUOUS,
            )
            is RuntimeProcessObservation.Owned -> return RuntimeCleanResult.Rejected(
                RuntimeCleanFailure.ACTIVE_ENDPOINT,
            )
        }
        val inactive = InactiveRuntimeEndpoint.afterObservedAbsence(
            endpoint,
            absence,
            reachability,
        )
        return when (val cleaning = artifacts.clean(inactive)) {
            is RuntimeEndpointArtifactCleaning.Cleaned -> RuntimeCleanResult.Cleaned(
                cleaning.removed,
            )
            RuntimeEndpointArtifactCleaning.Rejected -> RuntimeCleanResult.Rejected(
                RuntimeCleanFailure.ARTIFACT_CLEAN_FAILED,
            )
            RuntimeEndpointArtifactCleaning.Interrupted -> RuntimeCleanResult.Rejected(
                RuntimeCleanFailure.INTERRUPTED,
            )
        }
    }

    /**
     * Proof transition: `RuntimeEndpoint + RuntimeProcessObservation.Absent ->
     * RuntimeStopResult`.
     *
     * Establishes that the already-absent exact process also has an unreachable endpoint before
     * marker retirement. Reachability and marker failures remain closed [RuntimeStopFailure]
     * values.
     */
    private fun stoppedAfterObservedAbsence(
        endpoint: RuntimeEndpoint,
        absence: RuntimeProcessObservation.Absent,
    ): RuntimeStopResult = when (endpointProbe.probe(endpoint)) {
        RuntimeEndpointReachability.Reachable -> RuntimeStopResult.Rejected(
            RuntimeStopFailure.ACTIVE_ENDPOINT,
        )
        RuntimeEndpointReachability.Unreachable -> stoppedAfterRetiringMarkers(
            InactiveRuntimeEndpoint.afterObservedAbsence(
                endpoint,
                absence,
                RuntimeEndpointReachability.Unreachable,
            ),
        )
    }

    /**
     * Proof transition: `RuntimeEndpoint + RuntimeProcessTermination.Terminated ->
     * RuntimeStopResult`.
     *
     * Establishes that the terminated exact process also has an unreachable endpoint before
     * marker retirement. Reachability and marker failures remain closed [RuntimeStopFailure]
     * values.
     */
    private fun stoppedAfterTermination(
        endpoint: RuntimeEndpoint,
        termination: RuntimeProcessTermination.Terminated,
    ): RuntimeStopResult = when (endpointProbe.probe(endpoint)) {
        RuntimeEndpointReachability.Reachable -> RuntimeStopResult.Rejected(
            RuntimeStopFailure.ACTIVE_ENDPOINT,
        )
        RuntimeEndpointReachability.Unreachable -> stoppedAfterRetiringMarkers(
            InactiveRuntimeEndpoint.afterTermination(
                endpoint,
                termination,
                RuntimeEndpointReachability.Unreachable,
            ),
        )
    }

    /**
     * Proof transition: `InactiveRuntimeEndpoint -> RuntimeStopResult.Stopped`.
     *
     * Establishes that the exact socket and descriptor markers are absent without removing
     * persistent runtime state. [RuntimeStopFailure] closes marker retirement rejection and
     * interruption. Raw paths stay inside [RuntimeEndpointArtifacts].
     */
    private fun stoppedAfterRetiringMarkers(
        inactive: InactiveRuntimeEndpoint,
    ): RuntimeStopResult = when (val retirement = artifacts.retireMarkers(inactive)) {
        is RuntimeEndpointMarkerRetirement.Retired -> RuntimeStopResult.Stopped(
            retirement.removed,
        )
        RuntimeEndpointMarkerRetirement.Rejected -> RuntimeStopResult.Rejected(
            RuntimeStopFailure.ENDPOINT_MARKER_RETIREMENT_FAILED,
        )
        RuntimeEndpointMarkerRetirement.Interrupted -> RuntimeStopResult.Rejected(
            RuntimeStopFailure.INTERRUPTED,
        )
    }
}

/** Exact endpoint whose process closure and UDS unreachability have both been proven. */
internal class InactiveRuntimeEndpoint private constructor(
    internal val endpoint: RuntimeEndpoint,
) {
    companion object {
        /**
         * Proof transition: `RuntimeEndpoint + RuntimeProcessObservation.Absent +
         * RuntimeEndpointReachability.Unreachable -> InactiveRuntimeEndpoint`.
         *
         * Establishes that no exact owned process exists and the endpoint is unreachable. Raw
         * endpoint extraction is permitted only by lifecycle filesystem adapters.
         */
        fun afterObservedAbsence(
            endpoint: RuntimeEndpoint,
            absence: RuntimeProcessObservation.Absent,
            reachability: RuntimeEndpointReachability.Unreachable,
        ): InactiveRuntimeEndpoint = when (absence) {
            RuntimeProcessObservation.Absent -> when (reachability) {
                RuntimeEndpointReachability.Unreachable -> InactiveRuntimeEndpoint(endpoint)
            }
        }

        /**
         * Proof transition: `RuntimeEndpoint + RuntimeProcessTermination.Terminated +
         * RuntimeEndpointReachability.Unreachable -> InactiveRuntimeEndpoint`.
         *
         * Establishes that the exact owned process terminated and the endpoint is unreachable. Raw
         * endpoint extraction is permitted only by lifecycle filesystem adapters.
         */
        fun afterTermination(
            endpoint: RuntimeEndpoint,
            termination: RuntimeProcessTermination.Terminated,
            reachability: RuntimeEndpointReachability.Unreachable,
        ): InactiveRuntimeEndpoint = when (termination) {
            RuntimeProcessTermination.Terminated -> when (reachability) {
                RuntimeEndpointReachability.Unreachable -> InactiveRuntimeEndpoint(endpoint)
            }
        }
    }
}
