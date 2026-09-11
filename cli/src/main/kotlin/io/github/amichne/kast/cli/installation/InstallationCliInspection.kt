package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

internal sealed interface InstallationHandling {
    data object Unrelated : InstallationHandling

    data class Handled(val exit: CliExit) : InstallationHandling
}

/** Staged installation ingress runs before installed-provider composition or runtime creation. */
internal object InstallationCliInspection {
    fun inspect(arguments: List<String>, environment: Map<String, String>): InstallationHandling {
        if (arguments.firstOrNull() != "installation") return InstallationHandling.Unrelated
        if (arguments != listOf("installation", "install")) {
            return rejected(InstallationFailure.REQUEST_REJECTED, CliBoundaryExitStatus.USAGE)
        }
        val request =
            when (val parsed = InstallationRequest.parse(environment)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return requestRejected(parsed.failure)
            }
        return when (val outcome = InstallationWorkflow.execute(request)) {
            is InstallationOutcome.Complete ->
                InstallationHandling.Handled(CliExit.Complete(reportFactory.create(outcome.report)))
            is InstallationOutcome.Rejected -> rejected(outcome.failure, CliBoundaryExitStatus.BOOTSTRAP)
        }
    }

    private fun requestRejected(failure: InstallationRequestFailure): InstallationHandling {
        val field =
            when (failure) {
                is InstallationRequestFailure.Missing -> failure.environment.key
                is InstallationRequestFailure.InvalidPath -> failure.environment.key
                is InstallationRequestFailure.InvalidValue -> failure.environment.key
            }
        return InstallationHandling.Handled(
            CliExit.BoundaryRejected(
                CliBoundaryExitStatus.USAGE,
                rejectionFactory.create(
                    InstallationRejectionDocument(
                        reason = InstallationFailure.REQUEST_REJECTED.reason(),
                        field = field,
                    )
                ),
            )
        )
    }

    private fun rejected(
        failure: InstallationFailure,
        status: CliBoundaryExitStatus,
    ): InstallationHandling =
        InstallationHandling.Handled(
            CliExit.BoundaryRejected(
                status,
                rejectionFactory.create(InstallationRejectionDocument(reason = failure.reason())),
            )
        )
}

@Serializable
private data class InstallationRejectionDocument(
    val operation: String = "installation.install",
    val status: String = "rejected",
    val reason: String,
    val field: String? = null,
)

private fun InstallationFailure.reason(): String = name.lowercase().replace('_', '-')

private val reportFactory = CliJsonDocument.generated(InstallationReport.serializer())
private val rejectionFactory = CliJsonDocument.generated(InstallationRejectionDocument.serializer())
