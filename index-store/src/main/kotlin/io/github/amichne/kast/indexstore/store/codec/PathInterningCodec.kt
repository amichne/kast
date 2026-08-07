package io.github.amichne.kast.indexstore.store.codec

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.store.AttachedSqliteDatabase
import java.nio.file.Path
import java.sql.Connection

internal class SourceIndexReadPath internal constructor(
    internal val prefixId: Int,
    internal val filename: String,
)

internal sealed interface SourceIndexReadPathResolution {
    data class Resolved(val path: SourceIndexReadPath) : SourceIndexReadPathResolution

    data object PrefixUnavailable : SourceIndexReadPathResolution
}

internal class PathInterningCodec(
    workspaceRoot: Path,
    private val interning: StringInterningCodec = StringInterningCodec(
        StringInterningDomain.PATH_PREFIX,
    ),
) {
    private val normalizedWorkspaceRoot = NormalizedPath.of(workspaceRoot).toJavaPath()

    fun decompose(absolutePath: String): Pair<String, String> {
        val normalizedPath = NormalizedPath.of(Path.of(absolutePath)).toJavaPath()
        val filename = requireNotNull(normalizedPath.fileName) { "Path must include a filename: $absolutePath" }.toString()
        val relativeDir = if (normalizedPath.startsWith(normalizedWorkspaceRoot)) {
            escapeRelativeDir(normalizedWorkspaceRoot.relativize(normalizedPath).parent?.toSlashPath().orEmpty())
        } else {
            ABSOLUTE_PREFIX + normalizedPath.parentString()
        }
        return relativeDir to filename
    }

    fun compose(
        relativeDir: String,
        filename: String,
    ): String {
        if (relativeDir.startsWith(ABSOLUTE_PREFIX)) {
            return Path.of(relativeDir.removePrefix(ABSOLUTE_PREFIX)).resolve(filename).normalize().toString()
        }

        val workspaceRelativeDir = unescapeRelativeDir(relativeDir)
        return when {
            workspaceRelativeDir.isEmpty() ->
                normalizedWorkspaceRoot.resolve(filename).normalize().toString()

            else ->
                workspaceRelativeDir.split("/")
                    .filter { it.isNotEmpty() }
                    .fold(normalizedWorkspaceRoot) { current, segment -> current.resolve(segment) }
                    .resolve(filename)
                    .normalize()
                    .toString()
        }
    }

    fun internDir(
        conn: Connection,
        relativeDir: String,
    ): Int {
        return interning.getOrCreate(conn, relativeDir)
    }

    fun batchIntern(
        conn: Connection,
        relativeDirs: Set<String>,
    ) {
        interning.batchEnsure(conn, relativeDirs)
    }

    fun loadPrefixes(conn: Connection) = interning.loadAll(conn)

    fun reloadPrefixes(conn: Connection) = interning.reloadAll(conn)

    /**
     * Proof transition:
     * `(Connection, AttachedSqliteDatabase) -> ReadOnlyInterningAliasResolution`.
     *
     * Delegates repository path-prefix admission and preserves its finite
     * loaded/rejected outcome; raw IDs stay inside the SQLite codec boundary.
     */
    fun loadReadOnlyAliases(
        conn: Connection,
        database: AttachedSqliteDatabase,
    ): ReadOnlyInterningAliasResolution =
        interning.loadReadOnlyAliases(conn, database)

    fun resolvePrefix(prefixId: Int): String =
        interning.resolve(prefixId)

    fun encode(absolutePath: String): Pair<Int, String> {
        val (relativeDir, filename) = decompose(absolutePath)
        val prefixId = interning.idFor(relativeDir)
                       ?: throw IllegalStateException("Path prefix is not interned: $relativeDir")
        return prefixId to filename
    }

    fun encodeOrCreate(
        conn: Connection,
        absolutePath: String,
    ): Pair<Int, String> {
        val (relativeDir, filename) = decompose(absolutePath)
        return interning.getOrCreate(conn, relativeDir) to filename
    }

    fun encodeIfInterned(absolutePath: String): Pair<Int, String>? {
        val (relativeDir, filename) = decompose(absolutePath)
        return interning.idFor(relativeDir)?.let { prefixId -> prefixId to filename }
    }

    /**
     * Proof transition: `String -> SourceIndexReadPathResolution`.
     *
     * A resolved path carries a normalized filename and a prefix interned by
     * either the workspace database or attached repository read authority.
     * Missing prefix evidence is explicit [SourceIndexReadPathResolution.PrefixUnavailable].
     * Raw prefix IDs and filenames are extracted only while binding SQLite.
     */
    fun encodeForRead(absolutePath: String): SourceIndexReadPathResolution {
        val (relativeDir, filename) = decompose(absolutePath)
        return when (val resolution = interning.idForRead(relativeDir)) {
            is InternedStringReadIdResolution.Resolved -> SourceIndexReadPathResolution.Resolved(
                SourceIndexReadPath(resolution.id.value, filename),
            )
            InternedStringReadIdResolution.Unavailable -> SourceIndexReadPathResolution.PrefixUnavailable
        }
    }

    fun decode(
        prefixId: Int,
        filename: String,
    ): String = compose(resolvePrefix(prefixId), filename)

    private fun escapeRelativeDir(relativeDir: String): String =
        if (relativeDir.startsWith(ABSOLUTE_PREFIX) || relativeDir.startsWith(RELATIVE_ESCAPE_PREFIX)) {
            RELATIVE_ESCAPE_PREFIX + relativeDir
        } else {
            relativeDir
        }

    private fun unescapeRelativeDir(relativeDir: String): String =
        if (relativeDir.startsWith(RELATIVE_ESCAPE_PREFIX)) {
            relativeDir.removePrefix(RELATIVE_ESCAPE_PREFIX)
        } else {
            relativeDir
        }

    private fun Path.toSlashPath(): String =
        iterator().asSequence().joinToString("/") { it.toString() }

    private fun Path.parentString(): String =
        parent?.toString() ?: root?.toString() ?: ""

    private companion object {
        const val ABSOLUTE_PREFIX = "__kast_abs__/"
        const val RELATIVE_ESCAPE_PREFIX = "__kast_rel__/"
    }
}
