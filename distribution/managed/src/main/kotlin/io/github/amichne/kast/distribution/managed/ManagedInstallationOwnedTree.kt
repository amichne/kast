package io.github.amichne.kast.distribution.managed

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class ManagedInstallationTreeKind(val directoryName: String) {
    STATE("state"),
    RUNTIME_PAYLOADS("runtime-payloads"),
}

/** An exact destructive-recovery tree proven to belong to one physical installation. */
class ManagedInstallationOwnedTree private constructor(private val path: Path) {
    companion object {
        fun admit(
            installationDirectory: Path,
            kind: ManagedInstallationTreeKind,
            candidate: Path,
        ): ManagedInstallationOwnedTreeAdmission {
            val installation = installationDirectory.normalize()
            val tree = candidate.normalize()
            if (!installation.isAbsolute || !tree.isAbsolute || tree != installation.resolve(kind.directoryName)) {
                return ManagedInstallationOwnedTreeAdmission.Rejected
            }
            val physicalInstallation =
                try {
                    if (Files.isSymbolicLink(installation)) return ManagedInstallationOwnedTreeAdmission.Rejected
                    installation.toRealPath()
                } catch (_: IOException) {
                    return ManagedInstallationOwnedTreeAdmission.Rejected
                } catch (_: SecurityException) {
                    return ManagedInstallationOwnedTreeAdmission.Rejected
                }
            return if (physicalInstallation == installation) {
                ManagedInstallationOwnedTreeAdmission.Admitted(ManagedInstallationOwnedTree(tree))
            } else {
                ManagedInstallationOwnedTreeAdmission.Rejected
            }
        }
    }

    fun delete(): ManagedInstallationOwnedTreeDeletion =
        try {
            when {
                Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> Unit
                Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.delete(path)
                else -> Files.walkFileTree(path, DeletingVisitor)
            }
            ManagedInstallationOwnedTreeDeletion.Deleted
        } catch (_: IOException) {
            ManagedInstallationOwnedTreeDeletion.Rejected
        } catch (_: SecurityException) {
            ManagedInstallationOwnedTreeDeletion.Rejected
        }

    private object DeletingVisitor : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
            if (failure != null) throw failure
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    }
}

sealed interface ManagedInstallationOwnedTreeAdmission {
    data class Admitted(val tree: ManagedInstallationOwnedTree) : ManagedInstallationOwnedTreeAdmission

    data object Rejected : ManagedInstallationOwnedTreeAdmission
}

enum class ManagedInstallationOwnedTreeDeletion {
    Deleted,
    Rejected,
}
