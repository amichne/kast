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
import kotlinx.serialization.Serializable

internal sealed interface InstallationHandling {
    data object Unrelated : InstallationHandling

    data class Handled(val exit: CliExit) : InstallationHandling
}

/** Staged installation ingress runs before installed-provider composition or runtime creation. */
internal object InstallationCliInspection {
    fun inspect(arguments: List<String>, environment: Map<String, String>): InstallationHandling {
        if (arguments.firstOrNull() != "installation") return InstallationHandling.Unrelated
        if (arguments in listOf(listOf("installation", "--help"), listOf("installation", "-h"))) {
            val help =
                io.github.amichne.kast.cli.CliTextDocument.admit(
                    "kast installation install [--force]: install the verified release from install.sh. " +
                        "Use install.sh --force to reset and reclaim the selected installation; " +
                        "--dry-run previews changes. " +
                        "Installed lifecycle commands: inspect, recover-read-only, reset, remove [--dry-run] [--json]."
                )
            return when (help) {
                is io.github.amichne.kast.cli.CliTextDocumentAdmission.Admitted ->
                    InstallationHandling.Handled(CliExit.Complete(help.document))
                is io.github.amichne.kast.cli.CliTextDocumentAdmission.Rejected ->
                    rejected(InstallationFailure.REQUEST_REJECTED, CliBoundaryExitStatus.USAGE)
            }
        }

        val force = arguments == listOf("installation", "install", "--force")
        if (!force && arguments != listOf("installation", "install")) {
            return rejected(InstallationFailure.REQUEST_REJECTED, CliBoundaryExitStatus.USAGE)
        }
        val request =
            when (
                val parsed =
                    InstallationRequest.parse(
                        if (force) environment + (InstallationEnvironment.FORCE.key to "1") else environment
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return requestRejected(parsed.failure)
            }
        return project(InstallationWorkflow.execute(request))
    }

    internal fun project(outcome: InstallationOutcome): InstallationHandling =
        when (outcome) {
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
            is InstallationOutcome.TrustRejected ->
                InstallationHandling.Handled(installationTrustRejection(outcome.failure))
        }

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
