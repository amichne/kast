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
    data class Inspect(
        override val ideaHome: String,
        override val profile: String,
        val legacyProfileInspection: LegacyProfileInspection = LegacyProfileInspection.REJECT_UNPROVEN,
    ) : ManagedHostRequest

    @Serializable
    @SerialName("SETTLE")
    data class Settle(
        override val ideaHome: String,
        override val profile: String,
        val host: String,
        val transaction: String,
    ) : ManagedHostRequest

    @Serializable
    @SerialName("RESUME")
    data class Resume(
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

    @Serializable
    @SerialName("START")
    data class Start(override val ideaHome: String, override val profile: String) : ManagedHostRequest

    @Serializable
    @SerialName("LEGACY_STOP")
    data class LegacyStop(
        override val ideaHome: String,
        override val profile: String,
        val process: ManagedIdeProcess,
        val transaction: String,
    ) : ManagedHostRequest
}

@Serializable
internal enum class LegacyProfileInspection {
    REJECT_UNPROVEN,
    ATTACH,
}

@Serializable
internal data class ManagedIdeProcess(val pid: Long, val processStartEpochMillis: Long, val executable: String)

@Serializable
internal enum class ManagedHostFailure {
    INVALID_REQUEST,
    IDEA_UNAVAILABLE,
    PROFILE_MISMATCH,
    PROCESS_IDENTITY_UNAVAILABLE,
    INSTANCE_AMBIGUOUS,
    HOST_IDENTITY_MISMATCH,
    CONCURRENT_CHANGE,
    LAUNCH_REJECTED,
    LEGACY_QUIT_START_FAILED,
    LEGACY_QUIT_TIMED_OUT,
    LEGACY_QUIT_CAPTURE_FAILED,
    LEGACY_QUIT_OUTPUT_LIMIT_EXCEEDED,
    LEGACY_QUIT_INTERRUPTED,
    LEGACY_QUIT_EXIT_FAILED,
    LEGACY_QUIT_RESPONSE_INVALID,
    HOST_REJECTED,
    PROFILE_UNPROVEN,
}

@Serializable
internal sealed interface ManagedHostResponse {
    @Serializable
    @SerialName("INSPECTED")
    data class Inspected(
        val processes: List<ManagedIdeProcess>,
        val result: IdeLifecycleResult? = null,
        val legacyProfile: LegacyHostProfile? = null,
    ) : ManagedHostResponse

    @Serializable @SerialName("START_REQUESTED") data object StartRequested : ManagedHostResponse

    @Serializable
    @SerialName("LEGACY_STOP_REQUESTED")
    data class LegacyStopRequested(val process: ManagedIdeProcess, val transaction: String) : ManagedHostResponse

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val reason: ManagedHostFailure, val result: IdeLifecycleResult? = null) : ManagedHostResponse
}

internal sealed interface ManagedProcessInventory {
    data class Captured(val processes: List<ManagedIdeProcess>) : ManagedProcessInventory

    data object Unavailable : ManagedProcessInventory
}

internal enum class ManagedIdeLaunchOutcome {
    REQUESTED,
    REJECTED,
}
