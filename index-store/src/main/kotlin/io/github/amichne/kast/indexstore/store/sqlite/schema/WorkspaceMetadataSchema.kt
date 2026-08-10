package io.github.amichne.kast.indexstore.store

import java.sql.Statement

internal object WorkspaceMetadataSchema {
    fun createTables(stmt: Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL,
                generation INTEGER NOT NULL DEFAULT 0,
                head_commit TEXT
            )""",
        )
        stmt.execute(
            "INSERT INTO schema_version (version, generation, head_commit) " +
                "VALUES ($SOURCE_INDEX_SCHEMA_VERSION, 0, NULL)",
        )
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS workspace_publication (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                revision INTEGER NOT NULL CHECK(revision > 0),
                identity TEXT NOT NULL CHECK(length(trim(identity)) > 0),
                source_index_generation INTEGER NOT NULL CHECK(source_index_generation >= 0),
                source_revision INTEGER NOT NULL CHECK(source_revision >= 0),
                reference_revision INTEGER NOT NULL CHECK(reference_revision >= 0),
                graph_revision INTEGER CHECK(graph_revision >= 0),
                graph_blocker TEXT CHECK(graph_blocker = 'INDEXING_FAILED'),
                source_index_schema_version INTEGER NOT NULL CHECK(source_index_schema_version > 0),
                published_at_epoch_millis INTEGER NOT NULL CHECK(published_at_epoch_millis >= 0),
                repository_overlay_file TEXT CHECK(
                    repository_overlay_file IS NULL OR repository_overlay_file = 'repository-overlay.json'
                ),
                CHECK((graph_revision IS NULL) != (graph_blocker IS NULL))
            )""",
        )
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS workspace_discovery (
                cache_key TEXT PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                payload TEXT NOT NULL
            )""",
        )
    }

    fun dropTables(stmt: Statement) {
        stmt.execute("DROP TABLE IF EXISTS main.workspace_publication")
        stmt.execute("DROP TABLE IF EXISTS main.schema_version")
        stmt.execute("DROP TABLE IF EXISTS main.workspace_discovery")
    }
}
