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
    present: (String) -> Unit = ::println,
    environment: Map<String, String> = emptyMap(),
    executeProvider: (Map<String, String>, List<String>) -> ProcessObservation = { selected, arguments ->
        runGuidanceProvider(arguments, selected)
    },
) {
    val receipt = admittedUninstallReceipt(root)
    val remove = {
        admitUninstallPayload(root, receipt)
        if (receipt.providers.isNotEmpty()) removeInstallationProviders(root, home, environment, executeProvider)
        removeInstallation(root, home, receipt, executeInstaller, observe, executeHost, present, environment)
        Refinement.Refined(Unit)
    }
    when (
        val removed =
            if (receipt.product == null) remove()
            else ContainerQueryLease.duringStoreRemoval(root.resolveSibling("kast-workspaces"), remove)
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
    executeHost: (List<String>) -> HostRemovalProcessObservation,
    present: (String) -> Unit,
    environment: Map<String, String>,
) {
    cleanupUninstalledUserState(home, observe) {
        retireUninstallPayload(root, home, receipt, executeInstaller, executeHost, observe, environment)
    }
    val journal = readUninstallRetirement(root)
    journal.productRemoval?.let { requireProductRuntimeStore(root, it) }
    if (receipt.product != null) cleanupRemovedWorkspaceStore(root)
    journal.productRemoval?.let { cleanupProductRemoval(root, it) }
    removeUninstallRecovery(root, journal, observe)
    cleanupUninstallActivationLock(root, observe)
    removeUninstallRegistrations(root, home, receipt, observe)
    cleanupUninstallHomeConfiguration(home, journal.homeConfiguration, observe)
    finishUninstall(root, receipt, journal, observe)
    present("Removed Kast installation and owned registrations")
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
    environment: Map<String, String>,
) {
    val payload = root.resolve("installation")
    observe(UninstallCleanupObservation.Started(UninstallArtifact.CONTROL_PAYLOAD))
    if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
        val script = prepareUninstallPayload(root, home, receipt, executeHost, observe, environment)
        val args =
            (if (receipt.product == null) listOf("uninstall", "--managed-registrations")
            else listOf("--remove-product")) +
                listOf(
                    "--install-root",
                    root.toString(),
                    "--uninstall-journal",
                    uninstallJournalPath(root).toString(),
                )
        val code =
            withRegistrationLock(root) {
                requireProvidersRemoved(root)
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
    executeHost: (List<String>) -> HostRemovalProcessObservation,
    observe: (UninstallCleanupObservation) -> Unit,
    environment: Map<String, String>,
): Path {
    val payload = root.resolve("installation")
    if (receipt.product != null && Files.exists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS)) {
        val journal = readUninstallRetirement(root)
        val proof = journal.productRemoval
        if (proof != null) {
            resumeUninstallInventory(root, home, journal, observe)
            return when (proof.phase) {
                ProductRemovalPhase.PREPARING -> ownedUninstallInstaller(root, true)
                ProductRemovalPhase.PREPARED -> retainedProductInstaller(root, proof)
            }
        }
    }
    val script = ownedUninstallInstaller(root, receipt.product != null)
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
    val persist: (List<ManagedWorkspaceRemoval>) -> Unit = { workspaces ->
        persistUninstallRetirement(
            root,
            receipt,
            UninstallRetirementType.REMOVAL_REQUESTED,
            rootIdentity,
            recoveryProof,
            homeConfiguration = configuration,
            controlIdentity = uninstallFilesystemIdentity(payload),
            workspaceRemovals = workspaces,
        )
    }
    val workspaces =
        if (receipt.product == null) {
            removeConfiguredUninstallHost(payload, home, observe, executeHost)
            persist(emptyList())
            emptyList()
        } else prepareProductWorkspaceRemoval(root, home, environment, observe, persist)
    if (receipt.product != null) removeWorkspaceProfiles(uninstallWorkspaceStore(root), workspaces)
    return script
}

private fun resumeUninstallInventory(
    root: Path,
    home: Path,
    journal: UninstallRetirementJournal,
    observe: (UninstallCleanupObservation) -> Unit,
) {
    requireRetainedUninstallInventory(
        root,
        journal.recoveryProof,
        captureUninstallHomeConfiguration(home, observe),
        observe,
    )
    removeWorkspaceProfiles(uninstallWorkspaceStore(root), journal.workspaceRemovals)
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

private fun ownedUninstallInstaller(root: Path, product: Boolean): Path {
    val payload = root.resolve("installation")
    val relative = if (product) "share/kast/install-candidate.sh" else "share/kast/install.sh"
    val script = payload.resolve(relative)
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
    for (guidance in receipt.guidance) {
        when (val removal = withRegistrationLock(root) { removeRecordedGuidance(root, guidance.harness) }) {
            is PluginObservation.Observed -> Unit
            is PluginObservation.Rejected ->
                throw ManagementRejected("uninstall-guidance", pluginFailure(removal.failure).name)
        }
    }
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
    if (receipt.product != null) cleanupProductRemovalFence(root)
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
