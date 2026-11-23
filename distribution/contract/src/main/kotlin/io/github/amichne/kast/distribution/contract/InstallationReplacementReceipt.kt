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
}

/** The recorded state of one file the product transaction replaces, before replacement. */
@Serializable
sealed interface ManagedFilePreimage {
    val destination: String

    @Serializable @SerialName("ABSENT") data class Absent(override val destination: String) : ManagedFilePreimage

    @Serializable
    @SerialName("PRESENT")
    data class Present(
        override val destination: String,
        val preimage: String,
        val sha256: String,
        val mode: Int,
    ) : ManagedFilePreimage
}

/** The installer's closed journal phase set; product recovery resumes only a subset of it. */
@Serializable
enum class ManagedReplacementPhase {
    PREPARED,
    FENCED,
    SETTLED,
    CONTROL_COMMITTED,
    ACTIVATION_PROVEN,
}

/** Product selector publication; it shares the receipt's Control reservation and rollback slots. */
@Serializable
data class ProductReplacement(
    val management: ManagedFilePreimage,
    val publicExecutable: ManagedFilePreimage,
    val selectionSha256: String,
    val previousManifestSha256: String?,
    val phase: ManagedReplacementPhase,
    val fence: InstallationFilesystemIdentity?,
)

/**
 * The product installer's pending replacement journal, written only by `managed_update_journal.py`.
 *
 * Only a product journal is recoverable, so the reservation and product record are required.
 */
@Serializable
data class InstallationReplacementReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: InstallationReplacementStage,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
    val reservation: ManagedControlReservation,
    val product: ProductReplacement,
)
