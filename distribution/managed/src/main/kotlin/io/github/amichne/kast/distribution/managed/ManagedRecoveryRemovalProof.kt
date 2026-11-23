package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RecoveryRemovalFailure {
    ROOT_REJECTED,
    INSTALLATION_NOT_RETIRED,
    OWNERSHIP_UNPROVEN,
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
}

sealed interface RecoveryRemovalOutcome {
    data object Removed : RecoveryRemovalOutcome

    data class Rejected(val failure: RecoveryRemovalFailure) : RecoveryRemovalOutcome
}
