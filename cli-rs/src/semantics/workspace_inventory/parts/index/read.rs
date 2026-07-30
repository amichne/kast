pub(super) fn read_workspace_index(root: &WorkspaceRoot) -> WorkspaceIndexRead {
    read_workspace_index_with_path_validation(root, WorkspaceIndexPathValidation::LiveFilesystem)
}

pub(super) fn read_persisted_workspace_index(root: &WorkspaceRoot) -> WorkspaceIndexRead {
    read_workspace_index_with_path_validation(root, WorkspaceIndexPathValidation::PersistedLexical)
}

fn read_workspace_index_with_path_validation(
    root: &WorkspaceRoot,
    path_validation: WorkspaceIndexPathValidation,
) -> WorkspaceIndexRead {
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
    match read_database(root, &database_path, path_validation) {
        Ok(snapshot) => WorkspaceIndexRead::Snapshot(snapshot),
        Err(ReadDatabaseError::Unavailable(detail)) => unavailable(detail),
        Err(ReadDatabaseError::Incompatible(detail)) => incompatible(detail),
    }
}

fn read_database(
    root: &WorkspaceRoot,
    database_path: &Path,
    path_validation: WorkspaceIndexPathValidation,
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
    let snapshot = read_transaction(root, &transaction, path_validation)?;
    transaction
        .commit()
        .map_err(|error| ReadDatabaseError::Unavailable(error.to_string()))?;
    Ok(snapshot)
}

fn read_transaction(
    root: &WorkspaceRoot,
    transaction: &Transaction<'_>,
    path_validation: WorkspaceIndexPathValidation,
) -> Result<WorkspaceIndexSnapshot, ReadDatabaseError> {
    verify_required_structure(transaction)?;
    let generation = read_generation(transaction)?;
    let (module_progress, invalid_progress_count) = read_module_progress(transaction)?;
    let source_file_stage_progress = read_source_file_stage_progress(transaction)?;
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
        let (drift, containment) = match path_validation {
            WorkspaceIndexPathValidation::LiveFilesystem => {
                contain_path(root, relative_path.as_path())
            }
            WorkspaceIndexPathValidation::PersistedLexical => {
                (WorkspaceFileDrift::Unknown, PathContainment::Contained)
            }
        };
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
            incompatibilities.insert(SourceIndexIncompatibility::PackageMetadataReference);
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
            incompatibilities.insert(SourceIndexIncompatibility::MalformedGradleProjectIdentity);
            filter_partial = true;
        }
        let source_set_evidence = if project_rows_invalid > 0 || source_set_rows_invalid > 0 {
            incompatibilities.insert(SourceIndexIncompatibility::MalformedGradleSourceSetIdentity);
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
    let relationship_progress_incomplete = stamp.module_progress().is_empty()
        || stamp.module_progress().iter().any(|progress| {
            progress.status() != super::model::SourceIndexProgressStatus::Complete
                || progress.indexed_file_count() != progress.total_file_count()
        });
    let relationship_progress_terminal = !stamp.module_progress().is_empty()
        && stamp.module_progress().iter().all(|progress| {
            matches!(
                progress.status(),
                super::model::SourceIndexProgressStatus::Complete
                    | super::model::SourceIndexProgressStatus::Degraded
                    | super::model::SourceIndexProgressStatus::Failed
            ) && progress.indexed_file_count() == progress.total_file_count()
        });
    let source_progress_incomplete = match source_file_stage_progress {
        SourceFileStageProgress::Complete => !relationship_progress_terminal,
        SourceFileStageProgress::Empty => relationship_progress_incomplete,
        SourceFileStageProgress::Incomplete => true,
    };
    if source_progress_incomplete {
        candidate_partial = true;
    }
    if source_progress_incomplete || relationship_progress_incomplete {
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
