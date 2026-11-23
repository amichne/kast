package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.managed.RecoveryRemovalOutcome
import io.github.amichne.kast.distribution.managed.admitRecoveryRemoval
import io.github.amichne.kast.distribution.managed.removalProcessUid
import io.github.amichne.kast.distribution.managed.removeRetiredRecovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.encodeToString

internal fun uninstallInstallation(
    root: Path,
    home: Path,
    executeInstaller: (Path, List<String>) -> Int = { script, arguments -> executePrivateInstaller(script, arguments) },
    observe: (UninstallCleanupObservation) -> Unit = { System.err.println(managementJson.encodeToString(it)) },
    present: (String) -> Unit = ::println,
    environment: Map<String, String> = emptyMap(),
) {
    val receipt = admittedUninstallReceipt(root)
    val remove = {
        admitUninstallPayload(root, receipt)
        removeInstallation(root, home, receipt, executeInstaller, observe, present, environment)
        Refinement.Refined(Unit)
    }
    when (
        val removed =
            ContainerQueryLease.duringStoreRemoval(InstallationLayout.beneath(root).management.workspaceStore, remove)
    ) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> throw ManagementRejected("uninstall-workspaces", removed.failure.name)
    }
}

private fun removeInstallation(
    root: Path,
    home: Path,
    receipt: ManagementReceipt,
    executeInstaller: (Path, List<String>) -> Int,
    observe: (UninstallCleanupObservation) -> Unit,
    present: (String) -> Unit,
    environment: Map<String, String>,
) {
    cleanupUninstalledUserState(home, observe) {
        retireUninstallPayload(root, home, receipt, executeInstaller, observe, environment)
    }
    val journal = readUninstallRetirement(root)
    journal.productRemoval?.let { requireProductRuntimeStore(root, it) }
    cleanupRemovedWorkspaceStore(root)
    journal.productRemoval?.let { cleanupProductRemoval(root, it) }
    removeUninstallRecovery(root, journal, observe)
    cleanupUninstallActivationLock(root, observe)
    finishUninstall(root, receipt, journal, observe)
    present("Removed Kast installation")
}

private fun admitUninstallPayload(root: Path, receipt: ManagementReceipt) {
    val payload = InstallationLayout.beneath(root).root
    requireUninstallRootInventory(root)
    requireUninstallExecutable(receipt, payload)
    if (!Files.exists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS)) return
    requireUninstallRetirement(root, receipt)
    val prior = readUninstallRetirement(root)
    if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) return
    if (prior.type == UninstallRetirementType.EXTERNALS_CLEANED)
        throw ManagementRejected("uninstall-retirement", "payload changed after recorded retirement")
    if (uninstallFilesystemIdentity(payload) != prior.controlIdentity)
        throw ManagementRejected("uninstall-retirement", "control identity changed after removal request")
}

private fun retireUninstallPayload(
    root: Path,
    home: Path,
    receipt: ManagementReceipt,
    executeInstaller: (Path, List<String>) -> Int,
    observe: (UninstallCleanupObservation) -> Unit,
    environment: Map<String, String>,
) {
    val payload = InstallationLayout.beneath(root).root
    observe(UninstallCleanupObservation.Started(UninstallArtifact.CONTROL_PAYLOAD))
    if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
        val script = prepareUninstallPayload(root, home, receipt, observe, environment)
        val args =
            listOf("--remove-product") +
                listOf(
                    "--install-root",
                    root.toString(),
                    "--uninstall-journal",
                    uninstallJournalPath(root).toString(),
                )
        val code =
            withRegistrationLock(root) {
                executeInstaller(script, args)
            }
        if (code != 0 || Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
            observe(
                if (code != 0) UninstallCleanupObservation.ControlChildRejected(code)
                else UninstallCleanupObservation.ControlPayloadRetained()
            )
            throw ManagementRejected(
                "uninstall-payload",
                "shutdown or managed removal did not complete (exit $code); retained cleanup receipt",
            )
        }
    }
    requireUninstallRetirement(root, receipt)
    if (readUninstallRetirement(root).type == UninstallRetirementType.REMOVAL_REQUESTED)
        throw ManagementRejected("uninstall-retirement", "control disappeared before retirement proof was committed")
    observe(UninstallCleanupObservation.Completed(UninstallArtifact.CONTROL_PAYLOAD))
}

private fun prepareUninstallPayload(
    root: Path,
    home: Path,
    receipt: ManagementReceipt,
    observe: (UninstallCleanupObservation) -> Unit,
    environment: Map<String, String>,
): Path {
    val payload = InstallationLayout.beneath(root).root
    if (Files.exists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS)) {
        val journal = readUninstallRetirement(root)
        val proof = journal.productRemoval
        if (proof != null) {
            resumeUninstallInventory(root, journal, observe)
            return when (proof.phase) {
                ProductRemovalPhase.PREPARING -> ownedUninstallInstaller(root)
                ProductRemovalPhase.PREPARED -> retainedProductInstaller(root, proof)
            }
        }
    }
    val script = ownedUninstallInstaller(root)
    val rootIdentity = captureUninstallManagedRoot(root)
    val recoveryProof =
        when (val admission = admitRecoveryRemoval(payload)) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> {
                observe(UninstallCleanupObservation.RecoveryRejected(admission.failure))
                throw ManagementRejected(
                    "uninstall-recovery",
                    "recovery ownership rejected: ${admission.failure}; retained installation",
                )
            }
        }
    requireRetainedUninstallInventory(root, recoveryProof, observe)
    val persist: (List<ManagedWorkspaceRemoval>) -> Unit = { workspaces ->
        persistUninstallRetirement(
            root,
            receipt,
            UninstallRetirementType.REMOVAL_REQUESTED,
            rootIdentity,
            recoveryProof,
            controlIdentity = uninstallFilesystemIdentity(payload),
            workspaceRemovals = workspaces,
        )
    }
    val workspaces = prepareProductWorkspaceRemoval(root, home, environment, observe, persist)
    removeWorkspaceProfiles(uninstallWorkspaceStore(root), workspaces)
    return script
}

private fun resumeUninstallInventory(
    root: Path,
    journal: UninstallRetirementJournal,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    requireRetainedUninstallInventory(root, journal.recoveryProof, observe)
    removeWorkspaceProfiles(uninstallWorkspaceStore(root), journal.workspaceRemovals)
}

private fun requireRetainedUninstallInventory(
    root: Path,
    recovery: io.github.amichne.kast.distribution.managed.ManagedRecoveryRemovalProof,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    if (Files.notExists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS)) return
    val prior = readUninstallRetirement(root)
    if (prior.recoveryProof != recovery) {
        observe(
            UninstallCleanupObservation.RecoveryRejected(
                io.github.amichne.kast.distribution.managed.RecoveryRemovalFailure.OWNERSHIP_UNPROVEN
            )
        )
        throw ManagementRejected("uninstall-recovery", "recovery changed after removal request; retained installation")
    }
}

private fun ownedUninstallInstaller(root: Path): Path {
    val payload = InstallationLayout.beneath(root).root
    val relative = InstallationLayout.Relative.INSTALL_CANDIDATE.value
    val script = InstallationLayout.Relative.INSTALL_CANDIDATE.under(payload)
    val manifest =
        when (val read = readBundledManifest(payload)) {
            is BundledManifestRead.Read -> read.manifest
            BundledManifestRead.Unavailable,
            BundledManifestRead.Invalid ->
                throw ManagementRejected("installation-admission", "private installer manifest is unavailable")
        }
    if (!payloadOwned(payload, manifest, relative))
        throw ManagementRejected("installation-admission", "private installer ownership is unproven")
    return script
}

private fun removeUninstallRecovery(
    root: Path,
    journal: UninstallRetirementJournal,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    observe(UninstallCleanupObservation.Started(UninstallArtifact.RECOVERY_BUNDLE))
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        when (val cleanup = removeRetiredRecovery(InstallationLayout.beneath(root).root, journal.recoveryProof)) {
            RecoveryRemovalOutcome.Removed -> Unit
            is RecoveryRemovalOutcome.Rejected -> {
                observe(UninstallCleanupObservation.RecoveryRejected(cleanup.failure))
                throw ManagementRejected(
                    "uninstall-recovery",
                    "recovery cleanup rejected: ${cleanup.failure}; retained cleanup journal",
                )
            }
        }
    observe(UninstallCleanupObservation.Completed(UninstallArtifact.RECOVERY_BUNDLE))
}

private fun finishUninstall(
    root: Path,
    receipt: ManagementReceipt,
    journal: UninstallRetirementJournal,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    val executable = Path.of(receipt.executable)
    val layout = InstallationLayout.beneath(root)
    requireUninstallExecutable(receipt, layout.root)
    removeUninstallArtifact(executable, UninstallArtifact.PUBLIC_EXECUTABLE, observe)
    removeUninstallArtifact(layout.management.registrationLock, UninstallArtifact.MANAGEMENT_LOCK, observe)
    persistUninstallRetirement(
        root,
        receipt,
        UninstallRetirementType.EXTERNALS_CLEANED,
        journal.managedRootIdentity,
        journal.recoveryProof,
    )
    removeUninstallArtifact(receiptPath(root), UninstallArtifact.MANAGEMENT_RECEIPT, observe)
    cleanupProductRemovalFence(root)
    cleanupEmptyUninstallRoot(root, journal.managedRootIdentity, observe)
    removeUninstallArtifact(uninstallJournalPath(root), UninstallArtifact.RETIREMENT_JOURNAL, observe)
}

private fun requireUninstallExecutable(receipt: ManagementReceipt, payload: Path) {
    val executable = Path.of(receipt.executable)
    if (!Files.exists(executable, LinkOption.NOFOLLOW_LINKS) && !Files.exists(payload, LinkOption.NOFOLLOW_LINKS))
        return
    if (
        !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) ||
            (Files.getAttribute(executable, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong() !=
                removalProcessUid ||
            sha256(executable) != receipt.executableSha256
    )
        throw ManagementRejected(
            "uninstall-executable",
            "public executable ownership is unproven; retained cleanup receipt",
        )
}

private fun removeUninstallArtifact(
    path: Path,
    artifact: UninstallArtifact,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    observe(UninstallCleanupObservation.Started(artifact))
    try {
        if (
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                    (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong() !=
                        removalProcessUid)
        ) {
            observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.OWNERSHIP_UNPROVEN))
            throw ManagementRejected(
                "uninstall-${artifact.name.lowercase()}",
                "artifact ownership is unproven; retained cleanup receipt",
            )
        }
        Files.deleteIfExists(path)
    } catch (_: java.io.IOException) {
        observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.FILESYSTEM_REJECTED))
        throw ManagementRejected(
            "uninstall-${artifact.name.lowercase()}",
            "filesystem refused cleanup; retained cleanup receipt",
        )
    }
    observe(UninstallCleanupObservation.Completed(artifact))
}
