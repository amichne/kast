use crate::error::{CliError, Result};
use serde::{Deserialize, Serialize};
use std::io::Read;
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Component, Path, PathBuf};

const PUBLICATION_DIRECTORY: &str = "semantic-generations";
const GENERATIONS_DIRECTORY: &str = "generations";
const CURRENT_POINTER: &str = "current.json";

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct PublishedWorkspaceGenerationManifest {
    pub(crate) generation: u64,
    pub(crate) identity: String,
    pub(crate) source_index_generation: u64,
    pub(crate) source_index_schema_version: i64,
    pub(crate) database_file: String,
    pub(crate) published_at_epoch_millis: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) repository_overlay_file: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct PublishedWorkspaceDatabase {
    pub(crate) manifest: PublishedWorkspaceGenerationManifest,
    pub(crate) database: PathBuf,
    pub(crate) repository_overlay: Option<PathBuf>,
    pub(crate) repository_base_database: Option<PathBuf>,
    current_pointer: PathBuf,
}

include!("published_workspace/database.rs");

pub(crate) fn resolve_published_workspace_database(
    workspace_root: &Path,
) -> Result<PublishedWorkspaceDatabase> {
    let workspace_data = crate::config::workspace_data_directory(workspace_root)?;
    resolve_published_workspace_database_from(&workspace_data)
}

fn resolve_published_workspace_database_from(
    workspace_data: &Path,
) -> Result<PublishedWorkspaceDatabase> {
    let publication_directory = workspace_data.join(PUBLICATION_DIRECTORY);
    let current_pointer = publication_directory.join(CURRENT_POINTER);
    let manifest = read_manifest(&current_pointer)?;
    validate_manifest_fields(&manifest)?;

    let generations = publication_directory.join(GENERATIONS_DIRECTORY);
    let database = generation_database(&generations, &manifest.database_file)?;
    validate_source_database(&database, &manifest)?;
    let (repository_overlay, repository_base_database) = resolve_repository_overlay(
        &database,
        manifest.repository_overlay_file.as_deref(),
        manifest.source_index_schema_version,
    )?;

    Ok(PublishedWorkspaceDatabase {
        manifest,
        database,
        repository_overlay,
        repository_base_database,
        current_pointer,
    })
}

fn read_manifest(current_pointer: &Path) -> Result<PublishedWorkspaceGenerationManifest> {
    let metadata = std::fs::symlink_metadata(current_pointer).map_err(|_| {
        CliError::new(
            "PUBLISHED_WORKSPACE_UNAVAILABLE",
            format!(
                "Published workspace pointer is unavailable: {}",
                current_pointer.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(invalid_publication(
            "Published workspace pointer must be a regular non-symlink file",
        ));
    }
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    options.custom_flags(libc::O_NOFOLLOW);
    let mut pointer = options.open(current_pointer).map_err(|error| {
        CliError::new(
            "PUBLISHED_WORKSPACE_UNAVAILABLE",
            format!(
                "Cannot read published workspace pointer {}: {error}",
                current_pointer.display()
            ),
        )
    })?;
    if !pointer
        .metadata()
        .map_err(|error| {
            invalid_publication(format!(
                "Cannot inspect published workspace pointer {}: {error}",
                current_pointer.display()
            ))
        })?
        .is_file()
    {
        return Err(invalid_publication(
            "Published workspace pointer must remain a regular file",
        ));
    }
    let mut bytes = Vec::new();
    pointer.read_to_end(&mut bytes).map_err(|error| {
        CliError::new(
            "PUBLISHED_WORKSPACE_UNAVAILABLE",
            format!(
                "Cannot read published workspace pointer {}: {error}",
                current_pointer.display()
            ),
        )
    })?;
    serde_json::from_slice(&bytes).map_err(|error| {
        invalid_publication(format!(
            "Cannot decode published workspace pointer {}: {error}",
            current_pointer.display()
        ))
    })
}

fn validate_manifest_fields(manifest: &PublishedWorkspaceGenerationManifest) -> Result<()> {
    if manifest.generation == 0 {
        return Err(invalid_publication(
            "Published workspace generation must be positive",
        ));
    }
    if manifest.identity.trim().is_empty() {
        return Err(invalid_publication(
            "Published workspace identity must not be blank",
        ));
    }
    if manifest.source_index_schema_version
        != crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION
    {
        return Err(CliError::new(
            "PUBLISHED_WORKSPACE_MISMATCH",
            format!(
                "Published source-index schema {} does not match this Kast build's schema {}.",
                manifest.source_index_schema_version,
                crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION,
            ),
        ));
    }
    Ok(())
}

fn generation_database(generations: &Path, database_file: &str) -> Result<PathBuf> {
    let relative = Path::new(database_file);
    let components = relative.components().collect::<Vec<_>>();
    if components.len() != 2
        || !components
            .iter()
            .all(|component| matches!(component, Component::Normal(_)))
        || relative.file_name().and_then(|name| name.to_str()) != Some("source-index.db")
    {
        return Err(invalid_publication(
            "Published database file must be source-index.db inside one generation directory",
        ));
    }
    let database = generations.join(relative);
    let metadata = std::fs::symlink_metadata(&database).map_err(|error| {
        invalid_publication(format!(
            "Published workspace database is unavailable at {}: {error}",
            database.display()
        ))
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(invalid_publication(
            "Published workspace database must be a regular non-symlink file",
        ));
    }
    let canonical_generations = std::fs::canonicalize(generations).map_err(|error| {
        invalid_publication(format!(
            "Cannot resolve published generations directory {}: {error}",
            generations.display()
        ))
    })?;
    let canonical_database = std::fs::canonicalize(&database).map_err(|error| {
        invalid_publication(format!(
            "Cannot resolve published workspace database {}: {error}",
            database.display()
        ))
    })?;
    if canonical_database.parent().and_then(Path::parent) != Some(canonical_generations.as_path()) {
        return Err(invalid_publication(
            "Published workspace database escaped its generations directory",
        ));
    }
    Ok(canonical_database)
}

fn validate_source_database(
    database: &Path,
    manifest: &PublishedWorkspaceGenerationManifest,
) -> Result<()> {
    let (schema_version, source_generation) = database_identity(database)?;
    if schema_version != manifest.source_index_schema_version
        || source_generation != manifest.source_index_generation
    {
        return Err(CliError::new(
            "PUBLISHED_WORKSPACE_MISMATCH",
            format!(
                "Published database identity {schema_version}:{source_generation} does not match manifest identity {}:{}.",
                manifest.source_index_schema_version, manifest.source_index_generation,
            ),
        ));
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PublishedRepositoryOverlayDescriptor {
    base_database: Option<PathBuf>,
}

fn resolve_repository_overlay(
    database: &Path,
    repository_overlay_file: Option<&str>,
    expected_schema: i64,
) -> Result<(Option<PathBuf>, Option<PathBuf>)> {
    let Some(file) = repository_overlay_file else {
        return Ok((None, None));
    };
    if file != "repository-overlay.json" {
        return Err(invalid_publication(
            "Published repository overlay must be repository-overlay.json",
        ));
    }
    let descriptor = database.with_file_name(file);
    let metadata = std::fs::symlink_metadata(&descriptor).map_err(|error| {
        invalid_publication(format!(
            "Published repository overlay is unavailable at {}: {error}",
            descriptor.display()
        ))
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(invalid_publication(
            "Published repository overlay must be a regular non-symlink file",
        ));
    }
    let descriptor = std::fs::canonicalize(&descriptor).map_err(|error| {
        invalid_publication(format!(
            "Cannot resolve published repository overlay {}: {error}",
            descriptor.display()
        ))
    })?;
    if descriptor.parent() != database.parent() {
        return Err(invalid_publication(
            "Published repository overlay escaped its generation directory",
        ));
    }
    let overlay: PublishedRepositoryOverlayDescriptor =
        serde_json::from_slice(&std::fs::read(&descriptor).map_err(|error| {
            invalid_publication(format!(
                "Cannot read published repository overlay {}: {error}",
                descriptor.display()
            ))
        })?)
        .map_err(|error| {
            invalid_publication(format!(
                "Cannot decode published repository overlay {}: {error}",
                descriptor.display()
            ))
        })?;
    let base = overlay
        .base_database
        .as_deref()
        .ok_or_else(|| invalid_publication("Published repository overlay has no base database"))?;
    Ok((
        Some(descriptor),
        Some(validate_repository_base(database, base, expected_schema)?),
    ))
}

fn validate_repository_base(
    published_database: &Path,
    repository_base: &Path,
    expected_schema: i64,
) -> Result<PathBuf> {
    if !repository_base.is_absolute() {
        return Err(invalid_publication(
            "Published repository base database must be absolute",
        ));
    }
    let expected_base = published_database.with_file_name("repository-base.db");
    let metadata = std::fs::symlink_metadata(repository_base).map_err(|error| {
        invalid_publication(format!(
            "Published repository base is unavailable at {}: {error}",
            repository_base.display()
        ))
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(invalid_publication(
            "Published repository base must be a regular non-symlink file",
        ));
    }
    let canonical = std::fs::canonicalize(repository_base).map_err(|error| {
        invalid_publication(format!(
            "Cannot resolve published repository base {}: {error}",
            repository_base.display()
        ))
    })?;
    let canonical_expected_base = std::fs::canonicalize(&expected_base).map_err(|error| {
        invalid_publication(format!(
            "Published repository base is unavailable at {}: {error}",
            expected_base.display()
        ))
    })?;
    if canonical != canonical_expected_base || canonical.parent() != published_database.parent() {
        return Err(invalid_publication(
            "Published repository base must be the repository-base.db sibling in the immutable generation directory",
        ));
    }
    let (schema_version, _) = database_identity(&canonical)?;
    if schema_version != expected_schema {
        return Err(CliError::new(
            "PUBLISHED_WORKSPACE_MISMATCH",
            format!(
                "Published repository base schema {schema_version} does not match manifest schema {expected_schema}.",
            ),
        ));
    }
    Ok(canonical)
}

fn database_identity(database: &Path) -> Result<(i64, u64)> {
    let connection = rusqlite::Connection::open_with_flags(
        database,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY
            | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX
            | rusqlite::OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| {
        invalid_publication(format!(
            "Cannot open published workspace database {}: {error}",
            database.display()
        ))
    })?;
    let (schema_version, generation): (i64, i64) = connection
        .query_row(
            "SELECT version, generation FROM schema_version LIMIT 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|error| {
            invalid_publication(format!(
                "Cannot read published workspace database identity from {}: {error}",
                database.display()
            ))
        })?;
    let generation = u64::try_from(generation).map_err(|_| {
        invalid_publication("Published source-index generation must not be negative")
    })?;
    Ok((schema_version, generation))
}

fn invalid_publication(message: impl Into<String>) -> CliError {
    CliError::new("PUBLISHED_WORKSPACE_INVALID", message)
}

#[cfg(test)]
mod tests {
    use super::*;
    include!("published_workspace/tests.rs");
}
