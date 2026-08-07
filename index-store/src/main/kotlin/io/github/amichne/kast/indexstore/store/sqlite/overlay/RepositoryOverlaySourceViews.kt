package io.github.amichne.kast.indexstore.store

import java.sql.Connection
import java.sql.Statement

@JvmInline
internal value class OverlayViewDefinition private constructor(private val createSql: String) {
    /** Raw SQL extraction is confined to SQLite schema installation. */
    fun install(statement: Statement) {
        statement.execute(createSql)
    }

    companion object {
        /**
         * Proof transition: `String -> OverlayViewDefinition`.
         *
         * Establishes that a repository-owned constant is one complete
         * idempotent temporary-view definition. The raw SQL remains private and
         * may be extracted only by `install` at the SQLite schema boundary.
         */
        fun schemaOwned(createSql: String): OverlayViewDefinition {
            val canonical = createSql.trimIndent().trim()
            require(canonical.startsWith("CREATE TEMP VIEW IF NOT EXISTS ") && ';' !in canonical) {
                "Overlay view definition must be one idempotent temporary-view statement"
            }
            return OverlayViewDefinition(canonical)
        }

        /**
         * Proof transition: `Array<String> -> List<OverlayViewDefinition>`.
         *
         * Applies `schemaOwned` to a fixed group of repository-owned view
         * declarations and returns only validated definitions.
         */
        fun schemaOwnedAll(vararg createSql: String): List<OverlayViewDefinition> =
            createSql.map(::schemaOwned)
    }
}

internal object RepositoryOverlaySourceViews {
    fun install(connection: Connection) {
        connection.createStatement().use { statement ->
            (FileOverlayViews.definitions + SymbolOverlayViews.definitions).forEach { view ->
                view.install(statement)
            }
        }
    }
}
