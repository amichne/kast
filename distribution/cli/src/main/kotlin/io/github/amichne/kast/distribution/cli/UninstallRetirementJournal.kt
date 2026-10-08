package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.managed.ManagedRecoveryRemovalProof
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

@Serializable
internal enum class UninstallRetirementType {
    REMOVAL_REQUESTED,
    CONTROL_RETIRED,
    EXTERNALS_CLEANED,
}

@Serializable
internal data class UninstallRetirementJournal(
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: ReleaseChannel,
    val type: UninstallRetirementType,
    val managedRootIdentity: InstallationFilesystemIdentity,
    val recoveryProof: ManagedRecoveryRemovalProof,
    val controlIdentity: InstallationFilesystemIdentity,
    val homeConfiguration: HomeConfigurationRemovalProof,
)

internal fun uninstallJournalPath(root: Path): Path {
    val digest =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(root.toString().toByteArray(Charsets.UTF_8))
            )
    return root.parent.resolve(".kast-uninstall-$digest.json")
}

internal fun requireUninstallRetirement(root: Path, receipt: ManagementReceipt) {
    val journal = readUninstallRetirement(root)
    if (
        journal !=
            retirementJournal(
                receipt,
                journal.type,
                journal.managedRootIdentity,
                journal.recoveryProof,
                journal.controlIdentity,
                journal.homeConfiguration,
            )
    )
        throw ManagementRejected("uninstall-retirement", "retirement proof belongs to another installation")
}

internal fun admittedUninstallReceipt(root: Path): ManagementReceipt =
    when (val read = readReceipt(root)) {
        is ReceiptRead.Read -> {
            if (
                (Files.getAttribute(receiptPath(root), "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong() !=
                    com.sun.security.auth.module.UnixSystem().uid
            )
                throw ManagementRejected("uninstall-receipt", "management receipt ownership is unproven")
            read.receipt
        }
        is ReceiptRead.Unavailable -> {
            if (Files.exists(receiptPath(root), LinkOption.NOFOLLOW_LINKS))
                throw ManagementRejected("uninstall-retirement", read.reason)
            val journal = readUninstallRetirement(root)
            when (journal.type) {
                UninstallRetirementType.REMOVAL_REQUESTED,
                UninstallRetirementType.CONTROL_RETIRED ->
                    throw ManagementRejected("uninstall-retirement", "registration cleanup proof is unavailable")
                UninstallRetirementType.EXTERNALS_CLEANED ->
                    ManagementReceipt(
                        2,
                        journal.installationRoot,
                        journal.executable,
                        journal.executableSha256,
                        journal.channel,
                        emptyList(),
                    )
            }
        }
    }

internal fun readUninstallRetirement(root: Path): UninstallRetirementJournal {
    val path = uninstallJournalPath(root)
    requireRetirementFileOwnership(path, root)
    val raw =
        readBoundedFile(path, 65536)
            ?: throw ManagementRejected(
                "uninstall-retirement",
                "retirement proof is unavailable; use verified force removal",
            )
    val journal = decodeUninstallRetirement(raw)
    requireRetirementRecordOwnership(root, journal)
    requireRetirementRootIdentity(root, journal)
    return journal
}

private fun requireRetirementFileOwnership(path: Path, root: Path) {
    if (
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.getOwner(path) != Files.getOwner(root.parent) ||
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) !=
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
    )
        throw ManagementRejected("uninstall-retirement", "retirement proof ownership is unproven")
}

private fun decodeUninstallRetirement(raw: String): UninstallRetirementJournal =
    try {
        managementJson.decodeFromString<UninstallRetirementJournal>(raw)
    } catch (_: SerializationException) {
        throw ManagementRejected("uninstall-retirement", "retirement proof is invalid; retained cleanup receipt")
    }

private fun requireRetirementRecordOwnership(root: Path, journal: UninstallRetirementJournal) {
    if (
        journal.installationRoot != root.toString() ||
            !validSha256(journal.executableSha256) ||
            !validRetirementExecutable(journal.executable)
    )
        throw ManagementRejected("uninstall-retirement", "retirement proof has invalid ownership")
}

private fun validRetirementExecutable(raw: String): Boolean =
    try {
        val executable = Path.of(raw)
        executable.isAbsolute && executable.normalize() == executable
    } catch (_: java.nio.file.InvalidPathException) {
        false
    }

private fun requireRetirementRootIdentity(root: Path, journal: UninstallRetirementJournal) {
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
        if (captureUninstallManagedRoot(root) != journal.managedRootIdentity)
            throw ManagementRejected("uninstall-retirement", "managed root identity changed after retirement")
    } else if (journal.type != UninstallRetirementType.EXTERNALS_CLEANED)
        throw ManagementRejected("uninstall-retirement", "managed root disappeared before cleanup completed")
}

internal fun persistUninstallRetirement(
    root: Path,
    receipt: ManagementReceipt,
    type: UninstallRetirementType = UninstallRetirementType.CONTROL_RETIRED,
    managedRootIdentity: InstallationFilesystemIdentity = captureUninstallManagedRoot(root),
    recoveryProof: ManagedRecoveryRemovalProof = ManagedRecoveryRemovalProof.Absent,
    homeConfiguration: HomeConfigurationRemovalProof = HomeConfigurationRemovalProof.Absent,
    controlIdentity: InstallationFilesystemIdentity =
        if (Files.exists(uninstallJournalPath(root), LinkOption.NOFOLLOW_LINKS))
            readUninstallRetirement(root).controlIdentity
        else managedRootIdentity,
) {
    val path = uninstallJournalPath(root)
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        requireUninstallRetirement(root, receipt)
        val prior = readUninstallRetirement(root)
        if (prior.type.ordinal >= type.ordinal) return
    }
    val prior = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) readUninstallRetirement(root) else null
    val staged = Files.createTempFile(root.parent, ".uninstall-", ".json")
    try {
        val bytes =
            managementJson
                .encodeToString(
                    retirementJournal(
                        receipt,
                        type,
                        prior?.managedRootIdentity ?: managedRootIdentity,
                        prior?.recoveryProof ?: recoveryProof,
                        prior?.controlIdentity ?: controlIdentity,
                        prior?.homeConfiguration ?: homeConfiguration,
                    )
                )
                .toByteArray()
        FileChannel.open(staged, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
    } finally {
        Files.deleteIfExists(staged)
    }
}

private fun retirementJournal(
    receipt: ManagementReceipt,
    type: UninstallRetirementType,
    managedRootIdentity: InstallationFilesystemIdentity,
    recoveryProof: ManagedRecoveryRemovalProof,
    controlIdentity: InstallationFilesystemIdentity,
    homeConfiguration: HomeConfigurationRemovalProof,
) =
    UninstallRetirementJournal(
        receipt.installationRoot,
        receipt.executable,
        receipt.executableSha256,
        receipt.channel,
        type,
        managedRootIdentity,
        recoveryProof,
        controlIdentity,
        homeConfiguration,
    )
