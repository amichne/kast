use std::path::{Path, PathBuf};

use rusqlite::{Connection, params};

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
        for index in 0..count {
            let filename = format!("Source{index:04}.kt");
            std::fs::write(source_root.join(&filename), "package sample\n").expect("Kotlin source");
            transaction
                .execute(
                    "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (1, ?, 1)",
                    params![filename],
                )
                .expect("source manifest row");
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
                "INSERT OR REPLACE INTO module_index_progress(module_name, phase2_status, indexed_file_count, total_file_count, last_indexed_epoch_ms) VALUES (?, ?, ?, ?, 1)",
                params![module_name, status, indexed_file_count, total_file_count],
            )
            .expect("module progress");
    }

    pub(crate) fn seed_pending_update(&self, filename: &str, applied: bool) {
        self.connection()
            .execute(
                "INSERT INTO pending_updates(op, prefix_id, filename, epoch_ms, applied) VALUES ('upsert_file', 1, ?, 1, ?)",
                params![filename, i64::from(applied)],
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
        let connection = self.connection();
        connection
            .execute(
                "INSERT OR REPLACE INTO path_prefixes(prefix_id, dir_path) VALUES (?, ?)",
                params![prefix_id, dir_path],
            )
            .expect("path prefix");
        connection
            .execute(
                "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (?, ?, 1)",
                params![prefix_id, filename],
            )
            .expect("manifest file");
        if create_on_disk {
            let path = self.workspace_root.join(dir_path).join(filename);
            std::fs::create_dir_all(path.parent().expect("manifest file parent"))
                .expect("manifest file parent");
            std::fs::write(path, "package fixture\n").expect("manifest source file");
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
                    PRIMARY KEY(prefix_id, filename)
                );
                CREATE TABLE file_metadata (
                    prefix_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    package_fq_id INTEGER,
                    package_state TEXT NOT NULL CHECK(package_state IN ('PROVEN_ROOT','PROVEN_NAMED','UNPROVEN')),
                    package_unproven_reason TEXT CHECK(package_unproven_reason IS NULL OR package_unproven_reason IN ('NOT_SCANNED','SEMANTIC_ANALYSIS_UNAVAILABLE','SEMANTIC_ANALYSIS_FAILED','LEGACY_TEXT_ONLY')),
                    module_path TEXT,
                    source_set TEXT,
                    PRIMARY KEY(prefix_id, filename),
                    FOREIGN KEY(package_fq_id) REFERENCES fq_names(fq_id),
                    CHECK(
                        (package_state = 'PROVEN_ROOT' AND package_fq_id IS NULL AND package_unproven_reason IS NULL)
                        OR (package_state = 'PROVEN_NAMED' AND package_fq_id IS NOT NULL AND package_unproven_reason IS NULL)
                        OR (package_state = 'UNPROVEN' AND package_fq_id IS NULL AND package_unproven_reason IS NOT NULL)
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
                    phase2_status TEXT NOT NULL CHECK(phase2_status IN ('PENDING','INDEXING','COMPLETE','FAILED')),
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

impl Drop for WorkspaceIndexFixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.database_path);
        let _ = std::fs::remove_file(self.database_path.with_extension("db-wal"));
        let _ = std::fs::remove_file(self.database_path.with_extension("db-shm"));
    }
}
