package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.runtime.ResolvedMutationRecovery
import kotlinx.serialization.json.JsonObject

internal sealed interface SingleChangeNativePhase {
    val document: JsonObject?

    data class Complete(override val document: JsonObject?) : SingleChangeNativePhase

    data class Incomplete(override val document: JsonObject?) : SingleChangeNativePhase

    data class Rejected(override val document: JsonObject?) : SingleChangeNativePhase
}

internal sealed interface SingleChangeRecoveryAttempt {
    val document: JsonObject?

    data class Resolved(override val document: JsonObject, val state: ResolvedMutationRecovery) :
        SingleChangeRecoveryAttempt

    data class Unresolved(override val document: JsonObject?) : SingleChangeRecoveryAttempt
}
