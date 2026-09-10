package io.github.amichne.kast.workspace.intellij.read.hosted

/** Identity capability for exactly one in-process endpoint incarnation. Never reconstructed. */
class HostedQueryEndpoint internal constructor()

/** Exactly one admitted invocation; identity is preserved until publication or abandonment. */
internal class HostedQueryPermit

internal sealed interface HostedQueryAdmission {
    data class Admitted(val permit: HostedQueryPermit) : HostedQueryAdmission
    data class Rejected(val failure: HostedQueryFailure) : HostedQueryAdmission
}

internal sealed interface HostedQueryCompletion {
    data object Published : HostedQueryCompletion
    data class Rejected(val failure: HostedQueryFailure) : HostedQueryCompletion
}

/** Small linearizable owner. Retirement is terminal, including for already detached answers. */
internal class HostedQueryLifetime {
    val endpoint = HostedQueryEndpoint()
    private var state: State = State.Idle

    @Synchronized
    fun begin(requested: HostedQueryEndpoint): HostedQueryAdmission = when {
        state == State.Retired -> HostedQueryAdmission.Rejected(HostedQueryFailure.RETIRED)
        requested !== endpoint -> HostedQueryAdmission.Rejected(HostedQueryFailure.WRONG_ENDPOINT)
        state is State.Reading -> HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY)
        else -> HostedQueryPermit().let { permit ->
            state = State.Reading(permit)
            HostedQueryAdmission.Admitted(permit)
        }
    }

    @Synchronized
    fun complete(permit: HostedQueryPermit): HostedQueryCompletion = when (val current = state) {
        State.Retired -> HostedQueryCompletion.Rejected(HostedQueryFailure.RETIRED)
        State.Idle -> HostedQueryCompletion.Rejected(HostedQueryFailure.STALE_REQUEST)
        is State.Reading -> if (current.permit !== permit) {
            HostedQueryCompletion.Rejected(HostedQueryFailure.STALE_REQUEST)
        } else {
            state = State.Idle
            HostedQueryCompletion.Published
        }
    }

    @Synchronized
    fun retire() { state = State.Retired }

    private sealed interface State {
        data object Idle : State
        data class Reading(val permit: HostedQueryPermit) : State
        data object Retired : State
    }
}

/** Bounded outcomes contain no exception messages or source payloads. */
sealed interface HostedQueryFailure {
    data object RETIRED : HostedQueryFailure
    data object WRONG_ENDPOINT : HostedQueryFailure
    data object WRONG_PROJECT : HostedQueryFailure
    data object BUSY : HostedQueryFailure
    data object STALE_REQUEST : HostedQueryFailure
    data object INVALID_SELECTION : HostedQueryFailure
    data object DECLARATION_NOT_FOUND : HostedQueryFailure
    data object AMBIGUOUS_DECLARATION : HostedQueryFailure
    data object DECLARATION_IDENTITY_MISMATCH : HostedQueryFailure
    data object PROJECT_UNAVAILABLE : HostedQueryFailure
    data object INDEXING : HostedQueryFailure
    data object WRONG_THREAD : HostedQueryFailure
    data object DIRTY_DOCUMENTS : HostedQueryFailure
    data object UNCOMMITTED_DOCUMENTS : HostedQueryFailure
    data object CONTENT_MOVED : HostedQueryFailure
    data object MODEL_MOVED : HostedQueryFailure
    data object UNSUPPORTED_MODEL : HostedQueryFailure
    data object UNSUPPORTED_DECLARATION : HostedQueryFailure
    data object UNRESOLVED_SUPERTYPE : HostedQueryFailure
    data object FILE_UNAVAILABLE : HostedQueryFailure
    data object FILE_TOO_LARGE : HostedQueryFailure
    data object RESULT_LIMIT_EXCEEDED : HostedQueryFailure
    data object OUTSIDE_SCOPE : HostedQueryFailure
    data object AMBIGUOUS_SCOPE : HostedQueryFailure
    data object READ_PREEMPTED : HostedQueryFailure
    data object CANCELLED : HostedQueryFailure
    data object BUDGET_EXCEEDED : HostedQueryFailure
    data class Platform(val cause: HostedPlatformFailureCause) : HostedQueryFailure
    data class ProjectAdmission(val cause: io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure) : HostedQueryFailure
    data class ModelCapture(val cause: io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture.Rejected) : HostedQueryFailure
    data class ReadEpoch(val cause: io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure) : HostedQueryFailure
    data class Freshness(val cause: io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure) : HostedQueryFailure
    data class LiveAuthority(val cause: io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure) : HostedQueryFailure
    data class NamedSourceScope(val cause: io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure) : HostedQueryFailure
}

enum class HostedPlatformFailureCause { RUNTIME, LINKAGE }
