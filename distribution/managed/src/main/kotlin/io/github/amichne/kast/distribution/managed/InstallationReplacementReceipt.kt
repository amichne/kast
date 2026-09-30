package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json

enum class InstallationReplacementWriteFailure {
    OWNERSHIP_REJECTED,
    IO_REJECTED,
}

sealed interface InstallationReplacementWrite {
    data object Written : InstallationReplacementWrite

    data class Rejected(val failure: InstallationReplacementWriteFailure) : InstallationReplacementWrite
}

fun writeInstallationReplacementReceipt(
    directory: Path,
    receipt: InstallationReplacementReceipt,
): InstallationReplacementWrite {
    return try {
        if (!ownedReplacementSlot(directory, receipt))
            return InstallationReplacementWrite.Rejected(InstallationReplacementWriteFailure.OWNERSHIP_REJECTED)
        writeReplacementRecord(directory, receipt)
        InstallationReplacementWrite.Written
    } catch (_: java.io.IOException) {
        InstallationReplacementWrite.Rejected(InstallationReplacementWriteFailure.IO_REJECTED)
    } catch (_: SecurityException) {
        InstallationReplacementWrite.Rejected(InstallationReplacementWriteFailure.IO_REJECTED)
    }
}

private fun ownedReplacementSlot(directory: Path, receipt: InstallationReplacementReceipt): Boolean =
    replacementSlotNames(directory) &&
        physicalReplacementDirectory(directory) &&
        receipt.installation == directory.parent.parent.resolve("installation").toString()

private fun replacementSlotNames(directory: Path): Boolean =
    directory.isAbsolute &&
        directory.fileName.toString() == "replacement" &&
        directory.parent.fileName.toString() == "recovery"

private fun writeReplacementRecord(directory: Path, receipt: InstallationReplacementReceipt) {
    val temporary = Files.createTempFile(directory, ".receipt-", ".tmp")
    try {
        Files.writeString(
            temporary,
            replacementJson.encodeToString(InstallationReplacementReceipt.serializer(), receipt),
        )
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(
            temporary,
            directory.resolve("receipt.json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private val replacementJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private fun physicalReplacementDirectory(path: Path): Boolean =
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path
