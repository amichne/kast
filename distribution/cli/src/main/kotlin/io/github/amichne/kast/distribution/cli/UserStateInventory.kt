package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.evidence.sqlite.SqliteMutationStateInspectionResult
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

/** Retains physical identity, ownership, permissions and each admitted directory's closed contents. */
internal class UserStateArtifact
private constructor(
    val path: Path,
    private val attributes: BasicFileAttributes,
    private val owner: UserPrincipal,
    private val permissions: Set<PosixFilePermission>,
    private val children: Set<Path>,
) {
    fun unchanged(removed: Set<Path> = emptySet()): Boolean =
        try {
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            samePhysicalIdentity(current) &&
                path.toRealPath() == path &&
                Files.getOwner(path, NOFOLLOW_LINKS) == owner &&
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions &&
                if (attributes.isDirectory) sameChildren(removed)
                else current.size() == attributes.size() && current.lastModifiedTime() == attributes.lastModifiedTime()
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun samePhysicalIdentity(current: BasicFileAttributes): Boolean =
        current.fileKey() != null &&
            current.fileKey() == attributes.fileKey() &&
            current.isDirectory == attributes.isDirectory &&
            current.isRegularFile == attributes.isRegularFile &&
            current.isOther == attributes.isOther &&
            !current.isSymbolicLink

    private fun sameChildren(removed: Set<Path>): Boolean =
        Files.newDirectoryStream(path).use { entries ->
            val expected = children - removed
            val observed = mutableSetOf<Path>()
            for (entry in entries) {
                if (entry !in expected || !observed.add(entry)) return false
            }
            observed == expected
        }

    companion object {
        fun capture(
            path: Path,
            attributes: BasicFileAttributes,
            owner: UserPrincipal,
            children: Set<Path>,
        ): UserStateArtifact =
            UserStateArtifact(path, attributes, owner, Files.getPosixFilePermissions(path, NOFOLLOW_LINKS), children)
    }
}

internal class UserStateInventory(
    private val homeIdentity: UserStateHomeIdentity,
    private val artifacts: List<UserStateArtifact>,
    private val locks: List<Pair<FileChannel, FileLock>>,
    private val deadOwners: List<Long>,
    private val settledMutations: List<SqliteMutationStateInspectionResult.Settled>,
) : AutoCloseable {
    fun validateBeforeRetirement(): UserStateRemoval =
        when {
            !homeIdentity.unchanged() -> UserStateRemoval.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
            deadOwners.any { ProcessHandle.of(it).isPresent } ->
                UserStateRemoval.Rejected(UserStateCleanupFailure.ACTIVE_HOST)
            settledMutations.any { proof -> artifacts.none { it.path == proof.database } } ->
                UserStateRemoval.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
            artifacts.any { !it.unchanged() } -> UserStateRemoval.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
            else -> UserStateRemoval.Completed
        }

    fun remove(): UserStateRemoval {
        return try {
            when (val validation = validateBeforeRetirement()) {
                is UserStateRemoval.Rejected -> return validation
                UserStateRemoval.Completed -> Unit
            }
            val removed = mutableSetOf<Path>()
            val byPath = artifacts.associateBy { it.path }
            for (artifact in artifacts.asReversed()) {
                if (deadOwners.any { ProcessHandle.of(it).isPresent })
                    return UserStateRemoval.Rejected(UserStateCleanupFailure.ACTIVE_HOST)
                if (
                    !homeIdentity.unchanged() ||
                        !artifact.unchanged(removed) ||
                        byPath[artifact.path.parent]?.unchanged(removed) == false
                ) {
                    return UserStateRemoval.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
                }
                Files.delete(artifact.path)
                removed.add(artifact.path)
            }
            UserStateRemoval.Completed
        } catch (_: java.io.IOException) {
            UserStateRemoval.Rejected(UserStateCleanupFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            UserStateRemoval.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
        }
    }

    override fun close() {
        locks.asReversed().forEach { (channel, lock) ->
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    companion object {
        fun admit(home: Path): UserStateAdmission {
            val builder = UserStateInventoryBuilder(home)
            return try {
                builder.collect()
                UserStateAdmission.Admitted(builder.finish())
            } catch (rejected: UserStateInventoryRejected) {
                builder.close()
                UserStateAdmission.Rejected(rejected.failure)
            } catch (_: java.io.IOException) {
                builder.close()
                UserStateAdmission.Rejected(UserStateCleanupFailure.FILESYSTEM_REJECTED)
            } catch (_: SecurityException) {
                builder.close()
                UserStateAdmission.Rejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
            }
        }
    }
}

/** HOME is an ancestry fact, not a directory whose unrelated contents uninstall owns. */
internal class UserStateHomeIdentity
private constructor(
    private val home: Path,
    private val fileKey: Any,
    private val owner: UserPrincipal,
    private val permissions: Set<PosixFilePermission>,
) {
    fun unchanged(): Boolean =
        try {
            val current = Files.readAttributes(home, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            current.isDirectory &&
                !current.isSymbolicLink &&
                current.fileKey() == fileKey &&
                home.toRealPath() == home &&
                Files.getOwner(home, NOFOLLOW_LINKS) == owner &&
                Files.getPosixFilePermissions(home, NOFOLLOW_LINKS) == permissions
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    companion object {
        fun capture(home: Path): UserStateHomeIdentity {
            val attributes = Files.readAttributes(home, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val permissions = Files.getPosixFilePermissions(home, NOFOLLOW_LINKS)
            if (
                (Files.getAttribute(home, "unix:uid", NOFOLLOW_LINKS) as Number).toLong() !=
                    com.sun.security.auth.module.UnixSystem().uid ||
                    permissions.any { it == PosixFilePermission.GROUP_WRITE || it == PosixFilePermission.OTHERS_WRITE }
            )
                throw UserStateInventoryRejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
            val key =
                attributes.fileKey() ?: throw UserStateInventoryRejected(UserStateCleanupFailure.OWNERSHIP_UNPROVEN)
            return UserStateHomeIdentity(
                home,
                key,
                Files.getOwner(home, NOFOLLOW_LINKS),
                permissions,
            )
        }
    }
}
