use crate::error::{CliError, Result};
use rusqlite::{OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[cfg(test)]
use sha2::{Digest, Sha256};

const WORKSPACE_CACHE_DIRECTORY: &str = "cache";
const WORKSPACE_DATABASE_FILE: &str = "source-index.db";

#[path = "published_workspace/overlay.rs"]
mod overlay;
use overlay::resolve_repository_overlay;

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
    workspace_data: PathBuf,
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
    let workspace_data = workspace_data.to_path_buf();
    let database = resolve_workspace_database(&workspace_data)?;
    let manifest = read_database_publication(&database)?;
    validate_manifest_fields(&manifest)?;
    let (repository_overlay, repository_base_database) = resolve_repository_overlay(
        &database,
        &workspace_data,
        manifest.repository_overlay_file.as_deref(),
        manifest.source_index_schema_version,
    )?;

    Ok(PublishedWorkspaceDatabase {
        manifest,
        database,
        repository_overlay,
        repository_base_database,
        workspace_data,
    })
}

fn resolve_workspace_database(workspace_data: &Path) -> Result<PathBuf> {
    let database = workspace_data
        .join(WORKSPACE_CACHE_DIRECTORY)
        .join(WORKSPACE_DATABASE_FILE);
    let metadata = std::fs::symlink_metadata(&database).map_err(|_| {
        CliError::new(
            "PUBLISHED_WORKSPACE_UNAVAILABLE",
            format!(
                "Published workspace database is unavailable: {}",
                database.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(invalid_publication(
            "Published workspace database must be a regular non-symlink file",
        ));
    }
    let canonical_cache = std::fs::canonicalize(workspace_data.join(WORKSPACE_CACHE_DIRECTORY))
        .map_err(|error| {
            invalid_publication(format!(
                "Cannot resolve workspace cache directory {}: {error}",
                workspace_data.join(WORKSPACE_CACHE_DIRECTORY).display()
            ))
        })?;
    let canonical_database = std::fs::canonicalize(&database).map_err(|error| {
        invalid_publication(format!(
            "Cannot resolve published workspace database {}: {error}",
            database.display()
        ))
    })?;
    if canonical_database.parent() != Some(canonical_cache.as_path()) {
        return Err(invalid_publication(
            "Published workspace database escaped its workspace cache directory",
        ));
    }
    Ok(canonical_database)
}

fn read_database_publication(database: &Path) -> Result<PublishedWorkspaceGenerationManifest> {
    let mut connection = rusqlite::Connection::open_with_flags(
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
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Deferred)
        .map_err(|error| invalid_publication(format!("Cannot begin publication read: {error}")))?;
    let row = transaction
        .query_row(
            "SELECT publication.revision, publication.identity,
                    publication.source_index_generation,
                    publication.source_index_schema_version,
                    publication.published_at_epoch_millis,
                    publication.repository_overlay_file,
                    schema.version, schema.generation
             FROM workspace_publication publication
             CROSS JOIN schema_version schema
             WHERE publication.singleton = 1
             LIMIT 1",
            [],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, Option<String>>(5)?,
                    row.get::<_, i64>(6)?,
                    row.get::<_, i64>(7)?,
                ))
            },
        )
        .optional()
        .map_err(|error| {
            invalid_publication(format!(
                "Cannot read workspace publication from {}: {error}",
                database.display()
            ))
        })?
        .ok_or_else(|| {
            CliError::new(
                "PUBLISHED_WORKSPACE_UNAVAILABLE",
                "The workspace database has no committed publication.",
            )
        })?;
    transaction
        .commit()
        .map_err(|error| invalid_publication(format!("Cannot finish publication read: {error}")))?;

    let revision = non_negative_u64(row.0, "workspace publication revision")?;
    if revision == 0 {
        return Err(invalid_publication(
            "Published workspace revision must be positive",
        ));
    }
    let source_index_generation = non_negative_u64(row.2, "source-index generation")?;
    let published_at_epoch_millis = non_negative_u64(row.4, "publication time")?;
    if row.3 != row.6 || source_index_generation != non_negative_u64(row.7, "schema generation")? {
        return Err(CliError::new(
            "PUBLISHED_WORKSPACE_MISMATCH",
            format!(
                "Workspace publication identity {}:{} does not match database identity {}:{}.",
                row.3, source_index_generation, row.6, row.7
            ),
        ));
    }
    Ok(PublishedWorkspaceGenerationManifest {
        generation: revision,
        identity: row.1,
        source_index_generation,
        source_index_schema_version: row.3,
        database_file: WORKSPACE_DATABASE_FILE.to_string(),
        published_at_epoch_millis,
        repository_overlay_file: row.5,
    })
}

fn non_negative_u64(value: i64, description: &str) -> Result<u64> {
    u64::try_from(value)
        .map_err(|_| invalid_publication(format!("Published {description} is negative")))
}

fn validate_manifest_fields(manifest: &PublishedWorkspaceGenerationManifest) -> Result<()> {
    if manifest.identity.trim().is_empty() {
        return Err(invalid_publication(
            "Published workspace identity must not be blank",
        ));
    }
    if manifest.database_file != WORKSPACE_DATABASE_FILE {
        return Err(invalid_publication(
            "Published database must be the single workspace source-index.db",
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

fn invalid_publication(message: impl Into<String>) -> CliError {
    CliError::new("PUBLISHED_WORKSPACE_INVALID", message)
}

#[cfg(test)]
mod tests {
    use super::*;
    include!("published_workspace/tests.rs");
}
