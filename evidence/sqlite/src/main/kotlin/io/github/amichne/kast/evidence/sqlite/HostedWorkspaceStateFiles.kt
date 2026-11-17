package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private val privateDirectoryPermissions =
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

/** Creates the exact database parent and preserves the finite reason if it cannot be admitted. */
internal fun admitHostedDatabasePath(raw: String): Refinement<Path, SqliteMutationRecoveryJournalOpenFailure> =
    try {
        val database = Path.of(raw)
        when {
            !database.isAbsolute || database.normalize() != database || database.parent == null ->
                Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.NOT_CANONICAL_ABSOLUTE)
            Files.isSymbolicLink(database.parent) ->
                Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.SYMLINK_NOT_ALLOWED)
            Files.exists(database.parent, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(database.parent, LinkOption.NOFOLLOW_LINKS) ->
                Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.PARENT_NOT_DIRECTORY)
            else -> {
                Files.createDirectories(database.parent, privateDirectoryPermissions)
                if (database.parent.toRealPath() != database.parent)
                    Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.SYMLINK_NOT_ALLOWED)
                else Refinement.Refined(database)
            }
        }
    } catch (_: java.nio.file.InvalidPathException) {
        Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.NOT_CANONICAL_ABSOLUTE)
    } catch (_: java.io.IOException) {
        Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.STORAGE_UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.STORAGE_UNAVAILABLE)
    } catch (_: UnsupportedOperationException) {
        Refinement.Rejected(SqliteMutationRecoveryJournalOpenFailure.STORAGE_UNAVAILABLE)
    }
