package io.github.amichne.kast.evidence.sqlite

import java.sql.Connection

internal fun initializeTopologySchema(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute("PRAGMA journal_mode = WAL")
        statement.execute("PRAGMA foreign_keys = ON")
        statement.execute(
            """CREATE TABLE IF NOT EXISTS topology_snapshot (
                snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
                workspace_root TEXT NOT NULL CHECK(length(workspace_root) > 0),
                generation INTEGER NOT NULL CHECK(generation >= 0),
                source_state TEXT NOT NULL CHECK(length(source_state) > 0),
                digest TEXT NOT NULL CHECK(length(digest) = 64),
                file_count INTEGER NOT NULL CHECK(file_count >= 0),
                symbol_count INTEGER NOT NULL CHECK(symbol_count >= 0),
                edge_count INTEGER NOT NULL CHECK(edge_count >= 0),
                UNIQUE(workspace_root, generation, source_state)
            )""",
        )
        statement.execute(
            """CREATE TABLE IF NOT EXISTS topology_file (
                snapshot_id INTEGER NOT NULL REFERENCES topology_snapshot(snapshot_id)
                    ON DELETE CASCADE,
                path TEXT NOT NULL,
                content_hash TEXT NOT NULL CHECK(length(content_hash) = 64),
                module_name TEXT NOT NULL,
                build_root TEXT NOT NULL,
                project_path TEXT NOT NULL,
                source_set TEXT NOT NULL,
                source_root TEXT NOT NULL,
                provenance TEXT NOT NULL CHECK(
                    provenance IN ('AUTHORED', 'GENERATED', 'UNKNOWN_EXCLUDED')
                ),
                PRIMARY KEY(snapshot_id, path)
            )""",
        )
        statement.execute(
            """CREATE TABLE IF NOT EXISTS topology_symbol (
                snapshot_id INTEGER NOT NULL REFERENCES topology_snapshot(snapshot_id)
                    ON DELETE CASCADE,
                compiler_identity TEXT NOT NULL,
                file_path TEXT NOT NULL,
                start_offset INTEGER NOT NULL CHECK(start_offset >= 0),
                end_offset INTEGER NOT NULL CHECK(end_offset > start_offset),
                symbol_name TEXT NOT NULL,
                qualified_identity TEXT,
                symbol_kind TEXT NOT NULL,
                PRIMARY KEY(snapshot_id, compiler_identity),
                FOREIGN KEY(snapshot_id, file_path)
                    REFERENCES topology_file(snapshot_id, path)
            )""",
        )
        statement.execute(
            """CREATE TABLE IF NOT EXISTS topology_edge (
                snapshot_id INTEGER NOT NULL REFERENCES topology_snapshot(snapshot_id)
                    ON DELETE CASCADE,
                edge_kind TEXT NOT NULL,
                source_identity TEXT NOT NULL,
                target_identity TEXT NOT NULL,
                occurrence_file_path TEXT NOT NULL,
                start_offset INTEGER NOT NULL CHECK(start_offset >= 0),
                end_offset INTEGER NOT NULL CHECK(end_offset > start_offset),
                PRIMARY KEY(
                    snapshot_id, edge_kind, source_identity, target_identity,
                    occurrence_file_path, start_offset, end_offset
                ),
                FOREIGN KEY(snapshot_id, source_identity)
                    REFERENCES topology_symbol(snapshot_id, compiler_identity),
                FOREIGN KEY(snapshot_id, target_identity)
                    REFERENCES topology_symbol(snapshot_id, compiler_identity),
                FOREIGN KEY(snapshot_id, occurrence_file_path)
                    REFERENCES topology_file(snapshot_id, path)
            )""",
        )
        statement.execute(
            "CREATE INDEX IF NOT EXISTS topology_snapshot_root_order " +
            "ON topology_snapshot(workspace_root, snapshot_id DESC)",
        )
        statement.execute(
            "CREATE INDEX IF NOT EXISTS topology_edge_source " +
            "ON topology_edge(snapshot_id, source_identity, edge_kind)",
        )
        statement.execute(
            "CREATE INDEX IF NOT EXISTS topology_edge_target " +
            "ON topology_edge(snapshot_id, target_identity, edge_kind)",
        )
    }
}
