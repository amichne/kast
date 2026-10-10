package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.contract.WorkspaceNativeReadSettlement
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity

/** Identity capability for exactly one in-process endpoint incarnation. Never reconstructed. */
class HostedQueryEndpoint internal constructor()

/** Exactly one admitted invocation; identity is preserved until publication or abandonment. */
internal class HostedQueryPermit(val identity: WorkspaceReadOperationIdentity = WorkspaceReadOperationIdentity.Opaque())

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
    private var state: State = State.Active(mutableSetOf())

    @Synchronized
    fun begin(
        requested: HostedQueryEndpoint,
        limits: ReadLimits = ReadLimits.Default,
        identity: WorkspaceReadOperationIdentity = WorkspaceReadOperationIdentity.Opaque(),
    ): HostedQueryAdmission {
        val current = state
        return when (current) {
            is State.Retired -> HostedQueryAdmission.Rejected(HostedQueryFailure.RETIRED)
            is State.Active -> {
                if (requested !== endpoint) return HostedQueryAdmission.Rejected(HostedQueryFailure.WRONG_ENDPOINT)
                if (current.permits.size >= limits[ReadLimitParameter.HOST_READERS].value) {
                    HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY)
                } else {
                    val permit = HostedQueryPermit(identity)
                    current.permits.add(permit)
                    HostedQueryAdmission.Admitted(permit)
                }
            }
        }
    }

    @Synchronized
    fun complete(permit: HostedQueryPermit): HostedQueryCompletion =
        when (val current = state) {
            is State.Retired -> {
                current.permits.remove(permit)
                HostedQueryCompletion.Rejected(HostedQueryFailure.RETIRED)
            }
            is State.Active ->
                if (current.permits.remove(permit)) HostedQueryCompletion.Published
                else HostedQueryCompletion.Rejected(HostedQueryFailure.STALE_REQUEST)
        }

    @Synchronized
    fun retire() {
        when (val current = state) {
            is State.Active -> state = State.Retired(current.permits)
            is State.Retired -> Unit
        }
    }

    /** Permits remain owned during cancellation drainage, including after endpoint retirement. */
    @Synchronized
    fun settlement(): WorkspaceNativeReadSettlement =
        when (val current = state) {
            is State.Active ->
                if (current.permits.isEmpty()) WorkspaceNativeReadSettlement.Quiescent
                else WorkspaceNativeReadSettlement.Running(current.permits.map { it.identity })
            is State.Retired -> WorkspaceNativeReadSettlement.Retired(current.permits.map { it.identity })
        }

    private sealed interface State {
        class Active(val permits: MutableSet<HostedQueryPermit>) : State

        class Retired(val permits: MutableSet<HostedQueryPermit>) : State
    }
}

/** Bounded outcomes contain no exception messages or source payloads. */
sealed interface HostedQueryFailure {
    data class Configuration(val cause: io.github.amichne.kast.kernel.ReadLimitFailure) : HostedQueryFailure

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

    data class Publication(val cause: HostedPublicationFailureCause) : HostedQueryFailure

    data class ProjectAdmission(
        val cause: io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
    ) : HostedQueryFailure

    data class ModelCapture(val cause: io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture.Rejected) :
        HostedQueryFailure

    data class ReadEpoch(val cause: io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure) :
        HostedQueryFailure

    data class Freshness(val cause: io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure) :
        HostedQueryFailure

    data class LiveAuthority(val cause: io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure) :
        HostedQueryFailure

    data class NamedSourceScope(
        val cause: io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
    ) : HostedQueryFailure
}

enum class HostedPlatformFailureCause {
    RUNTIME,
    LINKAGE,
}
