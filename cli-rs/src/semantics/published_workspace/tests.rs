use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use rusqlite::Connection;
use tempfile::TempDir;

#[test]
fn native_graph_uses_published_database_not_live_candidate() {
    let fixture = Fixture::new();
    std::fs::write(fixture.live_database(), b"unpublished candidate").unwrap();
    let published = fixture.publish("generation-1", 11, None);

    let resolved = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap();

    assert_eq!(std::fs::canonicalize(published).unwrap(), resolved.database);
    assert_ne!(fixture.live_database(), resolved.database);
    resolved.revalidate().unwrap();
}

#[test]
fn missing_current_pointer_rejects_live_candidate() {
    let fixture = Fixture::new();
    std::fs::write(fixture.live_database(), b"unpublished candidate").unwrap();

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_UNAVAILABLE", failure.code);
}

#[test]
fn published_manifest_rejects_database_escape() {
    let fixture = Fixture::new();
    let outside = fixture.workspace_data().join("outside.db");
    write_source_database(&outside, 13);
    fixture.write_manifest("../outside.db", 13, None);

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_INVALID", failure.code);
}

#[test]
fn published_manifest_rejects_schema_or_source_generation_mismatch() {
    let fixture = Fixture::new();
    fixture.publish("generation-1", 17, None);
    fixture.write_manifest("generation-1/source-index.db", 18, None);

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_MISMATCH", failure.code);
}

#[test]
fn worktree_generation_resolves_bound_repository_base() {
    let fixture = Fixture::new();
    let repository_base = fixture
        .generation_directory("generation-1")
        .join("repository-base.db");
    std::fs::create_dir_all(repository_base.parent().unwrap()).unwrap();
    write_source_database(&repository_base, 7);
    fixture.publish("generation-1", 19, Some(repository_base.clone()));

    let resolved = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap();

    assert_eq!(
        Some(std::fs::canonicalize(repository_base).unwrap()),
        resolved.repository_base_database,
    );
    assert_eq!(
        Some(
            std::fs::canonicalize(
                fixture
                    .workspace_data()
                    .join("semantic-generations/generations/generation-1/repository-overlay.json"),
            )
            .unwrap(),
        ),
        resolved.repository_overlay,
    );
}

#[test]
fn published_overlay_rejects_external_same_schema_base() {
    let fixture = Fixture::new();
    let external_base = fixture.root.path().join("repository-base.db");
    write_source_database(&external_base, 7);
    fixture.publish("generation-1", 19, Some(external_base));

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_INVALID", failure.code);
}

#[test]
fn completed_read_rejects_a_moved_current_pointer() {
    let fixture = Fixture::new();
    fixture.publish("generation-1", 23, None);
    let published = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap();

    let failure = published
        .read(|_| {
            fixture.publish("generation-2", 24, None);
            Ok(())
        })
        .unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_MOVED", failure.code);
}

#[test]
fn invalid_current_pointer_rejects_a_live_candidate() {
    let fixture = Fixture::new();
    std::fs::write(fixture.live_database(), b"unpublished candidate").unwrap();
    std::fs::write(
        fixture.workspace_data().join("semantic-generations/current.json"),
        b"not-json",
    )
    .unwrap();

    let failure = resolve_published_workspace_database_from(fixture.workspace_data()).unwrap_err();

    assert_eq!("PUBLISHED_WORKSPACE_INVALID", failure.code);
}

#[cfg(unix)]
#[test]
fn symlinked_current_pointer_is_not_publication_authority() {
    use std::os::unix::fs::symlink;

    let fixture = Fixture::new();
    fixture.publish("generation-1", 31, None);
    let current = fixture
        .workspace_data()
        .join("semantic-generations/current.json");
    let target = fixture.root.path().join("mutable-current.json");
    std::fs::rename(&current, &target).unwrap();
    symlink(&target, &current).unwrap();

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
        let workspace_data = root.path().join("workspace-data");
        std::fs::create_dir_all(workspace_data.join("cache")).unwrap();
        std::fs::create_dir_all(workspace_data.join("semantic-generations/generations")).unwrap();
        Self {
            root,
            workspace_data,
        }
    }

    fn workspace_data(&self) -> &Path {
        &self.workspace_data
    }

    fn live_database(&self) -> PathBuf {
        self.workspace_data.join("cache/source-index.db")
    }

    fn generation_directory(&self, generation: &str) -> PathBuf {
        self.workspace_data
            .join("semantic-generations/generations")
            .join(generation)
    }

    fn publish(
        &self,
        generation_directory: &str,
        source_generation: u64,
        repository_base_database: Option<PathBuf>,
    ) -> PathBuf {
        let database = self
            .generation_directory(generation_directory)
            .join("source-index.db");
        std::fs::create_dir_all(database.parent().unwrap()).unwrap();
        write_source_database(&database, source_generation);
        if let Some(base) = &repository_base_database {
            std::fs::write(
                database.with_file_name("repository-overlay.json"),
                serde_json::to_vec(&serde_json::json!({ "baseDatabase": base })).unwrap(),
            )
            .unwrap();
        }
        self.write_manifest(
            &format!("{generation_directory}/source-index.db"),
            source_generation,
            repository_base_database,
        );
        database
    }

    fn write_manifest(
        &self,
        database_file: &str,
        source_generation: u64,
        repository_base_database: Option<PathBuf>,
    ) {
        let manifest = PublishedWorkspaceGenerationManifest {
            generation: 1,
            identity: "workspace-state-one".to_string(),
            source_index_generation: source_generation,
            source_index_schema_version: SOURCE_INDEX_SCHEMA_VERSION,
            database_file: database_file.to_string(),
            published_at_epoch_millis: 1,
            repository_overlay_file: repository_base_database
                .map(|_| "repository-overlay.json".to_string()),
        };
        std::fs::write(
            self.workspace_data.join("semantic-generations/current.json"),
            serde_json::to_vec(&manifest).unwrap(),
        )
        .unwrap();
    }
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
