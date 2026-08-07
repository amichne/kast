package io.github.amichne.kast.indexstore.store

/**
 * Closed set of source-index relations whose reads may be overlaid by an
 * immutable repository snapshot.
 */
internal enum class SourceIndexReadTable(internal val persistedName: String) {
    PATH_PREFIXES("path_prefixes"),
    FQ_NAMES("fq_names"),
    FILE_MANIFEST("file_manifest"),
    IDENTIFIER_PATHS("identifier_paths"),
    FILE_METADATA("file_metadata"),
    FILE_GRADLE_PROJECTS("file_gradle_projects"),
    FILE_GRADLE_SOURCE_SETS("file_gradle_source_sets"),
    FILE_IMPORTS("file_imports"),
    FILE_WILDCARD_IMPORTS("file_wildcard_imports"),
    FILE_STAGE_OUTCOMES("file_stage_outcomes"),
    DECLARATIONS("declarations"),
    DECLARATION_SUPERTYPES("declaration_supertypes"),
    SYMBOL_REFERENCES("symbol_references"),
    MODULE_INDEX_PROGRESS("module_index_progress"),
    SEMANTIC_FILES("semantic_files"),
    SEMANTIC_TYPES("semantic_types"),
    SEMANTIC_SYMBOLS("semantic_symbols"),
    SEMANTIC_TYPE_EDGES("semantic_type_edges"),
    SEMANTIC_SYMBOL_ANNOTATIONS("semantic_symbol_annotations"),
    SEMANTIC_EDGE_OCCURRENCES("semantic_edge_occurrences"),
    WORKSPACE_DISCOVERY("workspace_discovery"),
}

internal enum class AttachedSqliteDatabase(private val identifier: String) {
    REPOSITORY_BASE("repository_base"),
    ;

    /** Raw extraction is confined to SQLite statement construction. */
    override fun toString(): String = identifier
}

@JvmInline
internal value class SqlReadRelation private constructor(private val value: String) {
    /** Raw extraction is confined to SQL statement construction. */
    override fun toString(): String = value

    internal companion object {
        /**
         * Proof transition: `SourceIndexReadTable -> SqlReadRelation`.
         *
         * Establishes that the rendered relation is one closed, schema-owned
         * primary-database identifier. The raw identifier may be extracted only
         * while constructing a SQLite statement.
         */
        fun primary(table: SourceIndexReadTable): SqlReadRelation =
            SqlReadRelation(table.persistedName)

        /**
         * Proof transition: `SourceIndexReadTable -> SqlReadRelation`.
         *
         * Establishes that the rendered relation is the effective overlay view
         * corresponding to one closed, schema-owned source-index relation. The
         * raw identifier may be extracted only while constructing a SQLite
         * statement.
         */
        fun repositoryOverlay(table: SourceIndexReadTable): SqlReadRelation =
            SqlReadRelation("effective_${table.persistedName}")
    }
}
