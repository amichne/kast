package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.RecoveryRemovalOutcome
import io.github.amichne.kast.distribution.managed.admitRecoveryRemoval
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
    executeHost: (List<String>) -> HostRemovalProcessObservation = ::executeHostRemoval,
) {
    val receipt = admittedUninstallReceipt(root)
    admitUninstallPayload(root, receipt)
    cleanupUninstalledUserState(home, observe) {
        retireUninstallPayload(root, home, receipt, executeInstaller, executeHost, observe)
    }
    val journal = readUninstallRetirement(root)
    removeUninstallRecovery(root, journal, observe)
    cleanupUninstallActivationLock(root, observe)
    removeUninstallRegistrations(root, home, receipt, observe)
    cleanupUninstallHomeConfiguration(home, journal.homeConfiguration, observe)
    finishUninstall(root, receipt, journal, observe)
    println("Removed Kast installation and owned registrations")
}

private fun admitUninstallPayload(root: Path, receipt: ManagementReceipt) {
    val payload = root.resolve("installation")
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
    executeHost: (List<String>) -> HostRemovalProcessObservation,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    val payload = root.resolve("installation")
    observe(UninstallCleanupObservation.Started(UninstallArtifact.CONTROL_PAYLOAD))
    if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
        val script = prepareUninstallPayload(root, home, receipt, executeHost, observe)
        val args =
            listOf(
                "uninstall",
                "--managed-registrations",
                "--install-root",
                root.toString(),
                "--uninstall-journal",
                uninstallJournalPath(root).toString(),
            )
        val code = withRegistrationLock(root) { executeInstaller(script, args) }
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
    executeHost: (List<String>) -> HostRemovalProcessObservation,
    observe: (UninstallCleanupObservation) -> Unit,
): Path {
    val payload = root.resolve("installation")
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
    val configuration = captureUninstallHomeConfiguration(home, observe)
    requireRetainedUninstallInventory(root, recoveryProof, configuration, observe)
    removeConfiguredUninstallHost(payload, home, observe, executeHost)
    persistUninstallRetirement(
        root,
        receipt,
        UninstallRetirementType.REMOVAL_REQUESTED,
        rootIdentity,
        recoveryProof,
        homeConfiguration = configuration,
        controlIdentity = uninstallFilesystemIdentity(payload),
    )
    return script
}

private fun requireRetainedUninstallInventory(
    root: Path,
    recovery: io.github.amichne.kast.distribution.managed.ManagedRecoveryRemovalProof,
    configuration: HomeConfigurationRemovalProof,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    if (Files.notExists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS)) return
    val prior = readUninstallRetirement(root)
    if (prior.homeConfiguration != configuration) {
        observe(UninstallCleanupObservation.HomeConfigurationRejected(HomeConfigurationCleanupFailure.CONTENT_CHANGED))
        throw ManagementRejected(
            "uninstall-home-configuration",
            "configuration changed after removal request; retained installation",
        )
    }
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
    val script = installedPrivateInstaller(root)
    val payload = root.resolve("installation")
    val manifest =
        when (val read = readBundledManifest(payload)) {
            is BundledManifestRead.Read -> read.manifest
            BundledManifestRead.Unavailable,
            BundledManifestRead.Invalid ->
                throw ManagementRejected("installation-admission", "private installer manifest is unavailable")
        }
    if (!payloadOwned(payload, manifest, "share/kast/install.sh"))
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
        when (val cleanup = removeRetiredRecovery(root.resolve("installation"), journal.recoveryProof)) {
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

private fun removeUninstallRegistrations(
    root: Path,
    home: Path,
    receipt: ManagementReceipt,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    val failed = mutableListOf<HarnessConnection>()
    for (registration in receipt.registrations) {
        val artifact = registration.connection.uninstallArtifact()
        observe(UninstallCleanupObservation.Started(artifact))
        try {
            disconnectConnection(root, home, registration.connection)
            observe(UninstallCleanupObservation.Completed(artifact))
        } catch (_: ManagementRejected) {
            observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.OWNERSHIP_UNPROVEN))
            failed += registration.connection
        } catch (_: java.io.IOException) {
            observe(UninstallCleanupObservation.Rejected(artifact, UninstallCleanupFailure.FILESYSTEM_REJECTED))
            failed += registration.connection
        }
    }
    if (failed.isNotEmpty())
        throw ManagementRejected(
            "uninstall-registrations",
            "owned registrations require cleanup: ${failed.joinToString { it.publicName }}",
        )
}

private fun finishUninstall(
    root: Path,
    receipt: ManagementReceipt,
    journal: UninstallRetirementJournal,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    val executable = Path.of(receipt.executable)
    requireUninstallExecutable(receipt, root.resolve("installation"))
    removeUninstallArtifact(executable, UninstallArtifact.PUBLIC_EXECUTABLE, observe)
    removeUninstallArtifact(root.resolve("management.lock"), UninstallArtifact.MANAGEMENT_LOCK, observe)
    persistUninstallRetirement(
        root,
        receipt,
        UninstallRetirementType.EXTERNALS_CLEANED,
        journal.managedRootIdentity,
        journal.recoveryProof,
    )
    removeUninstallArtifact(receiptPath(root), UninstallArtifact.MANAGEMENT_RECEIPT, observe)
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
                com.sun.security.auth.module.UnixSystem().uid ||
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
                        com.sun.security.auth.module.UnixSystem().uid)
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
