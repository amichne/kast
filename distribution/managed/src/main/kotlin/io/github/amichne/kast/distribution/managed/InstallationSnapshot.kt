package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.configuration.InstallationOperationalLimits
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Only mutable state and a qualified recovery bundle are copied during physical installation replacement. */
enum class InstallationSnapshotKind {
    STATE,
    RECOVERY,
}

enum class InstallationSnapshotFailure {
    SOURCE_REJECTED,
    DESTINATION_REJECTED,
    ENTRY_REJECTED,
    SOURCE_CHANGED,
    LIMIT_EXCEEDED,
    IO_REJECTED,
}

sealed interface InstallationSnapshot {
    data object Copied : InstallationSnapshot

    data class Rejected(val failure: InstallationSnapshotFailure) : InstallationSnapshot
}

/** Copies into one absent owned transaction slot; no symbolic links or special files are followed or retained. */
fun copyInstallationSnapshot(source: Path, destination: Path, kind: InstallationSnapshotKind): InstallationSnapshot {
    return try {
        if (!physicalSnapshotDirectory(source))
            return InstallationSnapshot.Rejected(InstallationSnapshotFailure.SOURCE_REJECTED)
        if (
            Files.exists(destination, LinkOption.NOFOLLOW_LINKS) ||
                !physicalSnapshotDirectory(destination.parent) ||
                !validSnapshotSlot(source, destination, kind)
        )
            return InstallationSnapshot.Rejected(InstallationSnapshotFailure.DESTINATION_REJECTED)
        val entries =
            Files.walk(source).use { it.limit(InstallationOperationalLimits.stateMaximumEntries + 1L).toList() }
        if (entries.size > InstallationOperationalLimits.stateMaximumEntries)
            return InstallationSnapshot.Rejected(InstallationSnapshotFailure.LIMIT_EXCEEDED)
        val witnesses = entries.map { path ->
            SnapshotEntry(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
        }
        if (witnesses.any { !it.attributes.isDirectory && !it.attributes.isRegularFile })
            return InstallationSnapshot.Rejected(InstallationSnapshotFailure.ENTRY_REJECTED)
        copySnapshotEntries(source, destination, witnesses)
    } catch (_: java.io.IOException) {
        InstallationSnapshot.Rejected(InstallationSnapshotFailure.IO_REJECTED)
    } catch (_: SecurityException) {
        InstallationSnapshot.Rejected(InstallationSnapshotFailure.IO_REJECTED)
    }
}

private fun copySnapshotEntries(source: Path, destination: Path, entries: List<SnapshotEntry>): InstallationSnapshot {
    for (entry in entries) when (val copied = copySnapshotEntry(source, destination, entry)) {
        InstallationSnapshot.Copied -> Unit
        is InstallationSnapshot.Rejected -> return copied
    }
    return InstallationSnapshot.Copied
}

private fun validSnapshotSlot(source: Path, destination: Path, kind: InstallationSnapshotKind): Boolean =
    when (kind) {
        InstallationSnapshotKind.STATE -> validStateSlot(source, destination)
        InstallationSnapshotKind.RECOVERY -> validRecoverySlot(source, destination)
    }

private fun validStateSlot(source: Path, destination: Path): Boolean =
    stateSlotNames(source, destination) && managedSnapshotRoot(source.parent) == destination.parent.parent

private fun stateSlotNames(source: Path, destination: Path): Boolean =
    source.fileName.toString() == "state" &&
        destination.fileName.toString() == "state" &&
        destination.parent.fileName.toString().startsWith(".install-")

private fun validRecoverySlot(source: Path, destination: Path): Boolean =
    recoverySlotNames(source, destination) && destination.parent.parent == source.parent

private fun recoverySlotNames(source: Path, destination: Path): Boolean =
    source.parent.fileName.toString() == "recovery" &&
        destination.fileName.toString() == "recovery" &&
        destination.parent.fileName.toString() == "replacement"

private fun managedSnapshotRoot(installation: Path): Path =
    if (installation.parent.fileName.toString() == "versions") installation.parent.parent else installation.parent

private data class SnapshotEntry(val path: Path, val attributes: BasicFileAttributes)

private fun copySnapshotEntry(source: Path, destination: Path, entry: SnapshotEntry): InstallationSnapshot {
    if (!sameSnapshotEntry(entry)) return InstallationSnapshot.Rejected(InstallationSnapshotFailure.SOURCE_CHANGED)
    val target = destination.resolve(source.relativize(entry.path))
    if (entry.attributes.isDirectory) {
        Files.createDirectory(target)
        Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(entry.path, LinkOption.NOFOLLOW_LINKS))
    } else Files.copy(entry.path, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
    return if (Files.isSymbolicLink(target) || !sameSnapshotEntry(entry))
        InstallationSnapshot.Rejected(InstallationSnapshotFailure.SOURCE_CHANGED)
    else InstallationSnapshot.Copied
}

private fun sameSnapshotEntry(entry: SnapshotEntry): Boolean {
    val observed = Files.readAttributes(entry.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    return sameSnapshotKey(observed, entry.attributes) &&
        observed.size() == entry.attributes.size() &&
        observed.lastModifiedTime() == entry.attributes.lastModifiedTime()
}

private fun physicalSnapshotDirectory(path: Path): Boolean =
    normalizedSnapshotPath(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path

private fun sameSnapshotKey(observed: BasicFileAttributes, expected: BasicFileAttributes): Boolean =
    observed.fileKey() != null && observed.fileKey() == expected.fileKey()

private fun normalizedSnapshotPath(path: Path): Boolean = path.isAbsolute && path.normalize() == path
