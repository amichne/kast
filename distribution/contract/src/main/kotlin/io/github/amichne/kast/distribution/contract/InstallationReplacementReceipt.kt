package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class InstallationFilesystemIdentity(val device: Long, val inode: Long, val owner: Long)

@Serializable
enum class InstallationReplacementStage {
    PREPARED,
    PAYLOAD_COMMITTED,
    FINALIZING,
    RECOVERY_REQUIRED,
}

/** One transient rollback baseline; it is removed only after the outer installer seals activation. */
@Serializable
sealed interface PreviousInstallationPayload {
    @Serializable @SerialName("NONE") data object None : PreviousInstallationPayload

    @Serializable
    @SerialName("PHYSICAL")
    data class Physical(
        val installation: String,
        val identity: InstallationFilesystemIdentity,
        val payload: String,
        val recovery: String,
    ) : PreviousInstallationPayload

    @Serializable
    @SerialName("LEGACY")
    data class Legacy(
        val installation: String,
        val identity: InstallationFilesystemIdentity,
        val selector: String,
        val target: String,
        val recovery: String,
    ) : PreviousInstallationPayload
}

@Serializable
data class InstallationReplacementReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: InstallationReplacementStage,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
)
