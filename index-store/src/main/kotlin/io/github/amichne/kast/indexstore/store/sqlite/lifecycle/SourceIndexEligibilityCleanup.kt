package io.github.amichne.kast.indexstore.store

import java.sql.Connection

internal fun SqliteSourceIndexStoreState.removeIneligibleSourceIndexRows(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """DELETE FROM symbol_references
               WHERE src_filename NOT GLOB '*.kt'""",
        )
        statement.execute(
            """UPDATE symbol_references
               SET tgt_prefix_id = NULL,
                   tgt_filename = NULL,
                   target_offset = NULL
               WHERE tgt_filename IS NOT NULL
                 AND tgt_filename NOT GLOB '*.kt'""",
        )
        for (table in FileOwnedCleanupTable.entries) {
            statement.execute("DELETE FROM $table WHERE filename NOT GLOB '*.kt'")
        }
    }
}

private enum class FileOwnedCleanupTable(private val persistedName: String) {
    DECLARATIONS("declarations"),
    IDENTIFIER_PATHS("identifier_paths"),
    FILE_GRADLE_SOURCE_SETS("file_gradle_source_sets"),
    FILE_GRADLE_PROJECTS("file_gradle_projects"),
    FILE_METADATA("file_metadata"),
    FILE_IMPORTS("file_imports"),
    FILE_WILDCARD_IMPORTS("file_wildcard_imports"),
    FILE_MANIFEST("file_manifest"),
    PENDING_UPDATES("pending_updates"),
    ;

    /** Raw schema identifier extraction is confined to SQLite cleanup SQL. */
    override fun toString(): String = persistedName
}
