use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use rusqlite::Connection;
use tempfile::TempDir;

#[test]
fn resolves_publication_from_the_single_workspace_database() {
    let fixture = Fixture::new();
    let database = fixture.publish(1, 11, None);

    let resolved = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap();

    assert_eq!(std::fs::canonicalize(database).unwrap(), resolved.database);
    assert_eq!("source-index.db", resolved.manifest.database_file);
    assert!(!fixture.workspace_data().join("semantic-generations").exists());
    resolved.revalidate().unwrap();
}

#[test]
fn missing_workspace_database_is_unavailable() {
    let fixture = Fixture::new();

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_UNAVAILABLE", failure.code);
}

#[test]
fn database_without_a_committed_publication_is_unavailable() {
    let fixture = Fixture::new();
    write_source_database(&fixture.database(), 7);

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_INVALID", failure.code);
}

#[test]
fn publication_rejects_schema_or_source_generation_mismatch() {
    let fixture = Fixture::new();
    fixture.publish(1, 17, None);
    Connection::open(fixture.database())
        .unwrap()
        .execute(
            "UPDATE workspace_publication SET source_index_generation = 18 WHERE singleton = 1",
            [],
        )
        .unwrap();

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_MISMATCH", failure.code);
}

#[test]
fn worktree_publication_resolves_shared_repository_base() {
    let fixture = Fixture::new();
    let repository_base = fixture.write_repository_base(7);
    fixture.publish(1, 19, Some(repository_base.clone()));

    let resolved = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap();

    assert_eq!(
        Some(std::fs::canonicalize(repository_base).unwrap()),
        resolved.repository_base_database,
    );
    assert_eq!(
        Some(std::fs::canonicalize(fixture.overlay()).unwrap()),
        resolved.repository_overlay,
    );
}

#[test]
fn publication_rejects_an_unavailable_repository_base() {
    let fixture = Fixture::new();
    fixture.publish(
        1,
        19,
        Some(fixture.repository_base_path()),
    );

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_INVALID", failure.code);
}

#[test]
fn completed_read_rejects_a_moved_database_publication() {
    let fixture = Fixture::new();
    fixture.publish(1, 23, None);
    let published = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap();

    let failure = published
        .read(|_| {
            fixture.publish(2, 24, None);
            Ok(())
        })
        .unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_MOVED", failure.code);
}

#[cfg(unix)]
#[test]
fn symlinked_workspace_database_is_not_publication_authority() {
    use std::os::unix::fs::symlink;

    let fixture = Fixture::new();
    let database = fixture.publish(1, 31, None);
    let target = fixture.root.path().join("mutable-source-index.db");
    std::fs::rename(&database, &target).unwrap();
    symlink(&target, &database).unwrap();

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_INVALID", failure.code);
}

struct Fixture {
    root: TempDir,
    workspace_data: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let root = TempDir::new().unwrap();
        let workspace_data = root
            .path()
            .join("data/workspaces")
            .join("2".repeat(64));
        std::fs::create_dir_all(workspace_data.join("cache")).unwrap();
        Self {
            root,
            workspace_data,
        }
    }

    fn workspace_data(&self) -> &Path {
        &self.workspace_data
    }

    fn database(&self) -> PathBuf {
        self.workspace_data.join("cache/source-index.db")
    }

    fn overlay(&self) -> PathBuf {
        self.workspace_data.join("cache/repository-overlay.json")
    }

    fn repository_base_path(&self) -> PathBuf {
        self.root
            .path()
            .join("data/repositories")
            .join("1".repeat(64))
            .join("snapshots")
            .join(snapshot_directory_name())
            .join("source-index.db")
    }

    fn write_repository_base(&self, generation: u64) -> PathBuf {
        let database = self.repository_base_path();
        std::fs::create_dir_all(database.parent().unwrap()).unwrap();
        write_source_database(&database, generation);
        std::fs::write(
            database.with_file_name("manifest.json"),
            serde_json::to_vec(&serde_json::json!({
                "key": snapshot_key(),
                "files": {},
                "createdAt": 1
            }))
            .unwrap(),
        )
        .unwrap();
        database
    }

    fn publish(
        &self,
        revision: u64,
        source_generation: u64,
        repository_base_database: Option<PathBuf>,
    ) -> PathBuf {
        let database = self.database();
        if !database.exists() {
            write_source_database(&database, source_generation);
        } else {
            Connection::open(&database)
                .unwrap()
                .execute(
                    "UPDATE schema_version SET generation = ?1",
                    [i64::try_from(source_generation).unwrap()],
                )
                .unwrap();
        }
        if let Some(base) = &repository_base_database {
            std::fs::write(
                self.overlay(),
                serde_json::to_vec(&serde_json::json!({
                    "base": snapshot_key(),
                    "target": snapshot_key(),
                    "tombstones": [],
                    "shards": {},
                    "baseDatabase": base
                }))
                .unwrap(),
            )
            .unwrap();
        } else {
            let _ = std::fs::remove_file(self.overlay());
        }
        let connection = Connection::open(&database).unwrap();
        connection.execute_batch(PUBLICATION_SCHEMA).unwrap();
        connection
            .execute(
                "INSERT INTO workspace_publication(
                     singleton, revision, identity, source_index_generation,
                     source_revision, reference_revision, graph_revision, graph_blocker,
                     source_index_schema_version, published_at_epoch_millis, repository_overlay_file
                 ) VALUES (1, ?1, 'workspace-state-one', ?2, ?2, ?2, ?2, NULL, ?3, 1, ?4)
                 ON CONFLICT(singleton) DO UPDATE SET
                     revision = excluded.revision,
                     source_index_generation = excluded.source_index_generation,
                     source_revision = excluded.source_revision,
                     reference_revision = excluded.reference_revision,
                     graph_revision = excluded.graph_revision,
                     graph_blocker = excluded.graph_blocker,
                     repository_overlay_file = excluded.repository_overlay_file",
                rusqlite::params![
                    i64::try_from(revision).unwrap(),
                    i64::try_from(source_generation).unwrap(),
                    SOURCE_INDEX_SCHEMA_VERSION,
                    repository_base_database.map(|_| "repository-overlay.json"),
                ],
            )
            .unwrap();
        database
    }
}

fn snapshot_key() -> serde_json::Value {
    serde_json::json!({
        "treeOid": "a".repeat(40),
        "buildClasspathFingerprint": "b".repeat(64),
        "indexSchema": SOURCE_INDEX_SCHEMA_VERSION,
        "producerVersion": "test"
    })
}

fn snapshot_directory_name() -> String {
    let value = format!(
        "{}\n{}\n{}\n{}",
        "a".repeat(40),
        "b".repeat(64),
        SOURCE_INDEX_SCHEMA_VERSION,
        "test"
    );
    hex::encode(Sha256::digest(value.as_bytes()))
}

fn write_source_database(path: &Path, generation: u64) {
    let connection = Connection::open(path).unwrap();
    connection
        .execute_batch(
            "CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL);",
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO schema_version(version, generation) VALUES (?1, ?2)",
            rusqlite::params![
                SOURCE_INDEX_SCHEMA_VERSION,
                i64::try_from(generation).expect("test generation fits SQLite INTEGER"),
            ],
        )
        .unwrap();
}

const PUBLICATION_SCHEMA: &str =
    "CREATE TABLE IF NOT EXISTS workspace_publication(
         singleton INTEGER PRIMARY KEY,
         revision INTEGER NOT NULL,
         identity TEXT NOT NULL,
         source_index_generation INTEGER NOT NULL,
         source_revision INTEGER NOT NULL,
         reference_revision INTEGER NOT NULL,
         graph_revision INTEGER,
         graph_blocker TEXT,
         source_index_schema_version INTEGER NOT NULL,
         published_at_epoch_millis INTEGER NOT NULL,
         repository_overlay_file TEXT
     );";
