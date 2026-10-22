package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.installationResetFence
import io.github.amichne.kast.distribution.managed.PriorInstallationPreparation
import io.github.amichne.kast.distribution.managed.resetInstallationTransport
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

    /** The selected reset lock remains held until these effects and final process retirement finish. */
    fun erasePublicExterior(scope: PublicResetScope, erased: ErasedReset, runtime: ForceDaemonRuntime): ResetEffect {
        if (erased.root != path || scope.root != path) return ResetEffect.Rejected(ForceResetFailure.ROOT_REJECTED)
        when (val admitted = erased.fence.admission()) {
            ResetEffect.Completed -> Unit
            is ResetEffect.Rejected -> return admitted
        }
        return try {
            val marker = installationResetFence(path)
            val artifacts = retainedArtifacts(marker)
            if (artifacts.size > MAXIMUM_PUBLIC_RESET_ENTRIES)
                return ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
            val launcherArtifacts = launcherArtifacts(scope)
            if (launcherArtifacts.size > MAXIMUM_PUBLIC_RESET_ENTRIES)
                return ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
            for (path in artifacts + launcherArtifacts + scope.historicalPaths) {
                if (ForceResetRoot.admit(path, scope.home) == ForceResetRootAdmission.Rejected)
                    return ResetEffect.Rejected(ForceResetFailure.ROOT_REJECTED)
                when (val removed = removeHistoricalEntry(path, scope.home, runtime)) {
                    ResetEffect.Completed -> Unit
                    is ResetEffect.Rejected -> return removed
                }
            }
            removeCommands(scope)
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun retainedArtifacts(marker: Path): List<Path> =
        Files.newDirectoryStream(path.parent).use { entries ->
            entries
                .asSequence()
                .filter {
                    it.fileName.toString().startsWith(".kast-") &&
                        !it.fileName.toString().endsWith(".lock") &&
                        it != marker
                }
                .take(MAXIMUM_PUBLIC_RESET_ENTRIES + 1)
                .toList()
        }

    private fun launcherArtifacts(scope: PublicResetScope): List<Path> =
        setOf(scope.command.parent, scope.home.resolve(".local/bin")).flatMap { directory ->
            if (Files.notExists(directory, NOFOLLOW_LINKS)) emptyList()
            else
                Files.newDirectoryStream(directory).use { entries ->
                    entries
                        .asSequence()
                        .filter { Regex("\\.kast-[0-9]+\\.(old|new)").matches(it.fileName.toString()) }
                        .take(MAXIMUM_PUBLIC_RESET_ENTRIES + 1)
                        .toList()
                }
        }

    private fun removeCommands(scope: PublicResetScope): ResetEffect {
        when (resetInstallationTransport(path.resolve("installation"), scope.home)) {
            PriorInstallationPreparation.PREPARED -> Unit
            PriorInstallationPreparation.FILESYSTEM_REJECTED ->
                return ResetEffect.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
        for (path in setOf(scope.home.resolve(".local/bin/kast"), scope.command)) {
            if (ForceResetRoot.admit(path, scope.home) == ForceResetRootAdmission.Rejected)
                return ResetEffect.Rejected(ForceResetFailure.ROOT_REJECTED)
            Files.deleteIfExists(path)
        }
        return ResetEffect.Completed
    }

    private fun removeHistoricalEntry(path: Path, home: Path, runtime: ForceDaemonRuntime): ResetEffect {
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            when (val retired = ForceDaemonRetirement(runtime).retire(path, home)) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return retired
            }
            Files.walkFileTree(path, ResetDeletingVisitor)
        } else Files.deleteIfExists(path)
        return ResetEffect.Completed
    }
}

internal object ResetDeletingVisitor : SimpleFileVisitor<Path>() {
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

private const val MAXIMUM_PUBLIC_RESET_ENTRIES = 1024
