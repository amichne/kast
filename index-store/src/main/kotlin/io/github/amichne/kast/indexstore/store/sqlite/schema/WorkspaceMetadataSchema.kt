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
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS evidence_lane_sets (
                set_id TEXT NOT NULL CHECK(length(set_id) = 36),
                lane TEXT NOT NULL CHECK(lane IN ('SOURCE', 'REFERENCES', 'SEMANTIC_GRAPH')),
                workspace_identity TEXT NOT NULL CHECK(length(trim(workspace_identity)) > 0),
                environment_fingerprint TEXT NOT NULL CHECK(
                    length(environment_fingerprint) = 64 AND
                    environment_fingerprint NOT GLOB '*[^0-9a-f]*'
                ),
                PRIMARY KEY(set_id, lane)
            )""",
        )
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS evidence_candidate_shards (
                set_id TEXT NOT NULL,
                lane TEXT NOT NULL,
                source_path TEXT NOT NULL CHECK(length(trim(source_path)) > 0),
                content_hash TEXT NOT NULL CHECK(
                    length(content_hash) = 64 AND content_hash NOT GLOB '*[^0-9a-f]*'
                ),
                stage_version TEXT NOT NULL CHECK(
                    length(trim(stage_version)) > 0 AND length(stage_version) <= 128
                ),
                payload TEXT NOT NULL CHECK(
                    length(CAST(payload AS BLOB)) > 0 AND length(CAST(payload AS BLOB)) <= 1048576
                ),
                PRIMARY KEY(set_id, lane, source_path),
                FOREIGN KEY(set_id, lane) REFERENCES evidence_lane_sets(set_id, lane) ON DELETE CASCADE
            )""",
        )
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS evidence_lane_candidates (
                lane TEXT PRIMARY KEY CHECK(lane IN ('SOURCE', 'REFERENCES', 'SEMANTIC_GRAPH')),
                set_id TEXT NOT NULL UNIQUE,
                FOREIGN KEY(set_id, lane) REFERENCES evidence_lane_sets(set_id, lane)
            )""",
        )
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS evidence_lane_publications (
                lane TEXT PRIMARY KEY CHECK(lane IN ('SOURCE', 'REFERENCES', 'SEMANTIC_GRAPH')),
                current_set_id TEXT NOT NULL UNIQUE,
                current_revision INTEGER NOT NULL CHECK(current_revision > 0),
                current_published_at_epoch_millis INTEGER NOT NULL CHECK(current_published_at_epoch_millis >= 0),
                previous_set_id TEXT UNIQUE,
                previous_revision INTEGER CHECK(previous_revision > 0),
                previous_published_at_epoch_millis INTEGER CHECK(previous_published_at_epoch_millis >= 0),
                CHECK(
                    (previous_set_id IS NULL AND previous_revision IS NULL AND
                     previous_published_at_epoch_millis IS NULL) OR
                    (previous_set_id IS NOT NULL AND previous_revision IS NOT NULL AND
                     previous_published_at_epoch_millis IS NOT NULL)
                ),
                CHECK(previous_set_id IS NULL OR previous_set_id != current_set_id),
                FOREIGN KEY(current_set_id, lane) REFERENCES evidence_lane_sets(set_id, lane),
                FOREIGN KEY(previous_set_id, lane) REFERENCES evidence_lane_sets(set_id, lane)
            )""",
        )
    }

    fun dropTables(stmt: Statement) {
        stmt.execute("DROP TABLE IF EXISTS main.evidence_lane_publications")
        stmt.execute("DROP TABLE IF EXISTS main.evidence_lane_candidates")
        stmt.execute("DROP TABLE IF EXISTS main.evidence_candidate_shards")
        stmt.execute("DROP TABLE IF EXISTS main.evidence_lane_sets")
        stmt.execute("DROP TABLE IF EXISTS main.workspace_publication")
        stmt.execute("DROP TABLE IF EXISTS main.schema_version")
        stmt.execute("DROP TABLE IF EXISTS main.workspace_discovery")
    }
}
