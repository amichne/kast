use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags, OptionalExtension, Transaction, TransactionBehavior};

use crate::config;
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;

use super::model::{
    BuildQualifiedGradleProjectIdentity, BuildQualifiedGradleSourceSetIdentity,
    KotlinPackageFqName, LegacySourceSetLabel, SourceIndexGeneration, SourceIndexIncompatibility,
    SourceIndexModuleProgress, SourceIndexPendingCount, SourceIndexSnapshotStamp,
    WorkspaceCoverageDimension, WorkspaceEvidenceSource, WorkspaceFileDrift,
    WorkspaceFileIndexState, WorkspaceFilePath, WorkspaceIndexRead, WorkspaceIndexReadFailure,
    WorkspaceIndexSnapshot, WorkspaceInventoryFile, WorkspaceInventoryLimitationCode,
    WorkspaceMatchCoverage, WorkspacePackageEvidence, WorkspacePackageInvalidReference,
    WorkspacePackageUnprovenReason, WorkspaceRoot, WorkspaceSourceSetEvidence,
};

type FileKey = (i64, String);

const ABSOLUTE_PATH_PREFIX: &str = "__kast_abs__/";
const RELATIVE_ESCAPE_PREFIX: &str = "__kast_rel__/";

const REQUIRED_TABLE_COLUMNS: &[(&str, &[&str])] = &[
    ("schema_version", &["version", "generation"]),
    ("path_prefixes", &["prefix_id", "dir_path"]),
    (
        "file_manifest",
        &["prefix_id", "filename", "last_modified_millis"],
    ),
    (
        "file_metadata",
        &[
            "prefix_id",
            "filename",
            "package_fq_id",
            "package_state",
            "package_unproven_reason",
            "module_path",
            "source_set",
        ],
    ),
    ("fq_names", &["fq_id", "fq_name"]),
    (
        "file_gradle_projects",
        &["prefix_id", "filename", "build_root", "project_path"],
    ),
    (
        "file_gradle_source_sets",
        &[
            "prefix_id",
            "filename",
            "build_root",
            "project_path",
            "source_set_name",
        ],
    ),
    (
        "module_index_progress",
        &[
            "module_name",
            "phase2_status",
            "indexed_file_count",
            "total_file_count",
        ],
    ),
    ("pending_updates", &["applied"]),
];

pub(super) fn read_workspace_index(root: &WorkspaceRoot) -> WorkspaceIndexRead {
    let database_path = match config::workspace_database_path(root.as_path()) {
        Ok(path) => path,
        Err(error) => {
            return unavailable(format!(
                "source-index path cannot be resolved for `{}`: {error}",
                root.as_path().display()
            ));
        }
    };
    if !database_path.is_file() {
        return unavailable(format!(
            "source-index database is unavailable at `{}`",
            database_path.display()
        ));
    }
    match read_database(root, &database_path) {
        Ok(snapshot) => WorkspaceIndexRead::Snapshot(snapshot),
        Err(ReadDatabaseError::Unavailable(detail)) => unavailable(detail),
        Err(ReadDatabaseError::Incompatible(detail)) => incompatible(detail),
    }
}

fn read_database(
    root: &WorkspaceRoot,
    database_path: &Path,
) -> Result<WorkspaceIndexSnapshot, ReadDatabaseError> {
    let mut connection = Connection::open_with_flags(
        database_path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| ReadDatabaseError::Unavailable(error.to_string()))?;
    source_index_db::configure_read_connection(&connection)
        .map_err(|error| ReadDatabaseError::Unavailable(error.to_string()))?;
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Deferred)
        .map_err(|error| ReadDatabaseError::Unavailable(error.to_string()))?;
    let snapshot = read_transaction(root, &transaction)?;
    transaction
        .commit()
        .map_err(|error| ReadDatabaseError::Unavailable(error.to_string()))?;
    Ok(snapshot)
}

fn read_transaction(
    root: &WorkspaceRoot,
    transaction: &Transaction<'_>,
) -> Result<WorkspaceIndexSnapshot, ReadDatabaseError> {
    verify_required_structure(transaction)?;
    let generation = read_generation(transaction)?;
    let (module_progress, invalid_progress_count) = read_module_progress(transaction)?;
    let pending_count = read_pending_count(transaction)?;
    let stamp = SourceIndexSnapshotStamp::new(
        generation,
        module_progress,
        pending_count,
        invalid_progress_count == 0,
    );
    let mut associations = read_associations(transaction)?;
    let manifest_rows = read_manifest(transaction)?;
    let manifest_keys: BTreeSet<_> = manifest_rows.iter().map(|row| row.key.clone()).collect();
    let orphan_associations = associations.remove_orphan_rows(&manifest_keys);

    let mut files = Vec::new();
    let mut limitations = BTreeMap::new();
    let mut candidate_partial = invalid_progress_count > 0 || orphan_associations > 0;
    let mut filter_partial = orphan_associations > 0;
    increment_limitation(
        &mut limitations,
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
        invalid_progress_count + orphan_associations,
    );

    for row in manifest_rows {
        if !is_kotlin_source(&row.filename) {
            continue;
        }
        let Some(ref dir_path) = row.dir_path else {
            candidate_partial = true;
            increment_limitation(
                &mut limitations,
                WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
                1,
            );
            continue;
        };
        let relative_path = relative_manifest_path(dir_path, &row.filename);
        let Some(relative_path) = relative_path else {
            candidate_partial = true;
            increment_limitation(
                &mut limitations,
                WorkspaceInventoryLimitationCode::OutOfRootExcluded,
                1,
            );
            continue;
        };
        let (drift, containment) = contain_path(root, relative_path.as_path());
        match containment {
            PathContainment::Contained => {}
            PathContainment::Outside => {
                candidate_partial = true;
                increment_limitation(
                    &mut limitations,
                    WorkspaceInventoryLimitationCode::OutOfRootExcluded,
                    1,
                );
                continue;
            }
            PathContainment::Unprovable => {
                candidate_partial = true;
                increment_limitation(
                    &mut limitations,
                    WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
                    1,
                );
                continue;
            }
        }

        let mut incompatibilities = BTreeSet::new();
        let (package, package_is_proven) = decode_package(&row);
        let metadata_evidence = row
            .metadata_present
            .then_some(WorkspaceEvidenceSource::PackageMetadata);
        if matches!(package, WorkspacePackageEvidence::InvalidReference(_)) {
            incompatibilities.insert(SourceIndexIncompatibility::InvalidPackageMetadata);
            filter_partial = true;
            increment_limitation(
                &mut limitations,
                WorkspaceInventoryLimitationCode::PackageMetadataInvalid,
                1,
            );
        } else if !package_is_proven {
            filter_partial = true;
        }

        let mut project_rows_invalid = associations
            .invalid_projects
            .remove(&row.key)
            .unwrap_or_default();
        let mut source_set_rows_invalid = associations
            .invalid_source_sets
            .remove(&row.key)
            .unwrap_or_default();
        let mut projects = associations.projects.remove(&row.key).unwrap_or_default();
        let source_sets = associations
            .source_sets
            .remove(&row.key)
            .unwrap_or_default();
        if !row.metadata_present {
            project_rows_invalid = project_rows_invalid.saturating_add(projects.len());
            source_set_rows_invalid = source_set_rows_invalid.saturating_add(source_sets.len());
            projects.clear();
        }
        if project_rows_invalid > 0 {
            projects.clear();
            incompatibilities.insert(SourceIndexIncompatibility::InvalidGradleProjectIdentity);
            filter_partial = true;
        }
        let source_set_evidence = if project_rows_invalid > 0 || source_set_rows_invalid > 0 {
            incompatibilities.insert(SourceIndexIncompatibility::InvalidGradleSourceSetIdentity);
            filter_partial = true;
            WorkspaceSourceSetEvidence::Unavailable
        } else if !source_sets.is_empty() {
            WorkspaceSourceSetEvidence::Proven(source_sets)
        } else if let Some(legacy_source_set) =
            row.legacy_source_set.and_then(LegacySourceSetLabel::parse)
        {
            filter_partial = true;
            WorkspaceSourceSetEvidence::Unproven(BTreeSet::from([legacy_source_set]))
        } else {
            filter_partial = true;
            WorkspaceSourceSetEvidence::Unavailable
        };
        if project_rows_invalid + source_set_rows_invalid > 0 {
            increment_limitation(
                &mut limitations,
                WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
                project_rows_invalid + source_set_rows_invalid,
            );
        }
        if projects.is_empty() {
            filter_partial = true;
            increment_limitation(
                &mut limitations,
                WorkspaceInventoryLimitationCode::UnknownProjectModelOwnership,
                1,
            );
        }
        let index_state = if !incompatibilities.is_empty() {
            WorkspaceFileIndexState::Incompatible(incompatibilities)
        } else if row.metadata_present {
            WorkspaceFileIndexState::Indexed
        } else {
            WorkspaceFileIndexState::MetadataUnavailable
        };
        let mut evidence = BTreeSet::from([WorkspaceEvidenceSource::Manifest]);
        evidence.extend(metadata_evidence);
        if !projects.is_empty() {
            evidence.insert(WorkspaceEvidenceSource::GradleProjectModel);
        }
        files.push(WorkspaceInventoryFile::indexed_source(
            relative_path,
            projects,
            source_set_evidence,
            package,
            index_state,
            drift,
            evidence,
        ));
    }

    if !stamp.pending_count().is_empty() {
        candidate_partial = true;
        increment_limitation(
            &mut limitations,
            WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending,
            usize::try_from(stamp.pending_count().value()).unwrap_or(usize::MAX),
        );
    }
    if stamp.module_progress().is_empty()
        || stamp.module_progress().iter().any(|progress| {
            progress.status() != super::model::SourceIndexProgressStatus::Complete
                || progress.indexed_file_count() != progress.total_file_count()
        })
    {
        candidate_partial = true;
        increment_limitation(
            &mut limitations,
            WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete,
            1,
        );
    }
    let coverage = WorkspaceMatchCoverage::from_dimensions(
        coverage_dimension(candidate_partial),
        coverage_dimension(filter_partial),
    );
    Ok(WorkspaceIndexSnapshot::new(
        files,
        stamp,
        limitations,
        coverage,
    ))
}

fn read_generation(
    transaction: &Transaction<'_>,
) -> Result<SourceIndexGeneration, ReadDatabaseError> {
    let (row_count, version, generation) = transaction
        .query_row(
            "SELECT COUNT(*), MIN(version), MIN(generation) FROM schema_version",
            [],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, Option<i64>>(1)?,
                    row.get::<_, Option<i64>>(2)?,
                ))
            },
        )
        .map_err(incompatible_sql)?;
    if row_count != 1 {
        return Err(ReadDatabaseError::Incompatible(format!(
            "schema_version must contain exactly one row, found {row_count}"
        )));
    }
    let version = version.ok_or_else(|| {
        ReadDatabaseError::Incompatible("schema_version.version is unavailable".to_string())
    })?;
    if version != SOURCE_INDEX_SCHEMA_VERSION {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index schema {} is incompatible with required schema {SOURCE_INDEX_SCHEMA_VERSION}",
            version
        )));
    }
    SourceIndexGeneration::try_from_database(generation.ok_or_else(|| {
        ReadDatabaseError::Incompatible("schema_version.generation is unavailable".to_string())
    })?)
    .ok_or_else(|| {
        ReadDatabaseError::Incompatible("source-index generation is negative".to_string())
    })
}

fn read_module_progress(
    transaction: &Transaction<'_>,
) -> Result<(BTreeSet<SourceIndexModuleProgress>, usize), ReadDatabaseError> {
    let mut statement = transaction
        .prepare(
            "SELECT module_name, phase2_status, indexed_file_count, total_file_count FROM module_index_progress ORDER BY module_name",
        )
        .map_err(incompatible_sql)?;
    let mut rows = statement.query([]).map_err(incompatible_sql)?;
    let mut progress = BTreeSet::new();
    let mut invalid_count = 0;
    while let Some(row) = rows.next().map_err(incompatible_sql)? {
        let decoded = SourceIndexModuleProgress::from_database(
            row.get(0).map_err(incompatible_sql)?,
            row.get(1).map_err(incompatible_sql)?,
            row.get(2).map_err(incompatible_sql)?,
            row.get(3).map_err(incompatible_sql)?,
        );
        if let Some(decoded) = decoded {
            progress.insert(decoded);
        } else {
            invalid_count += 1;
        }
    }
    Ok((progress, invalid_count))
}

fn read_pending_count(
    transaction: &Transaction<'_>,
) -> Result<SourceIndexPendingCount, ReadDatabaseError> {
    let invalid_count = transaction
        .query_row(
            "SELECT COUNT(*) FROM pending_updates WHERE applied IS NULL OR typeof(applied) <> 'integer' OR applied NOT IN (0, 1)",
            [],
            |row| row.get::<_, i64>(0),
        )
        .map_err(incompatible_sql)?;
    if invalid_count > 0 {
        return Err(ReadDatabaseError::Incompatible(format!(
            "pending_updates contains {invalid_count} invalid applied states"
        )));
    }
    let count = transaction
        .query_row(
            "SELECT COUNT(*) FROM pending_updates WHERE applied = 0",
            [],
            |row| row.get::<_, i64>(0),
        )
        .map_err(incompatible_sql)?;
    SourceIndexPendingCount::try_from_database(count).ok_or_else(|| {
        ReadDatabaseError::Incompatible("source-index pending count is negative".to_string())
    })
}

fn read_manifest(transaction: &Transaction<'_>) -> Result<Vec<ManifestRow>, ReadDatabaseError> {
    let mut statement = transaction
        .prepare(
            r#"
            SELECT manifest.prefix_id,
                   manifest.filename,
                   prefixes.dir_path,
                   CASE WHEN metadata.prefix_id IS NULL THEN 0 ELSE 1 END AS metadata_present,
                   metadata.package_state,
                   metadata.package_unproven_reason,
                   metadata.package_fq_id,
                   names.fq_name,
                   metadata.source_set
              FROM file_manifest manifest
              LEFT JOIN path_prefixes prefixes
                ON prefixes.prefix_id = manifest.prefix_id
              LEFT JOIN file_metadata metadata
                ON metadata.prefix_id = manifest.prefix_id
               AND metadata.filename = manifest.filename
              LEFT JOIN fq_names names
                ON names.fq_id = metadata.package_fq_id
             ORDER BY manifest.prefix_id, manifest.filename
            "#,
        )
        .map_err(incompatible_sql)?;
    let rows = statement
        .query_map([], |row| {
            let prefix_id = row.get(0)?;
            let filename: String = row.get(1)?;
            Ok(ManifestRow {
                key: (prefix_id, filename.clone()),
                filename,
                dir_path: row.get(2)?,
                metadata_present: row.get::<_, i64>(3)? != 0,
                package_state: row.get(4)?,
                package_unproven_reason: row.get(5)?,
                package_fq_id: row.get(6)?,
                package_fq_name: row.get(7)?,
                legacy_source_set: row.get(8)?,
            })
        })
        .map_err(incompatible_sql)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(incompatible_sql)
}

fn read_associations(transaction: &Transaction<'_>) -> Result<AssociationRows, ReadDatabaseError> {
    let mut associations = AssociationRows::default();
    let mut project_statement = transaction
        .prepare(
            "SELECT prefix_id, filename, build_root, project_path FROM file_gradle_projects ORDER BY prefix_id, filename, build_root, project_path",
        )
        .map_err(incompatible_sql)?;
    let mut project_rows = project_statement.query([]).map_err(incompatible_sql)?;
    while let Some(row) = project_rows.next().map_err(incompatible_sql)? {
        let key = (
            row.get(0).map_err(incompatible_sql)?,
            row.get(1).map_err(incompatible_sql)?,
        );
        let identity = BuildQualifiedGradleProjectIdentity::parse(
            row.get(2).map_err(incompatible_sql)?,
            row.get(3).map_err(incompatible_sql)?,
        );
        if let Some(identity) = identity {
            associations
                .projects
                .entry(key)
                .or_default()
                .insert(identity);
        } else {
            *associations.invalid_projects.entry(key).or_default() += 1;
        }
    }

    let mut source_set_statement = transaction
        .prepare(
            "SELECT prefix_id, filename, build_root, project_path, source_set_name FROM file_gradle_source_sets ORDER BY prefix_id, filename, build_root, project_path, source_set_name",
        )
        .map_err(incompatible_sql)?;
    let mut source_set_rows = source_set_statement.query([]).map_err(incompatible_sql)?;
    while let Some(row) = source_set_rows.next().map_err(incompatible_sql)? {
        let key = (
            row.get(0).map_err(incompatible_sql)?,
            row.get(1).map_err(incompatible_sql)?,
        );
        let identity = BuildQualifiedGradleSourceSetIdentity::parse(
            row.get(2).map_err(incompatible_sql)?,
            row.get(3).map_err(incompatible_sql)?,
            row.get(4).map_err(incompatible_sql)?,
        );
        let Some(identity) = identity else {
            *associations.invalid_source_sets.entry(key).or_default() += 1;
            continue;
        };
        let project_exists = associations
            .projects
            .get(&key)
            .is_some_and(|projects| projects.contains(identity.project()));
        if project_exists {
            associations
                .source_sets
                .entry(key)
                .or_default()
                .insert(identity);
        } else {
            *associations.invalid_source_sets.entry(key).or_default() += 1;
        }
    }
    Ok(associations)
}

fn decode_package(row: &ManifestRow) -> (WorkspacePackageEvidence, bool) {
    if !row.metadata_present {
        return (WorkspacePackageEvidence::Unavailable, false);
    }
    match row.package_state.as_deref() {
        Some("PROVEN_ROOT")
            if row.package_fq_id.is_none()
                && row.package_fq_name.is_none()
                && row.package_unproven_reason.is_none() =>
        {
            (WorkspacePackageEvidence::ProvenRoot, true)
        }
        Some("PROVEN_NAMED")
            if row.package_fq_id.is_some() && row.package_unproven_reason.is_none() =>
        {
            let Some(fq_name) = row.package_fq_name.clone() else {
                return (
                    WorkspacePackageEvidence::InvalidReference(
                        WorkspacePackageInvalidReference::DanglingFqName,
                    ),
                    false,
                );
            };
            match KotlinPackageFqName::parse_persisted(fq_name) {
                Some(name) => (WorkspacePackageEvidence::ProvenNamed(name), true),
                None => (
                    WorkspacePackageEvidence::InvalidReference(
                        WorkspacePackageInvalidReference::InvalidFqName,
                    ),
                    false,
                ),
            }
        }
        Some("UNPROVEN") if row.package_fq_id.is_none() && row.package_fq_name.is_none() => {
            match row
                .package_unproven_reason
                .as_deref()
                .and_then(WorkspacePackageUnprovenReason::parse)
            {
                Some(reason) => (WorkspacePackageEvidence::Unproven(reason), false),
                None => (
                    WorkspacePackageEvidence::InvalidReference(
                        WorkspacePackageInvalidReference::IllegalStateTuple,
                    ),
                    false,
                ),
            }
        }
        Some("PROVEN_ROOT" | "PROVEN_NAMED" | "UNPROVEN") => (
            WorkspacePackageEvidence::InvalidReference(
                WorkspacePackageInvalidReference::IllegalStateTuple,
            ),
            false,
        ),
        _ => (
            WorkspacePackageEvidence::InvalidReference(
                WorkspacePackageInvalidReference::InvalidState,
            ),
            false,
        ),
    }
}

fn relative_manifest_path(dir_path: &str, filename: &str) -> Option<WorkspaceFilePath> {
    if dir_path.starts_with(ABSOLUTE_PATH_PREFIX) {
        return None;
    }
    let relative_dir = dir_path
        .strip_prefix(RELATIVE_ESCAPE_PREFIX)
        .unwrap_or(dir_path);
    if relative_dir.contains('\\') || filename.contains(['/', '\\']) {
        return None;
    }
    let path = if relative_dir.is_empty() {
        PathBuf::from(filename)
    } else {
        PathBuf::from(relative_dir).join(filename)
    };
    WorkspaceFilePath::from_relative_path(path)
}

fn is_kotlin_source(filename: &str) -> bool {
    Path::new(filename)
        .extension()
        .is_some_and(|extension| extension == "kt")
}

fn contain_path(
    root: &WorkspaceRoot,
    relative_path: &Path,
) -> (WorkspaceFileDrift, PathContainment) {
    let candidate = root.as_path().join(relative_path);
    match std::fs::symlink_metadata(&candidate) {
        Ok(_) => match std::fs::canonicalize(&candidate) {
            Ok(canonical) if canonical.starts_with(root.as_path()) => {
                (WorkspaceFileDrift::InSync, PathContainment::Contained)
            }
            Ok(_) => (WorkspaceFileDrift::Unknown, PathContainment::Outside),
            Err(_) => (WorkspaceFileDrift::Unknown, PathContainment::Unprovable),
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            contain_missing_path(root, candidate)
        }
        Err(_) => (WorkspaceFileDrift::Unknown, PathContainment::Unprovable),
    }
}

fn contain_missing_path(
    root: &WorkspaceRoot,
    candidate: PathBuf,
) -> (WorkspaceFileDrift, PathContainment) {
    let mut ancestor = candidate.as_path();
    loop {
        let Some(parent) = ancestor.parent() else {
            return (WorkspaceFileDrift::Unknown, PathContainment::Unprovable);
        };
        ancestor = parent;
        if !ancestor.starts_with(root.as_path()) {
            return (WorkspaceFileDrift::Unknown, PathContainment::Outside);
        }
        match std::fs::symlink_metadata(ancestor) {
            Ok(_) => {
                return match std::fs::canonicalize(ancestor) {
                    Ok(canonical) if canonical.starts_with(root.as_path()) => (
                        WorkspaceFileDrift::MissingOnDisk,
                        PathContainment::Contained,
                    ),
                    Ok(_) => (WorkspaceFileDrift::Unknown, PathContainment::Outside),
                    Err(_) => (WorkspaceFileDrift::Unknown, PathContainment::Unprovable),
                };
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => {
                return (WorkspaceFileDrift::Unknown, PathContainment::Unprovable);
            }
        }
    }
}

fn verify_required_structure(transaction: &Transaction<'_>) -> Result<(), ReadDatabaseError> {
    for (table, required_columns) in REQUIRED_TABLE_COLUMNS {
        let mut statement = transaction
            .prepare(&format!("PRAGMA table_info({table})"))
            .map_err(incompatible_sql)?;
        let columns = statement
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(incompatible_sql)?
            .collect::<rusqlite::Result<BTreeSet<_>>>()
            .map_err(incompatible_sql)?;
        if columns.is_empty() {
            return Err(ReadDatabaseError::Incompatible(format!(
                "required source-index table `{table}` is missing"
            )));
        }
        let missing: Vec<_> = required_columns
            .iter()
            .filter(|column| !columns.contains(**column))
            .copied()
            .collect();
        if !missing.is_empty() {
            return Err(ReadDatabaseError::Incompatible(format!(
                "required source-index table `{table}` is missing columns: {}",
                missing.join(", ")
            )));
        }
    }
    verify_primary_key(transaction, "file_metadata", &["prefix_id", "filename"])?;
    verify_primary_key(transaction, "path_prefixes", &["prefix_id"])?;
    verify_primary_key(transaction, "fq_names", &["fq_id"])?;
    verify_primary_key(transaction, "file_manifest", &["prefix_id", "filename"])?;
    verify_primary_key(transaction, "module_index_progress", &["module_name"])?;
    verify_primary_key(
        transaction,
        "file_gradle_projects",
        &["prefix_id", "filename", "build_root", "project_path"],
    )?;
    verify_primary_key(
        transaction,
        "file_gradle_source_sets",
        &[
            "prefix_id",
            "filename",
            "build_root",
            "project_path",
            "source_set_name",
        ],
    )?;
    verify_not_null(transaction, "schema_version", &["version", "generation"])?;
    verify_not_null(transaction, "path_prefixes", &["dir_path"])?;
    verify_not_null(transaction, "fq_names", &["fq_name"])?;
    verify_not_null(
        transaction,
        "file_manifest",
        &["prefix_id", "filename", "last_modified_millis"],
    )?;
    verify_not_null(
        transaction,
        "module_index_progress",
        &["phase2_status", "indexed_file_count", "total_file_count"],
    )?;
    verify_not_null(transaction, "pending_updates", &["applied"])?;
    verify_not_null(
        transaction,
        "file_metadata",
        &["prefix_id", "filename", "package_state"],
    )?;
    verify_not_null(
        transaction,
        "file_gradle_projects",
        &["prefix_id", "filename", "build_root", "project_path"],
    )?;
    verify_not_null(
        transaction,
        "file_gradle_source_sets",
        &[
            "prefix_id",
            "filename",
            "build_root",
            "project_path",
            "source_set_name",
        ],
    )?;
    verify_foreign_key(
        transaction,
        "file_metadata",
        "fq_names",
        &[("package_fq_id", "fq_id")],
        "NO ACTION",
    )?;
    verify_foreign_key(
        transaction,
        "file_gradle_projects",
        "file_metadata",
        &[("prefix_id", "prefix_id"), ("filename", "filename")],
        "CASCADE",
    )?;
    verify_foreign_key(
        transaction,
        "file_gradle_source_sets",
        "file_gradle_projects",
        &[
            ("prefix_id", "prefix_id"),
            ("filename", "filename"),
            ("build_root", "build_root"),
            ("project_path", "project_path"),
        ],
        "CASCADE",
    )?;
    verify_unique_key(transaction, "path_prefixes", &["dir_path"])?;
    verify_unique_key(transaction, "fq_names", &["fq_name"])?;
    verify_package_checks(transaction)?;
    verify_progress_checks(transaction)?;
    Ok(())
}

fn table_info(
    transaction: &Transaction<'_>,
    table: &str,
) -> Result<BTreeMap<String, TableColumn>, ReadDatabaseError> {
    let mut statement = transaction
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(incompatible_sql)?;
    statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(1)?,
                TableColumn {
                    not_null: row.get::<_, i64>(3)? != 0,
                    primary_key_position: row.get(5)?,
                },
            ))
        })
        .map_err(incompatible_sql)?
        .collect::<rusqlite::Result<BTreeMap<_, _>>>()
        .map_err(incompatible_sql)
}

fn verify_primary_key(
    transaction: &Transaction<'_>,
    table: &str,
    expected: &[&str],
) -> Result<(), ReadDatabaseError> {
    let columns = table_info(transaction, table)?;
    let mut actual: Vec<_> = columns
        .iter()
        .filter(|(_, column)| column.primary_key_position > 0)
        .map(|(name, column)| (column.primary_key_position, name.as_str()))
        .collect();
    actual.sort_unstable();
    let actual: Vec<_> = actual.into_iter().map(|(_, name)| name).collect();
    if actual != expected {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index table `{table}` has incompatible primary key"
        )));
    }
    Ok(())
}

fn verify_not_null(
    transaction: &Transaction<'_>,
    table: &str,
    required: &[&str],
) -> Result<(), ReadDatabaseError> {
    let columns = table_info(transaction, table)?;
    if let Some(column) = required
        .iter()
        .find(|column| !columns.get(**column).is_some_and(|shape| shape.not_null))
    {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index column `{table}.{column}` must be NOT NULL"
        )));
    }
    Ok(())
}

fn verify_unique_key(
    transaction: &Transaction<'_>,
    table: &str,
    expected: &[&str],
) -> Result<(), ReadDatabaseError> {
    let mut statement = transaction
        .prepare(&format!("PRAGMA index_list({table})"))
        .map_err(incompatible_sql)?;
    let indexes = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)? != 0,
                row.get::<_, i64>(4)? != 0,
            ))
        })
        .map_err(incompatible_sql)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(incompatible_sql)?;
    for (index, unique, partial) in indexes {
        if !unique || partial {
            continue;
        }
        let quoted_index = index.replace('\'', "''");
        let mut index_statement = transaction
            .prepare(&format!("PRAGMA index_info('{quoted_index}')"))
            .map_err(incompatible_sql)?;
        let columns = index_statement
            .query_map([], |row| row.get::<_, String>(2))
            .map_err(incompatible_sql)?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(incompatible_sql)?;
        if columns
            .iter()
            .map(String::as_str)
            .eq(expected.iter().copied())
        {
            return Ok(());
        }
    }
    Err(ReadDatabaseError::Incompatible(format!(
        "source-index table `{table}` lacks the required unique key ({})",
        expected.join(", ")
    )))
}

fn verify_foreign_key(
    transaction: &Transaction<'_>,
    table: &str,
    target_table: &str,
    expected_columns: &[(&str, &str)],
    expected_delete_action: &str,
) -> Result<(), ReadDatabaseError> {
    let mut statement = transaction
        .prepare(&format!("PRAGMA foreign_key_list({table})"))
        .map_err(incompatible_sql)?;
    let rows = statement
        .query_map([], |row| {
            Ok(ForeignKeyColumn {
                id: row.get(0)?,
                sequence: row.get(1)?,
                target_table: row.get(2)?,
                from_column: row.get(3)?,
                to_column: row.get(4)?,
                delete_action: row.get(6)?,
            })
        })
        .map_err(incompatible_sql)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(incompatible_sql)?;
    let mut grouped = BTreeMap::<i64, Vec<ForeignKeyColumn>>::new();
    for row in rows {
        grouped.entry(row.id).or_default().push(row);
    }
    let matches = grouped.values_mut().any(|columns| {
        columns.sort_by_key(|column| column.sequence);
        columns.first().is_some_and(|column| {
            column.target_table == target_table && column.delete_action == expected_delete_action
        }) && columns.len() == expected_columns.len()
            && columns
                .iter()
                .zip(expected_columns)
                .all(|(actual, expected)| {
                    actual.from_column == expected.0 && actual.to_column == expected.1
                })
    });
    if !matches {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index table `{table}` has an incompatible foreign key to `{target_table}`"
        )));
    }
    Ok(())
}

fn verify_package_checks(transaction: &Transaction<'_>) -> Result<(), ReadDatabaseError> {
    let normalized = normalized_table_sql(transaction, "file_metadata")?;
    let required_tokens = [
        "PROVEN_ROOT",
        "PROVEN_NAMED",
        "UNPROVEN",
        "NOT_SCANNED",
        "SEMANTIC_ANALYSIS_UNAVAILABLE",
        "SEMANTIC_ANALYSIS_FAILED",
        "LEGACY_TEXT_ONLY",
        "PACKAGE_STATE='PROVEN_ROOT'",
        "PACKAGE_STATE='PROVEN_NAMED'",
        "PACKAGE_STATE='UNPROVEN'",
        "PACKAGE_FQ_IDISNULL",
        "PACKAGE_FQ_IDISNOTNULL",
        "PACKAGE_UNPROVEN_REASONISNULL",
        "PACKAGE_UNPROVEN_REASONISNOTNULL",
    ];
    if required_tokens
        .iter()
        .any(|token| !normalized.contains(token))
    {
        return Err(ReadDatabaseError::Incompatible(
            "file_metadata package evidence CHECK contract is incomplete".to_string(),
        ));
    }
    Ok(())
}

fn verify_progress_checks(transaction: &Transaction<'_>) -> Result<(), ReadDatabaseError> {
    let normalized = normalized_table_sql(transaction, "module_index_progress")?;
    if !normalized.contains("PHASE2_STATUSIN('PENDING','INDEXING','COMPLETE','FAILED')") {
        return Err(ReadDatabaseError::Incompatible(
            "module_index_progress status CHECK contract is incomplete".to_string(),
        ));
    }
    Ok(())
}

fn normalized_table_sql(
    transaction: &Transaction<'_>,
    table: &str,
) -> Result<String, ReadDatabaseError> {
    let sql = transaction
        .query_row(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            [table],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(incompatible_sql)?
        .ok_or_else(|| ReadDatabaseError::Incompatible(format!("{table} DDL is unavailable")))?;
    Ok(sql
        .chars()
        .filter(|character| !character.is_whitespace())
        .flat_map(char::to_uppercase)
        .collect())
}

fn coverage_dimension(partial: bool) -> WorkspaceCoverageDimension {
    if partial {
        WorkspaceCoverageDimension::Partial
    } else {
        WorkspaceCoverageDimension::Complete
    }
}

fn increment_limitation(
    limitations: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    code: WorkspaceInventoryLimitationCode,
    count: usize,
) {
    if count > 0 {
        let updated = limitations
            .get(&code)
            .copied()
            .unwrap_or_default()
            .saturating_add(count);
        limitations.insert(code, updated);
    }
}

fn unavailable(detail: String) -> WorkspaceIndexRead {
    WorkspaceIndexRead::Unavailable(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        detail,
    ))
}

fn incompatible(detail: String) -> WorkspaceIndexRead {
    WorkspaceIndexRead::Incompatible(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
        detail,
    ))
}

fn incompatible_sql(error: rusqlite::Error) -> ReadDatabaseError {
    ReadDatabaseError::Incompatible(error.to_string())
}

#[derive(Debug)]
enum ReadDatabaseError {
    Unavailable(String),
    Incompatible(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PathContainment {
    Contained,
    Outside,
    Unprovable,
}

#[derive(Debug)]
struct ManifestRow {
    key: FileKey,
    filename: String,
    dir_path: Option<String>,
    metadata_present: bool,
    package_state: Option<String>,
    package_unproven_reason: Option<String>,
    package_fq_id: Option<i64>,
    package_fq_name: Option<String>,
    legacy_source_set: Option<String>,
}

#[derive(Debug)]
struct TableColumn {
    not_null: bool,
    primary_key_position: i64,
}

#[derive(Debug)]
struct ForeignKeyColumn {
    id: i64,
    sequence: i64,
    target_table: String,
    from_column: String,
    to_column: String,
    delete_action: String,
}

#[derive(Debug, Default)]
struct AssociationRows {
    projects: BTreeMap<FileKey, BTreeSet<BuildQualifiedGradleProjectIdentity>>,
    invalid_projects: BTreeMap<FileKey, usize>,
    source_sets: BTreeMap<FileKey, BTreeSet<BuildQualifiedGradleSourceSetIdentity>>,
    invalid_source_sets: BTreeMap<FileKey, usize>,
}

#[cfg(test)]
#[path = "index_regressions.rs"]
mod index_regressions;

impl AssociationRows {
    fn remove_orphan_rows(&mut self, manifest_keys: &BTreeSet<FileKey>) -> usize {
        let mut orphan_count = 0;
        for key in self.all_keys() {
            if !manifest_keys.contains(&key) {
                orphan_count += self.projects.remove(&key).map_or(0, |rows| rows.len());
                orphan_count += self.invalid_projects.remove(&key).unwrap_or_default();
                orphan_count += self.source_sets.remove(&key).map_or(0, |rows| rows.len());
                orphan_count += self.invalid_source_sets.remove(&key).unwrap_or_default();
            }
        }
        orphan_count
    }

    fn all_keys(&self) -> BTreeSet<FileKey> {
        self.projects
            .keys()
            .chain(self.invalid_projects.keys())
            .chain(self.source_sets.keys())
            .chain(self.invalid_source_sets.keys())
            .cloned()
            .collect()
    }
}
