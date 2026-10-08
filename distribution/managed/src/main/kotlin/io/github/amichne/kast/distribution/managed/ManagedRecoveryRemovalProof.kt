package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RecoveryRemovalFailure {
    ROOT_REJECTED,
    INSTALLATION_NOT_RETIRED,
    OWNERSHIP_UNPROVEN,
    RECEIPT_REJECTED,
    RECOVERY_PENDING,
    RECOVERY_UNRESOLVED,
    FOREIGN_CONTENT,
    IO_UNAVAILABLE,
}

/** Durable local file observations, not executable authority or a replacement trust mechanism. */
@Serializable
sealed interface ManagedRecoveryRemovalProof {
    @Serializable @SerialName("ABSENT_RECOVERY") data object Absent : ManagedRecoveryRemovalProof

    @Serializable
    @SerialName("EMPTY_RECOVERY")
    data class Empty(
        val installation: String,
        val recoveryDirectoryIdentity: InstallationFilesystemIdentity,
    ) : ManagedRecoveryRemovalProof

    @Serializable
    @SerialName("ADMITTED_RECOVERY")
    data class Admitted(
        val installation: String,
        val installationIdentity: InstallationFilesystemIdentity,
        val recoveryDirectoryIdentity: InstallationFilesystemIdentity,
        val bundleIdentity: InstallationFilesystemIdentity,
        val receipt: CapturedRecoveryFile,
        val recoveryScript: CapturedRecoveryFile,
        val lifecycleScript: CapturedRecoveryFile,
    ) : ManagedRecoveryRemovalProof
}

@Serializable
data class CapturedRecoveryFile(val identity: InstallationFilesystemIdentity, val digest: RecoveryBundleDigest)

@Serializable
@JvmInline
value class RecoveryBundleDigest private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<RecoveryBundleDigest, RecoveryRemovalFailure> =
            if (value.matches(Regex("[0-9a-f]{64}"))) Refinement.Refined(RecoveryBundleDigest(value))
            else Refinement.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN)
    }
}

sealed interface RecoveryRemovalOutcome {
    data object Removed : RecoveryRemovalOutcome

    data class Rejected(val failure: RecoveryRemovalFailure) : RecoveryRemovalOutcome
}
