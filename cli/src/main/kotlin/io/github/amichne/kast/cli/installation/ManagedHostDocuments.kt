package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface ManagedHostRequest {
    val ideaHome: String
    val profile: String

    @Serializable
    @SerialName("INSPECT")
    data class Inspect(override val ideaHome: String, override val profile: String) : ManagedHostRequest

    @Serializable
    @SerialName("SETTLE")
    data class Settle(
        override val ideaHome: String,
        override val profile: String,
        val host: String,
        val transaction: String,
    ) : ManagedHostRequest

    @Serializable
    @SerialName("STOP")
    data class Stop(
        override val ideaHome: String,
        override val profile: String,
        val host: String,
        val transaction: String,
    ) : ManagedHostRequest
}

@Serializable
internal data class ManagedIdeProcess(val pid: Long, val processStartEpochMillis: Long, val executable: String)

@Serializable
internal enum class ManagedHostFailure {
    INVALID_REQUEST,
    IDEA_UNAVAILABLE,
    PROCESS_IDENTITY_UNAVAILABLE,
    INSTANCE_AMBIGUOUS,
    HOST_IDENTITY_MISMATCH,
    CONCURRENT_CHANGE,
    HOST_REJECTED,
}

@Serializable
internal sealed interface ManagedHostResponse {
    @Serializable
    @SerialName("INSPECTED")
    data class Inspected(
        val processes: List<ManagedIdeProcess>,
        val result: IdeLifecycleResult? = null,
    ) : ManagedHostResponse

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val reason: ManagedHostFailure, val result: IdeLifecycleResult? = null) : ManagedHostResponse
}

internal sealed interface ManagedProcessInventory {
    data class Captured(val processes: List<ManagedIdeProcess>) : ManagedProcessInventory

    data object Unavailable : ManagedProcessInventory
}
