package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.SerializationException

internal enum class LegacyConnectionRejection {
    RECORDED_SLOT_REJECTED,
    TRANSACTION_UNAVAILABLE,
    TRANSACTION_REJECTED,
    FILESYSTEM_IDENTITY_REJECTED,
    LEGACY_PAYLOAD_REJECTED,
    LEGACY_REGISTRATION_UNVERIFIED,
}

internal sealed interface LegacyConnectionAdmission {
    data class Admitted(val migration: LegacyMcpMigration) : LegacyConnectionAdmission

    data class Rejected(val failure: LegacyConnectionRejection) : LegacyConnectionAdmission
}

/** A prior command is authority only inside this exact, still-pending installer replacement. */
internal class LegacyMcpMigration
private constructor(
    val replacement: InstallationReplacementReceipt,
    val registration: ManagedRegistration,
) {
    val command: Path = Path.of(registration.destination)

    companion object {
        @Suppress("ReturnCount", "ThrowsCount", "ComplexCondition")
        fun admit(root: Path, recorded: ManagedRegistration): LegacyConnectionAdmission {
            val command = root.resolve("current/bin/kast-mcp-complete")
            if (recorded.connection != HarnessConnection.CODEX_MCP || recorded.destination != command.toString())
                return LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.RECORDED_SLOT_REJECTED)
            val transaction = root.resolve("recovery/replacement")
            val receipt = transaction.resolve("receipt.json")
            val raw =
                readBoundedFile(receipt, 65_536)
                    ?: return LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.TRANSACTION_UNAVAILABLE)
            return try {
                val document = managementJson.decodeFromString(InstallationReplacementReceipt.serializer(), raw)
                val previous = document.previous
                val installation = root.resolve("installation")
                if (previous !is PreviousInstallationPayload.Legacy || !compatibleReplacement(document, installation))
                    return LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.TRANSACTION_REJECTED)
                if (!replacementIdentity(root, transaction, document, previous))
                    return LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.FILESYSTEM_IDENTITY_REJECTED)
                val prior = Path.of(previous.installation)
                if (!legacyLauncher(prior, recorded.payloadSha256))
                    return LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.LEGACY_PAYLOAD_REJECTED)
                LegacyConnectionAdmission.Admitted(LegacyMcpMigration(document, recorded))
            } catch (_: SerializationException) {
                LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.TRANSACTION_REJECTED)
            } catch (_: IllegalArgumentException) {
                LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.TRANSACTION_REJECTED)
            } catch (_: IOException) {
                LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.FILESYSTEM_IDENTITY_REJECTED)
            } catch (_: SecurityException) {
                LegacyConnectionAdmission.Rejected(LegacyConnectionRejection.FILESYSTEM_IDENTITY_REJECTED)
            }
        }
    }
}

private fun compatibleReplacement(document: InstallationReplacementReceipt, installation: Path): Boolean =
    document.schemaVersion == 1 &&
        document.stage == InstallationReplacementStage.PAYLOAD_COMMITTED &&
        document.installation == installation.toString()

private fun replacementIdentity(
    root: Path,
    transaction: Path,
    document: InstallationReplacementReceipt,
    previous: PreviousInstallationPayload.Legacy,
): Boolean {
    val receipt = transaction.resolve("receipt.json")
    return ordinaryDirectory(transaction) &&
        receipt.toRealPath() == receipt &&
        physicalIdentity(root.resolve("installation"), document.installationIdentity) &&
        legacyIdentity(root, transaction, previous)
}

private fun legacyIdentity(root: Path, transaction: Path, previous: PreviousInstallationPayload.Legacy): Boolean {
    val selector = root.resolve("current")
    val target = Path.of(previous.target)
    val prior = root.resolve(target).normalize()
    return !target.isAbsolute &&
        target.nameCount == 2 &&
        target.getName(0).toString() == "versions" &&
        target.normalize() == target &&
        prior.parent == root.resolve("versions") &&
        previous.selector == selector.toString() &&
        previous.installation == prior.toString() &&
        previous.recovery == transaction.resolve("recovery").toString() &&
        Files.isSymbolicLink(selector) &&
        Files.readSymbolicLink(selector) == target &&
        physicalIdentity(prior, previous.identity)
}

private fun ordinaryDirectory(path: Path): Boolean =
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path

private fun physicalIdentity(path: Path, identity: InstallationFilesystemIdentity): Boolean =
    ordinaryDirectory(path) &&
        InstallationFilesystemIdentity(
            (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
            (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
            (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        ) == identity

private fun legacyLauncher(installation: Path, recordedDigest: String): Boolean {
    val source = installation.resolve("bin/kast-mcp-complete")
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || source.toRealPath() != source) return false
    val manifest =
        when (val read = readBundledManifest(installation)) {
            is BundledManifestRead.Read -> read.manifest
            BundledManifestRead.Invalid,
            BundledManifestRead.Unavailable -> return false
        }
    val entries = manifest.payloadFiles.filter { it.path == "bin/kast-mcp-complete" }
    return manifest.schemaVersion in 1..2 &&
        manifest.installationRoot == installation.toString() &&
        entries.size == 1 &&
        entries.single().sha256 == "sha256:$recordedDigest" &&
        sha256(source) == recordedDigest
}
