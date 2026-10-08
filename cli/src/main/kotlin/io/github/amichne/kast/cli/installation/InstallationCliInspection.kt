package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.InstalledUpgradeRejection
import io.github.amichne.kast.appserver.diagnosticCode
import io.github.amichne.kast.appserver.runtime.UpgradeBlocker
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.CliTextDocumentAdmission
import io.github.amichne.kast.distribution.managed.ControlLimitExceeded
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal sealed interface InstallationHandling {
    data object Unrelated : InstallationHandling

    data class Handled(val exit: CliExit) : InstallationHandling
}

/** Staged installation ingress runs before installed-provider composition or runtime creation. */
internal object InstallationCliInspection {
    fun inspect(arguments: List<String>, environment: Map<String, String>): InstallationHandling {
        if (arguments.firstOrNull() != "installation") return InstallationHandling.Unrelated
        if (arguments in listOf(listOf("installation", "--help"), listOf("installation", "-h"))) return help()
        val command =
            when (val admitted = admitCommand(arguments)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(admitted.failure, CliBoundaryExitStatus.USAGE)
            }
        val input =
            if (command is ControlInstallationCommand.Install && command.force == InstallationSwitch.ENABLED)
                environment + (InstallationEnvironment.FORCE.key to "1")
            else environment
        val request =
            when (val parsed = InstallationRequest.parse(input)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return requestRejected(parsed.failure)
            }
        if (
            command is ControlInstallationCommand.Install &&
                command.activationPolicy == InstallationActivationPolicy.STAGE_ONLY &&
                request.controlOnly == InstallationSwitch.ENABLED
        )
            return requestRejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.CONTROL_ONLY))
        return project(
            when (command) {
                is ControlInstallationCommand.Install ->
                    InstallationWorkflow.execute(request, activationPolicy = command.activationPolicy)
                is ControlInstallationCommand.Recover -> InstallationWorkflow.recoverControl(request, command.failure)
            }
        )
    }

    private fun admitCommand(arguments: List<String>): Refinement<ControlInstallationCommand, InstallationFailure> =
        when (arguments) {
            listOf("installation", "install") ->
                Refinement.Refined(ControlInstallationCommand.Install(InstallationSwitch.DISABLED))
            listOf("installation", "install", "--force") ->
                Refinement.Refined(ControlInstallationCommand.Install(InstallationSwitch.ENABLED))
            listOf("installation", "install", "--stage-only") ->
                Refinement.Refined(
                    ControlInstallationCommand.Install(
                        InstallationSwitch.DISABLED,
                        InstallationActivationPolicy.STAGE_ONLY,
                    )
                )
            listOf("installation", "install", "--force", "--stage-only"),
            listOf("installation", "install", "--stage-only", "--force") ->
                Refinement.Refined(
                    ControlInstallationCommand.Install(
                        InstallationSwitch.ENABLED,
                        InstallationActivationPolicy.STAGE_ONLY,
                    )
                )
            listOf("installation", "recover-control", "publication") ->
                Refinement.Refined(ControlInstallationCommand.Recover(InstallationFailure.CONTROL_PUBLICATION_REJECTED))
            listOf("installation", "recover-control", "finalization") ->
                Refinement.Refined(
                    ControlInstallationCommand.Recover(InstallationFailure.CONTROL_FINALIZATION_REJECTED)
                )
            else -> Refinement.Rejected(InstallationFailure.REQUEST_REJECTED)
        }

    private fun help(): InstallationHandling {
        val document =
            CliTextDocument.admit(
                "kast installation install [--force] [--stage-only]: install the verified release from install.sh. " +
                    "Use install.sh --force to reset and reclaim the selected installation; " +
                    "--dry-run previews changes. " +
                    "Installed lifecycle commands: inspect, recover-read-only, reset, remove [--dry-run] [--json]."
            )
        return when (document) {
            is CliTextDocumentAdmission.Admitted -> InstallationHandling.Handled(CliExit.Complete(document.document))
            is CliTextDocumentAdmission.Rejected ->
                rejected(InstallationFailure.REQUEST_REJECTED, CliBoundaryExitStatus.USAGE)
        }
    }

    internal fun project(outcome: InstallationOutcome): InstallationHandling =
        when (outcome) {
            is InstallationOutcome.RolledBack -> recoveryReport(ControlRecoveryDocument.RolledBack(outcome.failure))
            is InstallationOutcome.RecoveryRequired ->
                recoveryReport(ControlRecoveryDocument.RecoveryRequired(outcome.failure, outcome.recoveryFailure))
            is InstallationOutcome.Complete ->
                InstallationHandling.Handled(CliExit.Complete(reportFactory.create(outcome.report)))
            is InstallationOutcome.Rejected -> rejected(outcome.failure, CliBoundaryExitStatus.BOOTSTRAP, outcome.limit)
            is InstallationOutcome.UpgradePending ->
                InstallationHandling.Handled(
                    CliExit.BoundaryRejected(
                        CliBoundaryExitStatus.BOOTSTRAP,
                        pendingFactory.create(InstallationUpgradePendingDocument(blockers = outcome.blockers.toList())),
                    )
                )
            is InstallationOutcome.UpgradeRejected ->
                InstallationHandling.Handled(
                    CliExit.BoundaryRejected(
                        CliBoundaryExitStatus.BOOTSTRAP,
                        rejectionFactory.create(InstallationRejectionDocument(reason = outcome.reason.reason())),
                    )
                )
            is InstallationOutcome.LegacyApprovalRetained ->
                InstallationHandling.Handled(installationApprovalCleanupRejection(outcome))
        }

    private fun recoveryReport(document: ControlRecoveryDocument): InstallationHandling =
        InstallationHandling.Handled(
            CliExit.BoundaryRejected(
                CliBoundaryExitStatus.BOOTSTRAP,
                recoveryFactory.create(document),
            )
        )

    private fun requestRejected(failure: InstallationRequestFailure): InstallationHandling {
        val field =
            when (failure) {
                is InstallationRequestFailure.RetiredSetting -> failure.setting.key
                is InstallationRequestFailure.Missing -> failure.environment.key
                is InstallationRequestFailure.InvalidPath -> failure.environment.key
                is InstallationRequestFailure.InvalidValue -> failure.environment.key
            }
        return InstallationHandling.Handled(
            CliExit.BoundaryRejected(
                CliBoundaryExitStatus.USAGE,
                rejectionFactory.create(
                    InstallationRejectionDocument(
                        reason =
                            if (failure is InstallationRequestFailure.RetiredSetting)
                                "retired-setting-remove-assignment"
                            else InstallationFailure.REQUEST_REJECTED.reason(),
                        field = field,
                    )
                ),
            )
        )
    }

    private fun rejected(
        failure: InstallationFailure,
        status: CliBoundaryExitStatus,
        limit: ControlLimitExceeded? = null,
    ): InstallationHandling =
        InstallationHandling.Handled(
            CliExit.BoundaryRejected(
                status,
                rejectionFactory.create(InstallationRejectionDocument(reason = failure.reason(), limit = limit)),
            )
        )
}

@Serializable
private data class InstallationRejectionDocument(
    val operation: String = "installation.install",
    val status: String = "rejected",
    val reason: String,
    val field: String? = null,
    val limit: ControlLimitExceeded? = null,
)

@Serializable
private data class InstallationUpgradePendingDocument(
    val operation: String = "installation.install",
    val status: String = "rejected",
    val reason: String = "prior-daemon-pending",
    val blockers: List<UpgradeBlocker>,
)

private fun InstallationFailure.reason(): String = name.lowercase().replace('_', '-')

private fun InstalledUpgradeRejection.reason(): String =
    when (this) {
        InstalledUpgradeRejection.CandidateRejected -> "prior-daemon-candidate-rejected"
        is InstalledUpgradeRejection.Command -> "prior-daemon-command-${failure.name.lowercase().replace('_', '-')}"
        InstalledUpgradeRejection.ServiceMarkersRejected -> "prior-daemon-service-markers-rejected"
        InstalledUpgradeRejection.RetainedServiceEvidence -> "prior-daemon-retained-service-evidence"
        is InstalledUpgradeRejection.Lifecycle -> "prior-daemon-lifecycle-${failure.name.lowercase().replace('_', '-')}"
        is InstalledUpgradeRejection.Daemon -> reason.diagnosticCode()
        InstalledUpgradeRejection.PreviousUpdateCancelled -> "prior-daemon-previous-update-cancelled"
    }

private val reportFactory = CanonicalJsonDocument.generated(InstallationReport.serializer())
private val rejectionFactory = CanonicalJsonDocument.generated(InstallationRejectionDocument.serializer())
private val pendingFactory = CanonicalJsonDocument.generated(InstallationUpgradePendingDocument.serializer())

@Serializable
private sealed interface ControlRecoveryDocument {
    @Serializable
    @SerialName("ROLLED_BACK")
    data class RolledBack(val failure: InstallationFailure) : ControlRecoveryDocument

    @Serializable
    @SerialName("RECOVERY_REQUIRED")
    data class RecoveryRequired(val failure: InstallationFailure, val recoveryFailure: InstallationFailure) :
        ControlRecoveryDocument
}

private val recoveryFactory = CanonicalJsonDocument.generated(ControlRecoveryDocument.serializer())

private sealed interface ControlInstallationCommand {
    data class Install(
        val force: InstallationSwitch,
        val activationPolicy: InstallationActivationPolicy = InstallationActivationPolicy.ACTIVATE,
    ) : ControlInstallationCommand

    data class Recover(val failure: InstallationFailure) : ControlInstallationCommand
}
