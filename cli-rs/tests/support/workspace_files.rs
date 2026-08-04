use std::path::{Path, PathBuf};

use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};

include!("workspace_files/fixture.rs");

pub(crate) fn publish_database_if_generation(database_path: &Path) -> Option<serde_json::Value> {
    let generation_directory = database_path.parent()?;
    let generations_directory = generation_directory.parent()?;
    let publication_directory = generations_directory.parent()?;
    if database_path.file_name()?.to_str()? != "source-index.db"
        || generations_directory.file_name()?.to_str()? != "generations"
        || publication_directory.file_name()?.to_str()? != "semantic-generations"
    {
        return None;
    }

    let connection = Connection::open(database_path).expect("published workspace database");
    let (schema_version, source_index_generation): (i64, i64) = connection
        .query_row(
            "SELECT version, generation FROM schema_version LIMIT 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .expect("published workspace database identity");
    let source_index_generation =
        u64::try_from(source_index_generation).expect("non-negative source generation");
    let generation_name = generation_directory
        .file_name()
        .and_then(|name| name.to_str())
        .expect("UTF-8 generation name");
    let mut manifest = serde_json::json!({
        "generation": source_index_generation.checked_add(1).expect("publication generation"),
        "identity": format!("test-{schema_version}-{source_index_generation}"),
        "sourceIndexGeneration": source_index_generation,
        "sourceIndexSchemaVersion": schema_version,
        "databaseFile": format!("{generation_name}/source-index.db"),
        "publishedAtEpochMillis": 1
    });
    if generation_directory
        .join("repository-overlay.json")
        .is_file()
    {
        manifest["repositoryOverlayFile"] = serde_json::json!("repository-overlay.json");
    }

    std::fs::create_dir_all(publication_directory).expect("publication directory");
    let next_pointer = publication_directory.join("current.json.next");
    std::fs::write(
        &next_pointer,
        serde_json::to_vec_pretty(&manifest).expect("published workspace manifest JSON"),
    )
    .expect("published workspace next pointer");
    std::fs::rename(next_pointer, publication_directory.join("current.json"))
        .expect("atomically publish workspace pointer");
    Some(manifest)
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
