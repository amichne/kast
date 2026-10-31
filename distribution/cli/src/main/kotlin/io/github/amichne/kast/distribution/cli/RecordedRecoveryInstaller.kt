package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.ManagedControlReservation
import io.github.amichne.kast.distribution.managed.ControlInventoryAdmission
import io.github.amichne.kast.distribution.managed.ControlPayloadInventory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal sealed interface RecordedRecoveryInstallerAdmission {
    data class Admitted(val installer: RecordedRecoveryInstaller) : RecordedRecoveryInstallerAdmission

    data class Rejected(val reason: BuildSelectionFailure) : RecordedRecoveryInstallerAdmission
}

/** This capability can invoke recorded recovery only, never a new release selection. */
internal class RecordedRecoveryInstaller
private constructor(private val root: Path, private val home: Path, private val directory: Path) {
    fun recover(
        command: ManagementCommand.Upgrade,
        environment: Map<String, String>,
        execution: ManagedUpgradeExecution,
    ): ManagedUpgradeOutcome =
        try {
            val options = buildList {
                addAll(
                    listOf(
                        "/bin/bash",
                        directory.resolve(BRIDGE).toString(),
                        "--recover-only",
                        "--install-root",
                        root.toString(),
                    )
                )
                if (command.dryRun) add("--dry-run")
                if (command.json) add("--json")
            }
            ManagedUpgradeOutcome.ChildExited(
                execution.installer(options, directory, environment + ("HOME" to home.toString()))
            )
        } catch (_: IOException) {
            ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED)
        } catch (_: SecurityException) {
            ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED)
        }

    companion object {
        fun admit(root: Path, home: Path): RecordedRecoveryInstallerAdmission =
            try {
                readRecordedInstaller(root, home)
            } catch (_: ManagementRejected) {
                rejectedRecordedInstaller()
            } catch (_: IOException) {
                rejectedRecordedInstaller()
            } catch (_: SecurityException) {
                rejectedRecordedInstaller()
            } catch (_: SerializationException) {
                rejectedRecordedInstaller()
            } catch (_: IllegalArgumentException) {
                rejectedRecordedInstaller()
            }

        private fun readRecordedInstaller(root: Path, home: Path): RecordedRecoveryInstallerAdmission {
            val slot = root.resolve("recovery/replacement")
            val receipt = slot.resolve("receipt.json")
            val raw = readBoundedFile(receipt, ControlDistributionLimits.maximumReplacementReceiptBytes.toLong())
            if (raw == null)
                return RecordedRecoveryInstallerAdmission.Rejected(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED)
            val owner = captureUninstallManagedRoot(root).owner
            if (!physicalDirectory(home) || uninstallFilesystemIdentity(home).owner != owner)
                return rejectedRecordedInstaller()
            if (!ownedJournal(root, slot, receipt, owner)) return rejectedRecordedInstaller()
            val record = managementJson.decodeFromString(InstallationReplacementReceipt.serializer(), raw)
            val reservation = record.reservation
            if (
                record.schemaVersion != 1 ||
                    record.installation != root.resolve("installation").toString() ||
                    record.installationIdentity.owner != owner
            )
                return rejectedRecordedInstaller()
            return when (reservation) {
                null -> rejectedRecordedInstaller()
                else -> admitCandidate(root, home, slot, receipt, record, reservation)
            }
        }

        private fun admitCandidate(
            root: Path,
            home: Path,
            slot: Path,
            receipt: Path,
            record: InstallationReplacementReceipt,
            reservation: ManagedControlReservation,
        ): RecordedRecoveryInstallerAdmission {
            val staged = Path.of(reservation.staged)
            if (
                staged.parent != root ||
                    staged.normalize() != staged ||
                    !staged.fileName.toString().startsWith(".install-")
            )
                return rejectedRecordedInstaller()
            val candidates =
                listOf(staged, root.resolve("installation"), slot.resolve("rejected-control")).filter {
                    physicalDirectory(it) && uninstallFilesystemIdentity(it) == record.installationIdentity
                }
            if (candidates.size != 1) return rejectedRecordedInstaller()
            return inspectCandidate(root, home, receipt, record, reservation, candidates.single())
        }

        private fun inspectCandidate(
            root: Path,
            home: Path,
            receipt: Path,
            record: InstallationReplacementReceipt,
            reservation: ManagedControlReservation,
            candidate: Path,
        ): RecordedRecoveryInstallerAdmission {
            val manifestPath = candidate.resolve("installation.json")
            val raw = readBoundedFile(manifestPath, ControlDistributionLimits.maximumManifestBytes.toLong())
            if (raw == null) return rejectedRecordedInstaller()
            if (!boundManifest(manifestPath, raw, reservation, record.installationIdentity.owner))
                return rejectedRecordedInstaller()
            val manifest = recoveryManifestJson.decodeFromString(RecordedControlManifest.serializer(), raw)
            return if (
                admittedPayload(
                    candidate,
                    root.resolve("installation"),
                    manifest,
                    reservation,
                    record.installationIdentity.owner,
                ) &&
                    candidateUnchanged(candidate, manifestPath, record, reservation) &&
                    recordUnchanged(receipt, record)
            )
                RecordedRecoveryInstallerAdmission.Admitted(RecordedRecoveryInstaller(root, home, candidate))
            else rejectedRecordedInstaller()
        }
    }
}

@Serializable
private data class RecordedControlManifest(
    val schemaVersion: Int,
    val semanticVersion: String,
    val installationRoot: String,
    val controlSha256: String,
    val payloadFiles: List<BundledPayload>,
)

private fun admittedPayload(
    candidate: Path,
    logicalRoot: Path,
    manifest: RecordedControlManifest,
    reservation: ManagedControlReservation,
    owner: Long,
): Boolean {
    if (!logicalLayoutMatches(manifest, logicalRoot) || !releaseIdentityMatches(manifest, reservation)) return false
    val expected = manifest.payloadFiles.map { it.path }.toSet()
    if (expected.size != manifest.payloadFiles.size || BRIDGE !in expected || CAPABILITY !in expected) return false
    val inventory =
        when (val result = ControlPayloadInventory.admit(candidate)) {
            is ControlInventoryAdmission.Admitted -> result
            is ControlInventoryAdmission.Rejected -> return false
        }
    if (inventory.files.map { candidate.relativize(it).toString().replace('\\', '/') }.toSet() != expected) return false
    val ownership = BundledManifest(manifest.schemaVersion, manifest.installationRoot, manifest.payloadFiles)
    return manifest.payloadFiles.all { entry ->
        val path = candidate.resolve(entry.path)
        uninstallFilesystemIdentity(path).owner == owner &&
            payloadModeMatches(path, entry.mode) &&
            payloadOwned(candidate, ownership, entry.path, logicalRoot)
    } && readBoundedFile(candidate.resolve(CAPABILITY), MAXIMUM_CAPABILITY_BYTES) == "1\n"
}

private fun ownedJournal(root: Path, slot: Path, receipt: Path, owner: Long): Boolean =
    listOf(root, slot).all(::physicalDirectory) &&
        receipt.toRealPath() == receipt &&
        listOf(root, receipt).all { uninstallFilesystemIdentity(it).owner == owner }

private fun boundManifest(path: Path, raw: String, reservation: ManagedControlReservation, owner: Long): Boolean =
    sha256Text(raw) == reservation.manifestSha256 &&
        path.toRealPath() == path &&
        uninstallFilesystemIdentity(path).owner == owner

private fun candidateUnchanged(
    candidate: Path,
    manifest: Path,
    record: InstallationReplacementReceipt,
    reservation: ManagedControlReservation,
): Boolean =
    uninstallFilesystemIdentity(candidate) == record.installationIdentity &&
        sha256(manifest) == reservation.manifestSha256

private fun recordUnchanged(receipt: Path, record: InstallationReplacementReceipt): Boolean =
    readBoundedFile(receipt, ControlDistributionLimits.maximumReplacementReceiptBytes.toLong())?.let {
        managementJson.decodeFromString(InstallationReplacementReceipt.serializer(), it)
    } == record

private fun logicalLayoutMatches(manifest: RecordedControlManifest, logicalRoot: Path): Boolean =
    manifest.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
        manifest.installationRoot == logicalRoot.toString() &&
        manifest.payloadFiles.size <= ControlDistributionLimits.maximumPayloadFiles

private fun releaseIdentityMatches(manifest: RecordedControlManifest, reservation: ManagedControlReservation): Boolean =
    manifest.semanticVersion == reservation.version && manifest.controlSha256 == "sha256:${reservation.controlSha256}"

private fun physicalDirectory(path: Path): Boolean =
    Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path

private fun payloadModeMatches(path: Path, expected: Int): Boolean =
    when (val mode = Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS)) {
        is Number -> mode.toInt() and UNIX_PERMISSION_MASK == expected
        else -> false
    }

private fun rejectedRecordedInstaller() =
    RecordedRecoveryInstallerAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)

private val recoveryManifestJson = Json { ignoreUnknownKeys = true }
private const val BRIDGE = "share/kast/install-candidate.sh"
private const val CAPABILITY = "share/kast/managed-install-v1"

private const val MAXIMUM_CAPABILITY_BYTES = 4L
private const val UNIX_PERMISSION_MASK = 0xFFF
