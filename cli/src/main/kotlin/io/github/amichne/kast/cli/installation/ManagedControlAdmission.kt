package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.ManagedCompositionPhase
import io.github.amichne.kast.distribution.contract.ManagedControlReservation
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

internal enum class ManagedControlCandidatePurpose {
    COMMIT,
    ABORT,
}

private fun matchesPreparationPurpose(
    receipt: InstallationReplacementReceipt,
    purpose: ManagedControlCandidatePurpose,
): Boolean =
    when (purpose) {
        ManagedControlCandidatePurpose.COMMIT -> receipt.composition?.phase == ManagedCompositionPhase.SETTLED
        ManagedControlCandidatePurpose.ABORT -> receipt.composition == null
    }

internal data class AdmittedManagedControlCandidate(val staged: Path, val receipt: InstallationReplacementReceipt)

internal fun admitManagedControlCandidate(
    plan: VerifiedInstallationPlan,
    transaction: String,
    purpose: ManagedControlCandidatePurpose,
): Refinement<AdmittedManagedControlCandidate, InstallationFailure> {
    val request = plan.request
    val receipt =
        readManagedReceipt(request.installRoot.value.resolve("recovery/replacement"))
            ?: return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
    val reservation =
        receipt.reservation ?: return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
    if (!matchesReservation(plan, transaction, receipt, reservation) || !matchesPreparationPurpose(receipt, purpose))
        return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
    val staged = Path.of(reservation.staged)
    if (
        !managedStageLocation(staged, request.installRoot.value) ||
            staged.toRealPath() != staged ||
            observeInstallationFilesystemIdentity(staged) != receipt.installationIdentity
    )
        return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
    val manifest = readInstalledManifest(staged)
    if (manifest == null || !matchesStagedManifest(plan, staged, reservation, manifest))
        return Refinement.Rejected(InstallationFailure.CONTROL_REJECTED)
    return Refinement.Refined(AdmittedManagedControlCandidate(staged, receipt))
}

private fun managedStageLocation(staged: Path, root: Path): Boolean =
    staged.parent == root && staged.fileName.toString().startsWith(".install-")

private fun matchesStagedManifest(
    plan: VerifiedInstallationPlan,
    staged: Path,
    reservation: ManagedControlReservation,
    manifest: InstallationManifest,
): Boolean {
    if (digest(staged.resolve("installation.json"))?.value != reservation.manifestSha256) return false
    return manifest.controlSha256 == "sha256:${plan.payloadDigest.value}" &&
        manifest.semanticVersion == plan.request.version.toString() &&
        payloadFiles(staged) == manifest.payloadFiles
}

private fun matchesReservation(
    plan: VerifiedInstallationPlan,
    transaction: String,
    receipt: InstallationReplacementReceipt,
    reservation: ManagedControlReservation,
): Boolean =
    reservation.transaction == transaction &&
        reservation.controlSha256 == plan.payloadDigest.value &&
        reservation.version == plan.request.version.toString() &&
        receipt.installation == plan.targetRoot.toString() &&
        receipt.stage == InstallationReplacementStage.PREPARED

private fun readManagedReceipt(journal: Path): InstallationReplacementReceipt? {
    if (journal.toRealPath() != journal) return null
    val raw =
        readInstallationBounded(
            journal.resolve("receipt.json"),
            ControlDistributionLimits.maximumReplacementReceiptBytes.toLong(),
        ) ?: return null
    return decodeControlDocument<InstallationReplacementReceipt>(raw)
}

private fun readInstalledManifest(staged: Path): InstallationManifest? {
    val raw =
        readInstallationBounded(
            staged.resolve("installation.json"),
            ControlDistributionLimits.maximumManifestBytes.toLong(),
        ) ?: return null
    return decodeControlDocument<InstallationManifest>(raw)
}

private inline fun <reified T> decodeControlDocument(raw: String): T? =
    try {
        installationManifestJson.decodeFromString<T>(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
