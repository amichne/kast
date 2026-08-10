impl WorkspaceIndexFixture {
    fn create_schema(&self) {
        let connection = self.connection();
        connection
            .execute_batch(&format!(
                r#"
                PRAGMA foreign_keys=ON;
                CREATE TABLE schema_version (
                    version INTEGER NOT NULL,
                    generation INTEGER NOT NULL DEFAULT 0,
                    head_commit TEXT
                );
                INSERT INTO schema_version(version, generation, head_commit)
                    VALUES ({}, 41, 'fixture-head');
                CREATE TABLE workspace_publication (
                    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                    revision INTEGER NOT NULL CHECK(revision > 0),
                    identity TEXT NOT NULL CHECK(length(identity) > 0),
                    source_index_generation INTEGER NOT NULL CHECK(source_index_generation >= 0),
                    source_revision INTEGER NOT NULL CHECK(source_revision >= 0),
                    reference_revision INTEGER NOT NULL CHECK(reference_revision >= 0),
                    graph_revision INTEGER,
                    graph_blocker TEXT,
                    source_index_schema_version INTEGER NOT NULL CHECK(source_index_schema_version > 0),
                    published_at_epoch_millis INTEGER NOT NULL CHECK(published_at_epoch_millis >= 0),
                    repository_overlay_file TEXT
                );
                CREATE TABLE path_prefixes (
                    prefix_id INTEGER PRIMARY KEY,
                    dir_path TEXT NOT NULL UNIQUE
                );
                INSERT INTO path_prefixes(prefix_id, dir_path)
                    VALUES (1, 'src/main/kotlin/sample');
                CREATE TABLE fq_names (
                    fq_id INTEGER PRIMARY KEY,
                    fq_name TEXT NOT NULL UNIQUE
                );
                INSERT INTO fq_names(fq_id, fq_name) VALUES (1, 'sample');
                CREATE TABLE file_manifest (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    last_modified_millis INTEGER NOT NULL,
                    content_hash TEXT,
                    desired_source_version TEXT,
                    desired_relationships_version TEXT,
                    desired_semantic_graph_version TEXT,
                    module_name TEXT,
                    source_set TEXT,
                    PRIMARY KEY(prefix_id, filename)
                );
                CREATE TABLE file_stage_outcomes (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    stage TEXT NOT NULL
                        CHECK(stage IN ('SOURCE','RELATIONSHIPS','SEMANTIC_GRAPH')),
                    content_hash TEXT NOT NULL,
                    stage_version TEXT NOT NULL,
                    stage_input_fingerprint TEXT,
                    outcome_status TEXT NOT NULL
                        CHECK(outcome_status IN ('COMPLETE','LIMITED','FAILED','EXTERNAL_BOUNDARY')),
                    limitations_json TEXT NOT NULL,
                    failure_id TEXT,
                    failure_code TEXT
                        CHECK(failure_code IS NULL OR failure_code IN ('PSI_UNAVAILABLE')),
                    failure_message TEXT,
                    failure_attempt_count INTEGER NOT NULL DEFAULT 0
                        CHECK(failure_attempt_count >= 0),
                    CHECK(
                        (outcome_status IN ('COMPLETE','LIMITED')
                            AND failure_id IS NULL AND failure_code IS NULL AND failure_message IS NULL)
                        OR
                        (outcome_status IN ('FAILED','EXTERNAL_BOUNDARY')
                            AND failure_id IS NOT NULL AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
                    ),
                    PRIMARY KEY(prefix_id, filename, stage)
                );
                CREATE UNIQUE INDEX idx_file_stage_outcomes_failure_id
                    ON file_stage_outcomes(failure_id) WHERE failure_id IS NOT NULL;
                CREATE TABLE file_metadata (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    package_fq_id INTEGER,
                    package_state TEXT NOT NULL CHECK(package_state IN ('PROVEN_ROOT','PROVEN_NAMED','UNPROVEN')),
                    package_unproven_reason TEXT,
                    module_path TEXT,
                    source_set TEXT,
                    PRIMARY KEY(prefix_id, filename),
                    FOREIGN KEY(package_fq_id) REFERENCES fq_names(fq_id),
                    CHECK(
                        (package_state = 'PROVEN_ROOT' AND package_fq_id IS NULL AND package_unproven_reason IS NULL)
                        OR (package_state = 'PROVEN_NAMED' AND package_fq_id IS NOT NULL AND package_unproven_reason IS NULL)
                        OR (
                            package_state = 'UNPROVEN'
                            AND package_fq_id IS NULL
                            AND package_unproven_reason IN (
                                'NOT_SCANNED',
                                'SEMANTIC_ANALYSIS_UNAVAILABLE',
                                'SEMANTIC_ANALYSIS_FAILED',
                                'LEGACY_TEXT_ONLY'
                            )
                        )
                    )
                );
                CREATE TABLE file_gradle_projects (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    build_root TEXT NOT NULL,
                    project_path TEXT NOT NULL,
                    PRIMARY KEY(prefix_id, filename, build_root, project_path),
                    FOREIGN KEY(prefix_id, filename) REFERENCES file_metadata(prefix_id, filename) ON DELETE CASCADE
                );
                CREATE TABLE file_gradle_source_sets (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    build_root TEXT NOT NULL,
                    project_path TEXT NOT NULL,
                    source_set_name TEXT NOT NULL,
                    PRIMARY KEY(prefix_id, filename, build_root, project_path, source_set_name),
                    FOREIGN KEY(prefix_id, filename, build_root, project_path)
                        REFERENCES file_gradle_projects(prefix_id, filename, build_root, project_path)
                        ON DELETE CASCADE
                );
                CREATE TABLE module_index_progress (
                    module_name TEXT PRIMARY KEY,
                    relationship_index_status TEXT NOT NULL
                        CHECK(relationship_index_status IN ('PENDING','INDEXING','COMPLETE','DEGRADED','FAILED')),
                    indexed_file_count INTEGER NOT NULL,
                    total_file_count INTEGER NOT NULL,
                    last_indexed_epoch_ms INTEGER
                );
                CREATE TABLE pending_updates (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    op TEXT NOT NULL,
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    payload TEXT,
                    session_id TEXT,
                    epoch_ms INTEGER NOT NULL,
                    applied INTEGER NOT NULL DEFAULT 0
                );
                "#,
                env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
            ))
            .expect("workspace index schema");
    }
}
