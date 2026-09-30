package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.managed.ManagedCopiedEpoch
import io.github.amichne.kast.distribution.managed.ManagedCopiedEpochAdmission
import io.github.amichne.kast.distribution.managed.ManagedCopiedEpochRemoval
import io.github.amichne.kast.distribution.managed.ManagedCopiedEpochVerification
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Startup is the only owner that creates an epoch; a retired replacement may discard its admitted copied epoch. */
sealed interface InstalledEpochRetention {
    data object Absent : InstalledEpochRetention

    data object Preserved : InstalledEpochRetention

    data object Regenerate : InstalledEpochRetention

    data class Rejected(val failure: InstalledEpochRetentionFailure) : InstalledEpochRetention

    companion object {
        fun retain(prior: Path, staged: Path, installation: Path): InstalledEpochRetention {
            if (!eligibleEpochReplacement(prior, staged, installation))
                return Rejected(InstalledEpochRetentionFailure.PATH_REJECTED)
            val admitted =
                when (val source = BrokerInstallationState.observe(prior)) {
                    is Refinement.Refined -> source.value
                    is Refinement.Rejected ->
                        return if (
                            source.failure == InstallationStateFailure.EPOCH_ABSENT &&
                                Files.notExists(staged.resolve("state/epoch.json"), LinkOption.NOFOLLOW_LINKS)
                        )
                            Absent
                        else Rejected(source.failure.retentionFailure())
                }
            return retainCopiedEpoch(staged, admitted, installation)
        }
    }
}

enum class InstalledEpochRetentionFailure {
    PATH_REJECTED,
    PAYLOAD_REJECTED,
    PAYLOAD_LIMIT_EXCEEDED,
    SOURCE_EPOCH_REJECTED,
    COPIED_EPOCH_REJECTED,
    WRITE_REJECTED,
}

private fun InstallationStateFailure.retentionFailure(): InstalledEpochRetentionFailure =
    when (this) {
        InstallationStateFailure.PATH_REJECTED -> InstalledEpochRetentionFailure.PATH_REJECTED
        InstallationStateFailure.PAYLOAD_REJECTED -> InstalledEpochRetentionFailure.PAYLOAD_REJECTED
        InstallationStateFailure.PAYLOAD_LIMIT_EXCEEDED -> InstalledEpochRetentionFailure.PAYLOAD_LIMIT_EXCEEDED
        InstallationStateFailure.EPOCH_ABSENT,
        InstallationStateFailure.EPOCH_REJECTED -> InstalledEpochRetentionFailure.SOURCE_EPOCH_REJECTED
        InstallationStateFailure.WRITE_REJECTED -> InstalledEpochRetentionFailure.WRITE_REJECTED
    }

private fun retainCopiedEpoch(
    staged: Path,
    admitted: io.github.amichne.kast.appserver.protocol.ThreadBindingOwner.Installation,
    installation: Path,
): InstalledEpochRetention {
    return try {
        val copiedFile =
            when (val file = ManagedCopiedEpoch.admit(staged)) {
                is ManagedCopiedEpochAdmission.Admitted -> file.epoch
                ManagedCopiedEpochAdmission.Rejected ->
                    return InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED)
            }
        val copied = BrokerInstallationState.observeCopiedEpoch(staged.resolve("state"), admitted.installationId.value)
        if (copied !is Refinement.Refined || copied.value != admitted)
            return InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED)
        val candidate =
            when (val observed = BrokerInstallationState.stagedIdentity(staged, installation)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return InstalledEpochRetention.Rejected(observed.failure.retentionFailure())
            }
        if (copiedFile.verify() != ManagedCopiedEpochVerification.VERIFIED)
            return InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED)
        if (candidate == admitted.installationId.value) InstalledEpochRetention.Preserved
        else
            when (copiedFile.remove()) {
                ManagedCopiedEpochRemoval.DELETED -> InstalledEpochRetention.Regenerate
                ManagedCopiedEpochRemoval.IDENTITY_REJECTED ->
                    InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED)
                ManagedCopiedEpochRemoval.IO_REJECTED ->
                    InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.WRITE_REJECTED)
            }
    } catch (_: java.nio.file.NoSuchFileException) {
        InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED)
    } catch (_: java.io.IOException) {
        InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.WRITE_REJECTED)
    } catch (_: SecurityException) {
        InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.WRITE_REJECTED)
    }
}

private fun eligibleEpochReplacement(prior: Path, staged: Path, installation: Path): Boolean =
    listOf(prior, staged, installation).all(::canonicalEpochPath) &&
        epochReplacementSlot(staged, installation) &&
        priorEpochSlot(prior, installation)

private fun canonicalEpochPath(path: Path): Boolean =
    path.isAbsolute && path.normalize() == path && path.fileName != null

private fun epochReplacementSlot(staged: Path, installation: Path): Boolean =
    installation.fileName.toString() == "installation" &&
        staged.parent == installation.parent &&
        staged.fileName.toString().startsWith(".install-")

private fun priorEpochSlot(prior: Path, installation: Path): Boolean =
    prior == installation ||
        (prior.parent.fileName.toString() == "versions" && prior.parent.parent == installation.parent)
