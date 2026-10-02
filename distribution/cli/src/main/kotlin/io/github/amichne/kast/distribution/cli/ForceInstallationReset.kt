package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal sealed interface ForceResetRootAdmission {
    data class Selected(val root: ForceResetRoot) : ForceResetRootAdmission

    data object Rejected : ForceResetRootAdmission
}

internal sealed interface ForceResetDeletion {
    data object Removed : ForceResetDeletion

    data class Rejected(val failure: ForceResetFailure) : ForceResetDeletion
}

/** Path scope is independent of ownership. The selected directory can contain arbitrary or corrupt bytes. */
internal class ForceResetRoot private constructor(val path: Path) {
    companion object {
        fun admit(root: Path, home: Path): ForceResetRootAdmission {
            if (!root.isAbsolute || root.normalize() != root || root.parent == null)
                return ForceResetRootAdmission.Rejected
            if (!home.isAbsolute || home.normalize() != home || home.startsWith(root))
                return ForceResetRootAdmission.Rejected
            return try {
                // An ancestor symlink cannot turn a selected child into HOME or an ancestor of HOME.
                val ancestor = generateSequence(root.parent) { it.parent }.first { Files.exists(it, NOFOLLOW_LINKS) }
                val physical = ancestor.toRealPath().resolve(ancestor.relativize(root))
                if (physical != root || home.toRealPath().startsWith(physical)) ForceResetRootAdmission.Rejected
                else ForceResetRootAdmission.Selected(ForceResetRoot(physical))
            } catch (_: IOException) {
                ForceResetRootAdmission.Rejected
            } catch (_: SecurityException) {
                ForceResetRootAdmission.Rejected
            }
        }
    }

    fun erase(quiescent: QuiescentReset): ForceResetDeletion {
        when (val admitted = quiescent.fenced.fence.admission()) {
            ResetEffect.Completed -> Unit
            is ResetEffect.Rejected -> return ForceResetDeletion.Rejected(admitted.failure)
        }
        if (quiescent.fenced.root !== this) return ForceResetDeletion.Rejected(ForceResetFailure.ROOT_REJECTED)
        if (!Files.notExists(path, NOFOLLOW_LINKS)) return ForceResetDeletion.Rejected(ForceResetFailure.RESET_BUSY)
        val storage = quiescent.fenced.storage
        return try {
            when (storage) {
                RetainedResetStorage.Missing -> Unit
                is RetainedResetStorage.Held -> {
                    Files.walkFileTree(storage.directory.resolve("payload"), ResetDeletingVisitor)
                    Files.delete(storage.directory)
                }
            }
            if (Files.notExists(path, NOFOLLOW_LINKS)) ForceResetDeletion.Removed
            else ForceResetDeletion.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: IOException) {
            ForceResetDeletion.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            ForceResetDeletion.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    }
}

private object ResetDeletingVisitor : SimpleFileVisitor<Path>() {
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
