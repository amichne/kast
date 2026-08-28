package io.github.amichne.kast.evidence.sqlite

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Creates only the exact database parent and rejects a symlink or non-directory result. */
internal fun prepareHostedDatabasePath(raw: String): Path? = try {
    val database = Path.of(raw)
    if (!database.isAbsolute || database.normalize() != database || database.parent == null) {
        null
    } else {
        val parent = database.parent
        Files.createDirectories(parent)
        if (
            Files.isSymbolicLink(parent) ||
            !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
        ) {
            null
        } else {
            database
        }
    }
} catch (_: Exception) {
    null
}
