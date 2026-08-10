use std::ops::{Deref, DerefMut};
use std::path::{Path, PathBuf};

use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};

include!("workspace_files/fixture.rs");

#[allow(dead_code)] // This shared include is compiled by unit-test contexts that do not publish fixtures directly.
pub(crate) fn publish_workspace_database(database_path: &Path) -> Option<serde_json::Value> {
    let cache_directory = database_path.parent()?;
    if database_path.file_name()?.to_str()? != "source-index.db"
        || cache_directory.file_name()?.to_str()? != "cache"
    {
        return None;
    }

    let connection = Connection::open(database_path).expect("published workspace database");
    publish_workspace_connection(&connection, database_path)
}

#[allow(dead_code)] // This shared include is compiled by test targets without overlay fixtures.
pub(crate) fn install_repository_overlay_fixture(
    workspace_database: &Path,
    write_base: impl FnOnce(&Path),
) -> PathBuf {
    let data_root = workspace_database
        .ancestors()
        .nth(4)
        .expect("flat workspace database data root");
    let schema_version: i64 = env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse()
        .expect("source-index schema version");
    let tree_oid = "a".repeat(40);
    let build_classpath_fingerprint = "b".repeat(64);
    let producer_version = "test";
    let snapshot_name = hex::encode(Sha256::digest(
        format!("{tree_oid}\n{build_classpath_fingerprint}\n{schema_version}\n{producer_version}")
            .as_bytes(),
    ));
    let snapshot_directory = data_root
        .join("repositories")
        .join("1".repeat(64))
        .join("snapshots")
        .join(snapshot_name);
    std::fs::create_dir_all(&snapshot_directory).expect("repository snapshot directory");
    let base_database = snapshot_directory.join("source-index.db");
    write_base(&base_database);
    let key = serde_json::json!({
        "treeOid": tree_oid,
        "buildClasspathFingerprint": build_classpath_fingerprint,
        "indexSchema": schema_version,
        "producerVersion": producer_version,
    });
    std::fs::write(
        snapshot_directory.join("manifest.json"),
        serde_json::to_vec(&serde_json::json!({
            "key": key,
            "files": {},
            "createdAt": 1,
        }))
        .expect("repository snapshot manifest JSON"),
    )
    .expect("repository snapshot manifest");
    std::fs::write(
        workspace_database.with_file_name("repository-overlay.json"),
        serde_json::to_vec(&serde_json::json!({
            "base": key,
            "target": key,
            "tombstones": [],
            "shards": {},
            "baseDatabase": base_database,
        }))
        .expect("repository overlay descriptor JSON"),
    )
    .expect("repository overlay descriptor");
    base_database
}

fn publish_workspace_connection(
    connection: &Connection,
    database_path: &Path,
) -> Option<serde_json::Value> {
    let cache_directory = database_path.parent()?;
    connection
        .execute_batch(
            "CREATE TABLE IF NOT EXISTS workspace_publication (
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
            );",
        )
        .expect("workspace publication schema");
    let (schema_version, source_index_generation): (i64, i64) = connection
        .query_row(
            "SELECT version, generation FROM schema_version LIMIT 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .expect("published workspace database identity");
    assert!(
        source_index_generation >= 0,
        "published workspace database has negative source generation",
    );
    let revision: i64 = connection
        .query_row(
            "SELECT COALESCE(MAX(revision), 0) + 1 FROM workspace_publication",
            [],
            |row| row.get(0),
        )
        .expect("next workspace publication revision");
    let graph_ready = connection
        .query_row(
            "SELECT NOT EXISTS(
                 SELECT 1
                 FROM file_manifest manifest
                 WHERE NOT EXISTS(
                       SELECT 1
                       FROM file_stage_outcomes outcome
                       WHERE outcome.prefix_id = manifest.prefix_id
                         AND outcome.filename = manifest.filename
                         AND outcome.stage = 'SEMANTIC_GRAPH'
                         AND outcome.outcome_status IN ('COMPLETE', 'LIMITED')
                   )
             )",
            [],
            |row| row.get::<_, bool>(0),
        )
        .unwrap_or(false);
    let graph_revision = graph_ready.then_some(revision);
    let graph_blocker = (!graph_ready).then_some("INDEXING_FAILED");
    let repository_overlay_file = cache_directory
        .join("repository-overlay.json")
        .is_file()
        .then_some("repository-overlay.json");
    connection
        .execute(
            "INSERT INTO workspace_publication(
                 singleton, revision, identity, source_index_generation,
                 source_revision, reference_revision, graph_revision, graph_blocker,
                 source_index_schema_version, published_at_epoch_millis, repository_overlay_file
             ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
             ON CONFLICT(singleton) DO UPDATE SET
                 revision = excluded.revision,
                 identity = excluded.identity,
                 source_index_generation = excluded.source_index_generation,
                 source_revision = excluded.source_revision,
                 reference_revision = excluded.reference_revision,
                 graph_revision = excluded.graph_revision,
                 graph_blocker = excluded.graph_blocker,
                 source_index_schema_version = excluded.source_index_schema_version,
                 published_at_epoch_millis = excluded.published_at_epoch_millis,
                 repository_overlay_file = excluded.repository_overlay_file",
            params![
                revision,
                format!("test-{schema_version}-{source_index_generation}"),
                source_index_generation,
                revision,
                revision,
                graph_revision,
                graph_blocker,
                schema_version,
                repository_overlay_file,
            ],
        )
        .expect("publish workspace database");
    let mut manifest = serde_json::json!({
        "generation": revision,
        "identity": format!("test-{schema_version}-{source_index_generation}"),
        "sourceIndexGeneration": source_index_generation,
        "sourceRevision": revision,
        "referenceRevision": revision,
        "graphPublication": if graph_ready {
            serde_json::json!({"type": "READY", "revision": revision})
        } else {
            serde_json::json!({"type": "BLOCKED", "reason": "INDEXING_FAILED"})
        },
        "sourceIndexSchemaVersion": schema_version,
        "databaseFile": "source-index.db",
        "publishedAtEpochMillis": 1
    });
    if repository_overlay_file.is_some() {
        manifest["repositoryOverlayFile"] = serde_json::json!("repository-overlay.json");
    }
    Some(manifest)
}

pub(crate) struct WorkspaceFixtureConnection {
    connection: Connection,
    database_path: PathBuf,
}

impl Deref for WorkspaceFixtureConnection {
    type Target = Connection;

    fn deref(&self) -> &Self::Target {
        &self.connection
    }
}

impl DerefMut for WorkspaceFixtureConnection {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.connection
    }
}

impl Drop for WorkspaceFixtureConnection {
    fn drop(&mut self) {
        publish_workspace_connection(&self.connection, &self.database_path)
            .expect("fixture publication after database mutation");
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
