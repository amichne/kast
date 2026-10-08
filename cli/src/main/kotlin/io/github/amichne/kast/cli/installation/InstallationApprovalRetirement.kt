package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.ide.RetiredApprovalArtifacts
import io.github.amichne.kast.cli.ide.RetiredApprovalOutcome
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun retireInstallationApprovalArtifacts(
    home: Path,
    observe: (RetiredApprovalOutcome) -> Unit = {
        System.err.println(approvalCleanupJson.encodeToString(InstallationApprovalCleanupObservation(it)))
    },
): RetiredApprovalOutcome = RetiredApprovalArtifacts(home).remove().also(observe)

@Serializable
internal enum class InstallationApprovalCleanupEvent {
    LEGACY_APPROVAL_CLEANUP
}

@Serializable
internal data class InstallationApprovalCleanupObservation(
    val outcome: RetiredApprovalOutcome,
    val type: InstallationApprovalCleanupEvent = InstallationApprovalCleanupEvent.LEGACY_APPROVAL_CLEANUP,
)

internal fun installationApprovalCleanupRejection(
    outcome: InstallationOutcome.LegacyApprovalRetained
): io.github.amichne.kast.cli.CliExit =
    io.github.amichne.kast.cli.CliExit.BoundaryRejected(
        io.github.amichne.kast.cli.CliBoundaryExitStatus.BOOTSTRAP,
        cleanupRejectionFactory.create(
            InstallationApprovalCleanupRejectionDocument(
                outcome.report,
                RetiredApprovalOutcome.Retained(outcome.failure),
            )
        ),
    )

@Serializable
private data class InstallationApprovalCleanupRejectionDocument(
    val installation: InstallationReport,
    val cleanup: RetiredApprovalOutcome.Retained,
)

private val approvalCleanupJson = Json { encodeDefaults = true }
private val cleanupRejectionFactory =
    io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument.generated(
        InstallationApprovalCleanupRejectionDocument.serializer()
    )
