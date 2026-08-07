use super::{WORKSPACE_DATABASE_FILE, invalid_publication, non_negative_u64};
use crate::error::{CliError, Result};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

const REPOSITORY_OVERLAY_FILE: &str = "repository-overlay.json";

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PublishedSnapshotKey {
    tree_oid: String,
    build_classpath_fingerprint: String,
    index_schema: i64,
    producer_version: String,
}

impl PublishedSnapshotKey {
    fn directory_name(&self) -> String {
        let value = format!(
            "{}\n{}\n{}\n{}",
            self.tree_oid,
            self.build_classpath_fingerprint,
            self.index_schema,
            self.producer_version
        );
        hex::encode(Sha256::digest(value.as_bytes()))
    }

    fn validate(&self) -> Result<()> {
        if !is_lower_hex(&self.tree_oid, &[40, 64])
            || !is_lower_hex(&self.build_classpath_fingerprint, &[64])
            || self.index_schema <= 0
            || self.producer_version.trim() != self.producer_version
            || self.producer_version.is_empty()
            || self.producer_version.chars().any(char::is_control)
        {
            return Err(invalid_publication(
                "Published repository snapshot key is not canonical",
            ));
        }
        Ok(())
    }

    fn compatible_with(&self, other: &Self) -> bool {
        self.build_classpath_fingerprint == other.build_classpath_fingerprint
            && self.index_schema == other.index_schema
            && self.producer_version == other.producer_version
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PublishedRepositoryOverlayDescriptor {
    base: PublishedSnapshotKey,
    target: PublishedSnapshotKey,
    tombstones: BTreeSet<String>,
    shards: BTreeMap<String, serde_json::Value>,
    base_database: PathBuf,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PublishedSnapshotManifest {
    key: PublishedSnapshotKey,
    files: BTreeMap<String, String>,
    created_at: u64,
}

pub(super) fn resolve_repository_overlay(
    database: &Path,
    workspace_data: &Path,
    repository_overlay_file: Option<&str>,
    expected_schema: i64,
) -> Result<(Option<PathBuf>, Option<PathBuf>)> {
    let Some(file) = repository_overlay_file else {
        return Ok((None, None));
    };
    if file != REPOSITORY_OVERLAY_FILE {
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
            "Published repository overlay escaped its workspace cache directory",
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
    validate_overlay_descriptor(&overlay)?;
    Ok((
        Some(descriptor),
        Some(validate_repository_base(
            &overlay.base_database,
            &overlay.base,
            workspace_data,
            expected_schema,
        )?),
    ))
}

fn validate_overlay_descriptor(overlay: &PublishedRepositoryOverlayDescriptor) -> Result<()> {
    overlay.base.validate()?;
    overlay.target.validate()?;
    if !overlay.base.compatible_with(&overlay.target)
        || overlay
            .tombstones
            .iter()
            .any(|path| !canonical_relative_path(path))
        || overlay
            .shards
            .keys()
            .any(|path| !canonical_relative_path(path))
        || overlay
            .tombstones
            .iter()
            .any(|path| overlay.shards.contains_key(path))
    {
        return Err(invalid_publication(
            "Published repository overlay is incompatible or contains noncanonical paths",
        ));
    }
    Ok(())
}

fn validate_repository_base(
    repository_base: &Path,
    expected_key: &PublishedSnapshotKey,
    workspace_data: &Path,
    expected_schema: i64,
) -> Result<PathBuf> {
    if !repository_base.is_absolute() {
        return Err(invalid_publication(
            "Published repository base database must be absolute",
        ));
    }
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
    let data_root = workspace_data
        .parent()
        .and_then(Path::parent)
        .ok_or_else(|| invalid_publication("Workspace data path has no data-root authority"))?;
    let snapshot_directory = canonical
        .parent()
        .ok_or_else(|| invalid_publication("Repository snapshot base has no directory"))?;
    let snapshots_directory = snapshot_directory.parent().ok_or_else(|| {
        invalid_publication("Repository snapshot base has no snapshots directory")
    })?;
    let repository_directory = snapshots_directory.parent().ok_or_else(|| {
        invalid_publication("Repository snapshot base has no repository directory")
    })?;
    let repositories_directory = repository_directory.parent().ok_or_else(|| {
        invalid_publication("Repository snapshot base has no repositories directory")
    })?;
    let expected_snapshot_directory = expected_key.directory_name();
    if canonical.file_name().and_then(|name| name.to_str()) != Some(WORKSPACE_DATABASE_FILE)
        || snapshot_directory
            .file_name()
            .and_then(|name| name.to_str())
            != Some(expected_snapshot_directory.as_str())
        || snapshots_directory
            .file_name()
            .and_then(|name| name.to_str())
            != Some("snapshots")
        || repositories_directory
            .file_name()
            .and_then(|name| name.to_str())
            != Some("repositories")
        || !is_lower_hex(
            repository_directory
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default(),
            &[64],
        )
        || std::fs::canonicalize(
            repositories_directory
                .parent()
                .unwrap_or(repositories_directory),
        )
        .ok()
        .as_deref()
            != std::fs::canonicalize(data_root).ok().as_deref()
    {
        return Err(invalid_publication(
            "Published repository base is outside its repository-keyed snapshot authority",
        ));
    }
    let manifest_path = snapshot_directory.join("manifest.json");
    let manifest_metadata = std::fs::symlink_metadata(&manifest_path).map_err(|error| {
        invalid_publication(format!(
            "Published repository snapshot manifest is unavailable at {}: {error}",
            manifest_path.display()
        ))
    })?;
    if manifest_metadata.file_type().is_symlink() || !manifest_metadata.is_file() {
        return Err(invalid_publication(
            "Published repository snapshot manifest must be a regular non-symlink file",
        ));
    }
    let snapshot_manifest: PublishedSnapshotManifest =
        serde_json::from_slice(&std::fs::read(&manifest_path).map_err(|error| {
            invalid_publication(format!(
                "Cannot read published repository snapshot manifest: {error}"
            ))
        })?)
        .map_err(|error| {
            invalid_publication(format!(
                "Cannot decode repository snapshot manifest: {error}"
            ))
        })?;
    let _ = snapshot_manifest.created_at;
    if snapshot_manifest.key != *expected_key
        || snapshot_manifest
            .files
            .keys()
            .any(|path| !canonical_relative_path(path))
    {
        return Err(invalid_publication(
            "Published repository snapshot manifest does not match the overlay base key",
        ));
    }
    let (schema_version, _) = database_identity(&canonical)?;
    if schema_version != expected_schema {
        return Err(CliError::new(
            "PUBLISHED_WORKSPACE_MISMATCH",
            format!(
                "Published repository base schema {schema_version} does not match publication schema {expected_schema}.",
            ),
        ));
    }
    Ok(canonical)
}

fn canonical_relative_path(raw: &str) -> bool {
    !raw.is_empty()
        && !raw.contains('\\')
        && !Path::new(raw).is_absolute()
        && Path::new(raw)
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
}

fn is_lower_hex(value: &str, lengths: &[usize]) -> bool {
    lengths.contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
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
                "Cannot read published database identity from {}: {error}",
                database.display()
            ))
        })?;
    Ok((
        schema_version,
        non_negative_u64(generation, "source-index generation")?,
    ))
}
