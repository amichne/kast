package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.InstalledEpochRetentionFailure
import io.github.amichne.kast.appserver.InstalledUpgradeRejection
import io.github.amichne.kast.appserver.runtime.UpgradeBlocker
import io.github.amichne.kast.distribution.managed.ControlLimitExceeded
import io.github.amichne.kast.distribution.managed.InstallationReplacementWriteFailure
import io.github.amichne.kast.distribution.managed.InstallationSnapshotFailure
import io.github.amichne.kast.kernel.NonEmptyFailures

internal enum class InstallationFailure {
    REQUEST_REJECTED,
    CONTROL_REJECTED,
    CONTROL_LAYOUT_REJECTED,
    CONTROL_LIMIT_EXCEEDED,
    PLUGIN_REJECTED,
    IDEA_REJECTED,
    INSTALLATION_ROOT_REJECTED,
    ACTIVATION_LOCK_REJECTED,
    CONFIGURATION_REJECTED,
    PRIOR_EPOCH_PATH_REJECTED,
    PRIOR_EPOCH_PAYLOAD_REJECTED,
    PRIOR_EPOCH_PAYLOAD_LIMIT_EXCEEDED,
    PRIOR_EPOCH_REJECTED,
    CANDIDATE_EPOCH_REJECTED,
    CANDIDATE_EPOCH_WRITE_REJECTED,
    CANDIDATE_QUALIFICATION_REJECTED,
    CANDIDATE_EXISTING_UNTRUSTED,
    PRIOR_SELECTION_REJECTED,
    PRIOR_ADMISSION_FILE_REJECTED,
    PRIOR_ADMISSION_EXIT_REJECTED,
    PRIOR_ADMISSION_DEADLINE_EXCEEDED,
    PRIOR_ADMISSION_IO_REJECTED,
    PRIOR_ADMISSION_INTERRUPTED,
    PRIOR_RETIREMENT_EXECUTABLE_REJECTED,
    PRIOR_RETIREMENT_CONFIGURATION_REJECTED,
    PRIOR_RETIREMENT_EXIT_REJECTED,
    PRIOR_RETIREMENT_DEADLINE_EXCEEDED,
    PRIOR_RETIREMENT_IO_REJECTED,
    PRIOR_RETIREMENT_INTERRUPTED,
    REPLACEMENT_EXIT_REJECTED,
    REPLACEMENT_DEADLINE_EXCEEDED,
    REPLACEMENT_IO_REJECTED,
    ACTIVATION_REJECTED,
    RECOVERY_REQUIRED,
    SNAPSHOT_SOURCE_REJECTED,
    SNAPSHOT_DESTINATION_REJECTED,
    SNAPSHOT_ENTRY_REJECTED,
    SNAPSHOT_SOURCE_CHANGED,
    SNAPSHOT_LIMIT_EXCEEDED,
    SNAPSHOT_IO_REJECTED,
    TRANSACTION_OWNERSHIP_REJECTED,
    TRANSACTION_WRITE_REJECTED,
    FILESYSTEM_REJECTED,
    INTERRUPTED,
}

internal sealed interface InstallationOutcome {
    data class Complete(val report: InstallationReport) : InstallationOutcome

    data class TrustRejected(val failure: io.github.amichne.kast.cli.ide.BrokerTrustFailure) : InstallationOutcome

    data class Rejected(val failure: InstallationFailure, val limit: ControlLimitExceeded? = null) : InstallationOutcome

    data class UpgradePending(val blockers: NonEmptyFailures<UpgradeBlocker>) : InstallationOutcome

    data class UpgradeRejected(val reason: InstalledUpgradeRejection) : InstallationOutcome
}

internal fun InstalledEpochRetentionFailure.installationFailure(): InstallationFailure =
    when (this) {
        InstalledEpochRetentionFailure.PATH_REJECTED -> InstallationFailure.PRIOR_EPOCH_PATH_REJECTED
        InstalledEpochRetentionFailure.PAYLOAD_REJECTED -> InstallationFailure.PRIOR_EPOCH_PAYLOAD_REJECTED
        InstalledEpochRetentionFailure.PAYLOAD_LIMIT_EXCEEDED -> InstallationFailure.PRIOR_EPOCH_PAYLOAD_LIMIT_EXCEEDED
        InstalledEpochRetentionFailure.SOURCE_EPOCH_REJECTED -> InstallationFailure.PRIOR_EPOCH_REJECTED
        InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED -> InstallationFailure.CANDIDATE_EPOCH_REJECTED
        InstalledEpochRetentionFailure.WRITE_REJECTED -> InstallationFailure.CANDIDATE_EPOCH_WRITE_REJECTED
    }

internal fun InstallationSnapshotFailure.installationFailure(): InstallationFailure =
    when (this) {
        InstallationSnapshotFailure.SOURCE_REJECTED -> InstallationFailure.SNAPSHOT_SOURCE_REJECTED
        InstallationSnapshotFailure.DESTINATION_REJECTED -> InstallationFailure.SNAPSHOT_DESTINATION_REJECTED
        InstallationSnapshotFailure.ENTRY_REJECTED -> InstallationFailure.SNAPSHOT_ENTRY_REJECTED
        InstallationSnapshotFailure.SOURCE_CHANGED -> InstallationFailure.SNAPSHOT_SOURCE_CHANGED
        InstallationSnapshotFailure.LIMIT_EXCEEDED -> InstallationFailure.SNAPSHOT_LIMIT_EXCEEDED
        InstallationSnapshotFailure.IO_REJECTED -> InstallationFailure.SNAPSHOT_IO_REJECTED
    }

internal fun InstallationReplacementWriteFailure.installationFailure(): InstallationFailure =
    when (this) {
        InstallationReplacementWriteFailure.OWNERSHIP_REJECTED -> InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED
        InstallationReplacementWriteFailure.IO_REJECTED -> InstallationFailure.TRANSACTION_WRITE_REJECTED
    }
