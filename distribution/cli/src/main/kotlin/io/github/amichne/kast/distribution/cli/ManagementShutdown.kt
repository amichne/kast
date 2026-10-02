package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.encodeToString

internal data class LifecycleExecution(
    val child: LifecycleChildExecutor = NativeLifecycleChildExecutor,
    val processes: LifecycleProcesses = NativeLifecycleProcesses,
    val installer: InstallerExecutor = InstallerExecutor(::executePrivateInstaller),
    val observe: (LifecycleObservation) -> Unit = { System.err.println(managementJson.encodeToString(it)) },
)

/** External installer execution stays behind the existing report validation owner. */
internal fun interface InstallerExecutor {
    fun execute(script: Path, arguments: List<String>, report: Path): Int
}

@JvmInline
internal value class ReinstallationVersion private constructor(val value: String) {
    companion object {
        fun admit(raw: String?): ReinstallationVersionAdmission =
            if (raw != null && Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(raw))
                ReinstallationVersionAdmission.Admitted(ReinstallationVersion(raw))
            else ReinstallationVersionAdmission.Rejected
    }
}

internal sealed interface ReinstallationVersionAdmission {
    data class Admitted(val version: ReinstallationVersion) : ReinstallationVersionAdmission

    data object Rejected : ReinstallationVersionAdmission
}

internal sealed interface InstallationSelection {
    data object Latest : InstallationSelection

    data object ControlOnly : InstallationSelection

    data class Exact(val version: ReinstallationVersion) : InstallationSelection
}

internal fun executeInstallationLifecycle(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    operation: LifecycleOperation,
    execution: LifecycleExecution = LifecycleExecution(),
): LifecycleOutcome {
    val prior =
        try {
            requireOwnedExecutable(root)
        } catch (_: ManagementRejected) {
            return LifecycleOutcome.Rejected(operation, LifecycleStage.ADMISSION, LifecycleFailure.OWNERSHIP_UNPROVEN)
        }
    val outcome =
        try {
            withRegistrationLock(root) { shutdownLocked(root, home, environment, operation, execution) }
        } catch (_: ManagementRejected) {
            LifecycleOutcome.Rejected(operation, LifecycleStage.ADMISSION, LifecycleFailure.OWNERSHIP_UNPROVEN)
        }
    return when (outcome) {
        is LifecycleOutcome.Reinstalled -> reconnect(root, home, prior, outcome, execution.observe)
        is LifecycleOutcome.Stopped,
        is LifecycleOutcome.Rejected,
        is LifecycleOutcome.Pending -> outcome
    }
}

private fun shutdownLocked(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    operation: LifecycleOperation,
    execution: LifecycleExecution,
): LifecycleOutcome {
    val lifecycle =
        when (val admitted = InstallationLifecycle.admit(root, home, environment, execution)) {
            is LifecycleAdmission.Admitted -> admitted.lifecycle
            LifecycleAdmission.Rejected ->
                return LifecycleOutcome.Rejected(
                    operation,
                    LifecycleStage.ADMISSION,
                    LifecycleFailure.OWNERSHIP_UNPROVEN,
                )
        }
    val stopped = lifecycle.shutdown(operation)
    if (stopped !is LifecycleOutcome.Stopped || operation != LifecycleOperation.REINSTALL) return stopped
    return reinstall(root, lifecycle, execution)
}

private fun reinstall(root: Path, lifecycle: InstallationLifecycle, execution: LifecycleExecution): LifecycleOutcome {
    val operation = LifecycleOperation.REINSTALL
    execution.observe(LifecycleObservation.started(operation, LifecycleStage.INSTALLATION))
    val version =
        when (val admitted = ReinstallationVersion.admit(readStatus(root, "kast").installedVersion.value)) {
            is ReinstallationVersionAdmission.Admitted -> admitted.version
            ReinstallationVersionAdmission.Rejected -> return installationRejected(execution)
        }
    val report =
        try {
            installLatest(root, requireOwnedExecutable(root), InstallationSelection.Exact(version), execution.installer)
        } catch (_: ManagementRejected) {
            return installationRejected(execution)
        }
    if (report.semanticVersion != version.value) {
        installationRejected(execution)
        return LifecycleOutcome.Pending(
            operation,
            report.semanticVersion,
            LifecycleStage.INSTALLATION,
            LifecycleFailure.INSTALLATION_REJECTED,
        )
    }
    execution.observe(LifecycleObservation.completed(operation, LifecycleStage.INSTALLATION))
    execution.observe(LifecycleObservation.started(operation, LifecycleStage.ACTIVATION))
    return when (val resumed = lifecycle.resume()) {
        is LifecycleEffect.Rejected -> {
            execution.observe(LifecycleObservation.rejected(operation, LifecycleStage.ACTIVATION, resumed.failure))
            LifecycleOutcome.Pending(operation, version.value, LifecycleStage.ACTIVATION, resumed.failure)
        }
        LifecycleEffect.Completed -> {
            execution.observe(LifecycleObservation.completed(operation, LifecycleStage.ACTIVATION))
            LifecycleOutcome.Reinstalled(operation, root.resolve("installation").toString(), version.value)
        }
    }
}

private fun installationRejected(execution: LifecycleExecution): LifecycleOutcome.Rejected {
    execution.observe(
        LifecycleObservation.rejected(
            LifecycleOperation.REINSTALL,
            LifecycleStage.INSTALLATION,
            LifecycleFailure.INSTALLATION_REJECTED,
        )
    )
    return LifecycleOutcome.Rejected(
        LifecycleOperation.REINSTALL,
        LifecycleStage.INSTALLATION,
        LifecycleFailure.INSTALLATION_REJECTED,
    )
}

private fun reconnect(
    root: Path,
    home: Path,
    prior: ManagementReceipt,
    outcome: LifecycleOutcome.Reinstalled,
    observe: (LifecycleObservation) -> Unit,
): LifecycleOutcome {
    observe(LifecycleObservation.started(outcome.operation, LifecycleStage.REGISTRATIONS))
    for (registration in prior.registrations) {
        try {
            connectHarness(root, home, registration.connection)
        } catch (_: ManagementRejected) {
            observe(
                LifecycleObservation.rejected(
                    outcome.operation,
                    LifecycleStage.REGISTRATIONS,
                    LifecycleFailure.REGISTRATION_REPAIR_REQUIRED,
                )
            )
            return LifecycleOutcome.Pending(
                outcome.operation,
                outcome.version,
                LifecycleStage.REGISTRATIONS,
                LifecycleFailure.REGISTRATION_REPAIR_REQUIRED,
            )
        }
    }
    observe(LifecycleObservation.completed(outcome.operation, LifecycleStage.REGISTRATIONS))
    return outcome
}
