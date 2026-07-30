use std::path::{Path, PathBuf};

use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};

pub(crate) struct WorkspaceIndexFixture {
    workspace_root: PathBuf,
    database_path: PathBuf,
}

impl WorkspaceIndexFixture {
    pub(crate) fn at_database_path(workspace_root: &Path, database_path: &Path) -> Self {
        std::fs::create_dir_all(workspace_root).expect("workspace root");
        std::fs::create_dir_all(database_path.parent().expect("database parent"))
            .expect("database parent");
        let fixture = Self {
            workspace_root: workspace_root.to_path_buf(),
            database_path: database_path.to_path_buf(),
        };
        fixture.create_schema();
        fixture
    }

    pub(crate) fn database_path(&self) -> &Path {
        &self.database_path
    }

    pub(crate) fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    pub(crate) fn connection(&self) -> Connection {
        Connection::open(&self.database_path).expect("workspace index database")
    }

    pub(crate) fn seed_high_cardinality_sources(&self, count: usize) {
        let source_root = self.workspace_root.join("src/main/kotlin/sample");
        std::fs::create_dir_all(&source_root).expect("Kotlin source root");
        let mut connection = self.connection();
        let transaction = connection.transaction().expect("source seed transaction");
        let content = b"package sample\n";
        let content_hash = hex::encode(Sha256::digest(content));
        let semantic_graph_scope_fingerprint =
            semantic_graph_scope_fingerprint((0..count).map(|index| {
                (
                    format!("src/main/kotlin/sample/Source{index:04}.kt"),
                    content_hash.clone(),
                )
            }));
        for index in 0..count {
            let filename = format!("Source{index:04}.kt");
            std::fs::write(source_root.join(&filename), content).expect("Kotlin source");
            transaction
                .execute(
                    "INSERT INTO file_manifest(
                         prefix_id, filename, last_modified_millis, content_hash,
                         desired_source_version, desired_relationships_version,
                         desired_semantic_graph_version, module_name, source_set
                     ) VALUES (1, ?, 1, ?, 'source-1', 'relationships-1', 'semantic-graph-1', 'app', 'main')",
                    params![filename, content_hash],
                )
                .expect("source manifest row");
            for (stage, version) in [
                ("SOURCE", "source-1"),
                ("RELATIONSHIPS", "relationships-1"),
                ("SEMANTIC_GRAPH", "semantic-graph-1"),
            ] {
                transaction
                    .execute(
                        "INSERT INTO file_stage_outcomes(
                             prefix_id, filename, stage, content_hash, stage_version,
                             stage_input_fingerprint, outcome_status, limitations_json
                         ) VALUES (1, ?, ?, ?, ?, ?, 'COMPLETE', '[]')",
                        params![
                            filename,
                            stage,
                            content_hash,
                            version,
                            (stage == "SEMANTIC_GRAPH")
                                .then_some(semantic_graph_scope_fingerprint.as_str())
                        ],
                    )
                    .expect("complete file stage");
            }
            transaction
                .execute(
                    "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (1, ?, 1, 'PROVEN_NAMED', NULL, 'idea.app.main', 'main')",
                    params![filename],
                )
                .expect("source metadata row");
            transaction
                .execute(
                    "INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path) VALUES (1, ?, '.', ':app')",
                    params![filename],
                )
                .expect("source Gradle project row");
            transaction
                .execute(
                    "INSERT INTO file_gradle_source_sets(prefix_id, filename, build_root, project_path, source_set_name) VALUES (1, ?, '.', ':app', 'main')",
                    params![filename],
                )
                .expect("source Gradle source-set row");
        }
        transaction.commit().expect("source seed commit");
    }

    #[allow(dead_code)] // Shared support is also compiled by unit-test harnesses without graph fixtures.
    pub(crate) fn synchronize_semantic_graph_scope_fingerprints(&self) {
        let connection = self.connection();
        let paths = {
            let mut statement = connection
                .prepare(
                    "SELECT path, content_hash
                     FROM semantic_files
                     WHERE refresh_status != 'CACHED'
                     ORDER BY path",
                )
                .expect("semantic scope query");
            statement
                .query_map([], |row| {
                    Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
                })
                .expect("semantic scope rows")
                .collect::<rusqlite::Result<Vec<_>>>()
                .expect("semantic scope paths")
        };
        let fingerprint = semantic_graph_scope_fingerprint(paths);
        connection
            .execute(
                "UPDATE file_stage_outcomes
                 SET stage_input_fingerprint = ?
                 WHERE stage = 'SEMANTIC_GRAPH'",
                params![fingerprint],
            )
            .expect("synchronize semantic scope fingerprint");
    }

    pub(crate) fn seed_non_source_manifest_rows(&self) {
        let source_root = self.workspace_root.join("src/main/kotlin/sample");
        for filename in ["Build.gradle.kts", "README.md", "Generated.java"] {
            std::fs::write(source_root.join(filename), "fixture\n").expect("non-source file");
        }
        let connection = self.connection();
        for filename in ["Build.gradle.kts", "README.md", "Generated.java"] {
            connection
                .execute(
                    "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (1, ?, 1)",
                    params![filename],
                )
                .expect("non-source manifest row");
        }
    }

    pub(crate) fn seed_exact_progress(&self) {
        self.seed_progress("app", "COMPLETE", 500, 500);
    }

    pub(crate) fn seed_progress(
        &self,
        module_name: &str,
        status: &str,
        indexed_file_count: i64,
        total_file_count: i64,
    ) {
        let connection = self.connection();
        connection
            .execute(
                "INSERT OR REPLACE INTO module_index_progress(module_name, relationship_index_status, indexed_file_count, total_file_count, last_indexed_epoch_ms) VALUES (?, ?, ?, ?, 1)",
                params![module_name, status, indexed_file_count, total_file_count],
            )
            .expect("module progress");
    }

    pub(crate) fn seed_pending_update(&self, filename: &str, applied: bool) {
        self.seed_pending_update_at(1, filename, applied);
    }

    pub(crate) fn seed_pending_update_at(&self, prefix_id: i64, filename: &str, applied: bool) {
        self.connection()
            .execute(
                "INSERT INTO pending_updates(op, prefix_id, filename, epoch_ms, applied) VALUES ('upsert_file', ?, ?, 1, ?)",
                params![prefix_id, filename, i64::from(applied)],
            )
            .expect("pending update");
    }

    pub(crate) fn insert_manifest_file(
        &self,
        prefix_id: i64,
        dir_path: &str,
        filename: &str,
        create_on_disk: bool,
    ) {
        let path = self.workspace_root.join(dir_path).join(filename);
        if create_on_disk {
            std::fs::create_dir_all(path.parent().expect("manifest file parent"))
                .expect("manifest file parent");
            std::fs::write(&path, "package fixture\n").expect("manifest source file");
        }
        let content_hash = std::fs::read(&path)
            .ok()
            .map(|content| hex::encode(Sha256::digest(content)));
        let connection = self.connection();
        connection
            .execute(
                "INSERT OR REPLACE INTO path_prefixes(prefix_id, dir_path) VALUES (?, ?)",
                params![prefix_id, dir_path],
            )
            .expect("path prefix");
        connection
            .execute(
                "INSERT INTO file_manifest(
                     prefix_id, filename, last_modified_millis, content_hash,
                     desired_source_version, desired_relationships_version,
                     desired_semantic_graph_version
                 ) VALUES (?, ?, 1, ?, 'source-1', 'relationships-1', 'semantic-graph-1')",
                params![prefix_id, filename, content_hash],
            )
            .expect("manifest file");
        if let Some(content_hash) = content_hash {
            let semantic_graph_scope_fingerprint = semantic_graph_scope_fingerprint([(
                format!("{dir_path}/{filename}"),
                content_hash.clone(),
            )]);
            for (stage, version) in [
                ("SOURCE", "source-1"),
                ("RELATIONSHIPS", "relationships-1"),
                ("SEMANTIC_GRAPH", "semantic-graph-1"),
            ] {
                connection
                    .execute(
                        "INSERT INTO file_stage_outcomes(
                             prefix_id, filename, stage, content_hash, stage_version,
                             stage_input_fingerprint, outcome_status, limitations_json
                         ) VALUES (?, ?, ?, ?, ?, ?, 'COMPLETE', '[]')",
                        params![
                            prefix_id,
                            filename,
                            stage,
                            content_hash,
                            version,
                            (stage == "SEMANTIC_GRAPH")
                                .then_some(semantic_graph_scope_fingerprint.as_str())
                        ],
                    )
                    .expect("complete file stage");
            }
        }
    }

    pub(crate) fn insert_project_evidence(
        &self,
        prefix_id: i64,
        filename: &str,
        build_root: &str,
        project_path: &str,
        source_set_name: &str,
    ) {
        let connection = self.connection();
        connection
            .execute(
                "INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path) VALUES (?, ?, ?, ?)",
                params![prefix_id, filename, build_root, project_path],
            )
            .expect("Gradle project evidence");
        connection
            .execute(
                "INSERT INTO file_gradle_source_sets(prefix_id, filename, build_root, project_path, source_set_name) VALUES (?, ?, ?, ?, ?)",
                params![prefix_id, filename, build_root, project_path, source_set_name],
            )
            .expect("Gradle source-set evidence");
    }

    pub(crate) fn set_schema_version(&self, version: i64) {
        self.connection()
            .execute("UPDATE schema_version SET version = ?", params![version])
            .expect("schema version");
    }

    pub(crate) fn drop_required_table(&self, table: &str) {
        assert!(
            matches!(table, "file_gradle_projects" | "file_gradle_source_sets"),
            "fixture only drops an owned association table"
        );
        let connection = self.connection();
        connection
            .execute_batch("PRAGMA foreign_keys=OFF;")
            .expect("disable fixture foreign keys");
        connection
            .execute_batch(&format!("DROP TABLE {table};"))
            .expect("drop required table");
    }

    pub(crate) fn replace_file_metadata_without_package_checks(&self) {
        self.connection()
            .execute_batch(
                r#"
                PRAGMA foreign_keys=OFF;
                DROP TABLE file_gradle_source_sets;
                DROP TABLE file_gradle_projects;
                DROP TABLE file_metadata;
                CREATE TABLE file_metadata (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    package_fq_id INTEGER,
                    package_state TEXT NOT NULL,
                    package_unproven_reason TEXT,
                    module_path TEXT,
                    source_set TEXT,
                    PRIMARY KEY(prefix_id, filename),
                    FOREIGN KEY(package_fq_id) REFERENCES fq_names(fq_id)
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
                "#,
            )
            .expect("replace package-check schema");
    }
}

include!("workspace_files/schema.rs");

fn semantic_graph_scope_fingerprint(inputs: impl IntoIterator<Item = (String, String)>) -> String {
    let mut inputs = inputs.into_iter().collect::<Vec<_>>();
    inputs.sort_by(|left, right| left.0.encode_utf16().cmp(right.0.encode_utf16()));
    let mut digest = Sha256::new();
    for (path, content_hash) in inputs {
        digest.update(b"source:");
        digest.update(path.as_bytes());
        digest.update(b":");
        digest.update(content_hash.as_bytes());
        digest.update(b"\n");
    }
    hex::encode(digest.finalize())
}

impl Drop for WorkspaceIndexFixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.database_path);
        let _ = std::fs::remove_file(self.database_path.with_extension("db-wal"));
        let _ = std::fs::remove_file(self.database_path.with_extension("db-shm"));
    }
}
