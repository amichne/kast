package io.github.amichne.kast.runtime.ide.read

/** Finite client causes that end active or queued read demand. */
internal enum class ProjectReadCancellationCause { REQUEST_CANCELLED, CLIENT_DISCONNECTED }

/** Finite platform causes that abort an executing read independently of client demand. */
internal enum class ProjectReadExecutionCancellationCause { WRITE_PREEMPTED, PLATFORM_CANCELLED }

/** Finite host lifecycle causes that retire one project's read authority. */
internal enum class ProjectReadRetirementCause {
    PROJECT_DISPOSED,
    PLUGIN_UNLOADED,
    ENDPOINT_PUBLICATION_FAILED,
    SOCKET_FAILED,
}

/** Closed terminal state of one issued active permit. */
internal sealed interface ProjectReadPermitTerminal {
    data object Released : ProjectReadPermitTerminal
    data class Cancelled(val cause: ProjectReadCancellationCause) : ProjectReadPermitTerminal
    data class ExecutionCancelled(val cause: ProjectReadExecutionCancellationCause) :
        ProjectReadPermitTerminal
    data class Retired(val cause: ProjectReadRetirementCause) : ProjectReadPermitTerminal
}

/** Closed terminal state of one queued request. */
internal sealed interface QueuedProjectReadTerminal {
    data class Cancelled(val cause: ProjectReadCancellationCause) : QueuedProjectReadTerminal
    data class Promoted(val permit: ProjectReadPermit) : QueuedProjectReadTerminal
    data class Retired(val cause: ProjectReadRetirementCause) : QueuedProjectReadTerminal
}

/** Closed result of one single-flight admission attempt. */
internal sealed interface ProjectReadAdmission {
    data class Active(val permit: ProjectReadPermit) : ProjectReadAdmission
    data class Queued(val request: QueuedProjectReadRequest) : ProjectReadAdmission
    data class Rejected(val failure: ProjectReadAdmissionFailure) : ProjectReadAdmission
}

/** Finite failures before read execution begins. */
internal sealed interface ProjectReadAdmissionFailure {
    data object WrongProject : ProjectReadAdmissionFailure
    data object IncomparableProjectSource : ProjectReadAdmissionFailure
    data object Busy : ProjectReadAdmissionFailure
    data class Retired(val cause: ProjectReadRetirementCause) : ProjectReadAdmissionFailure
}

/** Closed result of refining one active permit into executing authority. */
internal sealed interface ProjectReadExecutionAdmission {
    data class Admitted(val execution: ExecutingProjectRead) : ProjectReadExecutionAdmission
    data class Rejected(val failure: ProjectReadExecutionAdmissionFailure) :
        ProjectReadExecutionAdmission
}

/** Finite failures for `ProjectReadPermit -> ExecutingProjectRead`. */
internal sealed interface ProjectReadExecutionAdmissionFailure {
    data object NotOwned : ProjectReadExecutionAdmissionFailure
    data object AlreadyExecuting : ProjectReadExecutionAdmissionFailure
    data class Terminal(val terminal: ProjectReadPermitTerminal) :
        ProjectReadExecutionAdmissionFailure
}

/** Non-forgeable proof that one active permit is currently executing. */
internal sealed interface ExecutingProjectRead

/** Closed outcome of requesting cancellation through the cancellable-read executor boundary. */
internal sealed interface ProjectReadExecutionCancellation {
    data class Ended(
        val terminal: ProjectReadPermitTerminal,
        val continuation: ProjectReadContinuation,
    ) : ProjectReadExecutionCancellation

    data class Deferred(val cause: ProjectReadCancellationCause) :
        ProjectReadExecutionCancellation
    data class AlreadyDeferred(val terminal: ProjectReadPermitTerminal) :
        ProjectReadExecutionCancellation
    data class AlreadyTerminal(val terminal: ProjectReadPermitTerminal) :
        ProjectReadExecutionCancellation
    data object NotOwned : ProjectReadExecutionCancellation
}

/** Closed continuation after one active permit ends. */
internal sealed interface ProjectReadContinuation {
    data object Idle : ProjectReadContinuation
    data class Promoted(
        val request: QueuedProjectReadRequest,
        val permit: ProjectReadPermit,
    ) : ProjectReadContinuation
}

/** Closed result of releasing or cancelling one active permit. */
internal sealed interface ProjectReadPermitEnd {
    data class Ended(
        val terminal: ProjectReadPermitTerminal,
        val continuation: ProjectReadContinuation,
    ) : ProjectReadPermitEnd
    data class AlreadyEnded(val terminal: ProjectReadPermitTerminal) : ProjectReadPermitEnd
    data class Deferred(val terminal: ProjectReadPermitTerminal) : ProjectReadPermitEnd
    data object ExecutionInProgress : ProjectReadPermitEnd
    data object NotOwned : ProjectReadPermitEnd
}

/** Closed result of cancelling one queued request. */
internal sealed interface QueuedProjectReadCancellation {
    data class Cancelled(val cause: ProjectReadCancellationCause) :
        QueuedProjectReadCancellation
    data class AlreadyTerminal(val terminal: QueuedProjectReadTerminal) :
        QueuedProjectReadCancellation
    data object NotOwned : QueuedProjectReadCancellation
}

/** Closed no-mutation observation of one exact queued request. */
internal sealed interface QueuedProjectReadObservation {
    data object Pending : QueuedProjectReadObservation
    data class Terminal(val value: QueuedProjectReadTerminal) : QueuedProjectReadObservation
    data object NotOwned : QueuedProjectReadObservation
}

/** Exact authorities terminalized by the first retirement transition. */
internal sealed interface RetiredProjectReadAuthority {
    data object None : RetiredProjectReadAuthority
    data class Active(val permit: ProjectReadPermit) : RetiredProjectReadAuthority
    data class ActiveAndQueued(
        val permit: ProjectReadPermit,
        val request: QueuedProjectReadRequest,
    ) : RetiredProjectReadAuthority
}

/** Closed result of retiring one project-scoped owner. */
internal sealed interface ProjectReadRetirement {
    data class Retired(
        val cause: ProjectReadRetirementCause,
        val authority: RetiredProjectReadAuthority,
    ) : ProjectReadRetirement
    data class AlreadyRetired(val cause: ProjectReadRetirementCause) : ProjectReadRetirement
}
