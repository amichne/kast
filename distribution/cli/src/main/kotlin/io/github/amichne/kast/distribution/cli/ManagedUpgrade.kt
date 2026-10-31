package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal sealed interface ManagedUpgradeOutcome {
    data class ChildExited(val code: Int) : ManagedUpgradeOutcome

    data class Rejected(val reason: BuildSelectionFailure) : ManagedUpgradeOutcome

    data class RecoveryRequired(val reason: BuildSelectionFailure) : ManagedUpgradeOutcome
}

internal data class ManagedUpgradeExecution(
    val readRelease: (String) -> ReleaseDocumentRead = ::readReleaseDocument,
    val installer: (List<String>, Path, Map<String, String>) -> Int = ::runOwnedPublicQueryChild,
    val notice: (ManagedUpgradeNotice) -> Unit = ::printUpgradeNotice,
)

internal enum class ManagedUpgradeNotice {
    CAPTURED_SELECTION_CLEANUP_FAILED
}

private fun printUpgradeNotice(notice: ManagedUpgradeNotice) {
    when (notice) {
        ManagedUpgradeNotice.CAPTURED_SELECTION_CLEANUP_FAILED ->
            System.err.println("kast: upgrade: temporary selection cleanup failed")
    }
}

internal fun upgradeSelectedBuild(
    root: Path,
    home: Path,
    command: ManagementCommand.Upgrade,
    environment: Map<String, String>,
) {
    when (val outcome = executeManagedUpgrade(root, home, command, environment)) {
        is ManagedUpgradeOutcome.ChildExited -> if (outcome.code != 0) kotlin.system.exitProcess(outcome.code)
        is ManagedUpgradeOutcome.Rejected -> presentUpgradeRejection(outcome.reason, command.json, "REJECTED")
        is ManagedUpgradeOutcome.RecoveryRequired ->
            presentUpgradeRejection(outcome.reason, command.json, "RECOVERY_REQUIRED")
    }
}

/** The child consumes one captured selection and owns its terminal result. */
internal fun executeManagedUpgrade(
    root: Path,
    home: Path,
    command: ManagementCommand.Upgrade,
    environment: Map<String, String>,
    execution: ManagedUpgradeExecution = ManagedUpgradeExecution(),
): ManagedUpgradeOutcome {
    if (pendingInstallationRecords(root).records.isNotEmpty())
        return recoverManagedUpgrade(root, home, command, environment, execution)
    val prior = requireOwnedExecutable(root)
    val request =
        when (val result = resolveRecordedPolicy(command.request, prior.updatePolicy)) {
            is SelectionAdmission.Admitted -> result.value
            is SelectionAdmission.Rejected -> return ManagedUpgradeOutcome.Rejected(result.reason)
        }
    val selection =
        when (val result = resolveBuildSelection(request, execution.readRelease)) {
            is SelectionAdmission.Admitted -> result.value
            is SelectionAdmission.Rejected -> return ManagedUpgradeOutcome.Rejected(result.reason)
        }
    val installation = selectedInstallation(root)
    val bridge =
        admittedInstallerBridge(installation)
            ?: return ManagedUpgradeOutcome.Rejected(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED)
    val captured = Files.createTempFile("kast-selection-", ".json")
    return try {
        Files.writeString(captured, managementJson.encodeToString(selection))
        val options = buildList {
            addAll(listOf("--resolved-selection", captured.toString(), "--install-root", root.toString()))
            command.ideaHome?.let { addAll(listOf("--idea-home", it)) }
            if (command.restartIde) add("--restart-ide")
            addAll(upgradeOutputOptions(command))
        }
        invokeUpgradeInstaller(bridge, options, installation, home, environment, execution)
    } finally {
        discardCapturedSelection(captured, execution.notice)
    }
}

private fun discardCapturedSelection(captured: Path, notice: (ManagedUpgradeNotice) -> Unit) {
    try {
        Files.deleteIfExists(captured)
    } catch (_: IOException) {
        notice(ManagedUpgradeNotice.CAPTURED_SELECTION_CLEANUP_FAILED)
    } catch (_: SecurityException) {
        notice(ManagedUpgradeNotice.CAPTURED_SELECTION_CLEANUP_FAILED)
    }
}

private fun recoverManagedUpgrade(
    root: Path,
    home: Path,
    command: ManagementCommand.Upgrade,
    environment: Map<String, String>,
    execution: ManagedUpgradeExecution,
): ManagedUpgradeOutcome {
    return try {
        val installation = selectedInstallation(root)
        val bridge = admittedInstallerBridge(installation) ?: return recoveryBridgeUnavailable()
        val options = listOf("--recover-only", "--install-root", root.toString()) + upgradeOutputOptions(command)
        when (val outcome = invokeUpgradeInstaller(bridge, options, installation, home, environment, execution)) {
            is ManagedUpgradeOutcome.Rejected -> ManagedUpgradeOutcome.RecoveryRequired(outcome.reason)
            is ManagedUpgradeOutcome.ChildExited,
            is ManagedUpgradeOutcome.RecoveryRequired -> outcome
        }
    } catch (_: ManagementRejected) {
        recoveryBridgeUnavailable()
    } catch (_: IOException) {
        recoveryBridgeUnavailable()
    } catch (_: SecurityException) {
        recoveryBridgeUnavailable()
    }
}

private fun recoveryBridgeUnavailable(): ManagedUpgradeOutcome.RecoveryRequired =
    ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED)

private fun admittedInstallerBridge(installation: Path): Path? {
    val manifest = readBundledManifest(installation)
    val relative = "share/kast/install-candidate.sh"
    return if (manifest is BundledManifestRead.Read && payloadOwned(installation, manifest.manifest, relative))
        installation.resolve(relative)
    else null
}

private fun upgradeOutputOptions(command: ManagementCommand.Upgrade): List<String> = buildList {
    if (command.dryRun) add("--dry-run")
    if (command.json) add("--json")
}

private fun invokeUpgradeInstaller(
    bridge: Path,
    options: List<String>,
    installation: Path,
    home: Path,
    environment: Map<String, String>,
    execution: ManagedUpgradeExecution,
): ManagedUpgradeOutcome =
    try {
        ManagedUpgradeOutcome.ChildExited(
            execution.installer(
                listOf("/bin/bash", bridge.toString()) + options,
                installation,
                environment + ("HOME" to home.toString()),
            )
        )
    } catch (_: IOException) {
        ManagedUpgradeOutcome.Rejected(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED)
    }

@Serializable private data class CoreUpgradeRejection(val reason: BuildSelectionFailure, val type: String)

private fun presentUpgradeRejection(reason: BuildSelectionFailure, json: Boolean, type: String) {
    if (json) println(managementJson.encodeToString(CoreUpgradeRejection(reason, type)))
    System.err.println("kast: upgrade: $reason")
    kotlin.system.exitProcess(1)
}
