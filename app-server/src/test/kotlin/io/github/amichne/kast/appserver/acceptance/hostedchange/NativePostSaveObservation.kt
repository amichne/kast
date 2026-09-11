package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionFailure
import kotlinx.serialization.Serializable

@Serializable
internal enum class NativeBrokerReplacement {
    STORES_RETAINED
}

@Serializable
internal data class NativePostSaveObservation(
    val sourcePreimageSha256: String,
    val sourcePostimageSha256: String,
    val brokerFailure: WorkspaceExecutionFailure,
    val retryInvocationCount: Int,
    val invocationJournalSha256: String,
    val threadStoreSha256: String,
    val brokerReplacement: NativeBrokerReplacement,
    val recoveryState: NativeObservedChangeState,
)
