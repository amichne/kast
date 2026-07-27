fn compose_snapshot(
    kind_domain: WorkspaceRequestedKindDomain,
    backend: &super::model::BackendWorkspaceInventory,
    index_read: Option<&WorkspaceIndexRead>,
    filesystem_read: &WorkspaceLaneStamp<WorkspaceFilesystemStamp>,
    dirty_read: Option<&DirtyWorkspaceRead>,
) -> WorkspaceInventorySnapshot {
    let mut limitations = backend.limitations().clone();
    let index_snapshot = match index_read {
        Some(WorkspaceIndexRead::Snapshot(snapshot)) => {
            merge_limitations(&mut limitations, snapshot.limitations());
            Some(snapshot)
        }
        Some(WorkspaceIndexRead::Unavailable(failure))
        | Some(WorkspaceIndexRead::Incompatible(failure)) => {
            increment(&mut limitations, failure.limitation());
            None
        }
        None => None,
    };
    let filesystem = match filesystem_read {
        WorkspaceLaneStamp::Available(stamp) => Some(stamp),
        WorkspaceLaneStamp::Unavailable(_) => None,
    };
    let dirty = match dirty_read {
        Some(DirtyWorkspaceRead::Snapshot(snapshot)) => Some(snapshot),
        Some(DirtyWorkspaceRead::Unavailable(_)) => {
            increment(
                &mut limitations,
                WorkspaceInventoryLimitationCode::GitUnavailable,
            );
            None
        }
        None => None,
    };
    let index_by_path: BTreeMap<_, _> = index_snapshot
        .map(|snapshot| {
            snapshot
                .files()
                .iter()
                .map(|file| (file.path().clone(), file))
                .collect()
        })
        .unwrap_or_default();
    let candidates = candidate_paths(kind_domain, backend, index_read);
    let mut files = Vec::with_capacity(candidates.len());
    for path in candidates {
        let filesystem_state = filesystem.and_then(|stamp| stamp.state_for(&path));
        if filesystem_state == Some(&WorkspaceFilesystemPathState::Unprovable) {
            increment(
                &mut limitations,
                WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
            );
            continue;
        }
        let backend_modules = backend.files().get(&path).cloned().unwrap_or_default();
        if filesystem_state.is_none() && backend_modules.is_empty() {
            increment(
                &mut limitations,
                WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
            );
            continue;
        }
        let indexed = index_by_path.get(&path).copied();
        let kind = if path.as_path().extension().and_then(|value| value.to_str()) == Some("kts") {
            WorkspaceFileKind::Script
        } else {
            WorkspaceFileKind::Source
        };
        let (drift, drift_limitation) = filesystem_state.map_or_else(
            || {
                if kind == WorkspaceFileKind::Script {
                    (WorkspaceFileDrift::NotApplicable, None)
                } else {
                    (WorkspaceFileDrift::Unknown, None)
                }
            },
            |filesystem_state| {
                file_index_and_drift(
                    &path,
                    kind,
                    filesystem_state,
                    &backend_modules,
                    backend,
                    indexed,
                    index_snapshot,
                )
            },
        );
        if let Some(limitation) = drift_limitation {
            increment(&mut limitations, limitation);
        }
        let dirty_state = dirty
            .map(|snapshot| snapshot.state_for(&path))
            .unwrap_or_else(|| {
                if dirty_read.is_some() {
                    WorkspaceFileDirtyState::Unknown
                } else {
                    WorkspaceFileDirtyState::NotApplicable
                }
            });
        let mut evidence = indexed
            .map(|file| file.evidence().clone())
            .unwrap_or_default();
        if !backend_modules.is_empty() {
            evidence.insert(WorkspaceEvidenceSource::GradleProjectModel);
        }
        files.push(WorkspaceInventoryFile::composed(
            path,
            backend_modules,
            indexed,
            kind,
            drift,
            dirty_state,
            evidence,
        ));
    }

    let source_coverage = kind_domain.includes_sources().then(|| {
        if backend.coverage() == BackendWorkspaceCoverage::Complete
            && index_snapshot.is_some_and(|snapshot| {
                snapshot.coverage().candidate_inventory() == WorkspaceCoverageDimension::Complete
            })
            && filesystem.is_some()
        {
            WorkspaceCoverageDimension::Complete
        } else {
            WorkspaceCoverageDimension::Partial
        }
    });
    let script_coverage = kind_domain.includes_scripts().then(|| {
        if backend.coverage() == BackendWorkspaceCoverage::Complete && filesystem.is_some() {
            WorkspaceCoverageDimension::Complete
        } else {
            WorkspaceCoverageDimension::Partial
        }
    });
    let candidate_coverage = if [source_coverage, script_coverage]
        .into_iter()
        .flatten()
        .all(|coverage| coverage == WorkspaceCoverageDimension::Complete)
    {
        WorkspaceCoverageDimension::Complete
    } else {
        WorkspaceCoverageDimension::Partial
    };
    let filter_coverage = if candidate_coverage == WorkspaceCoverageDimension::Complete
        && dirty_read
            .is_none_or(|read| read.coverage() == super::model::DirtyWorkspaceCoverage::Complete)
    {
        WorkspaceCoverageDimension::Complete
    } else {
        WorkspaceCoverageDimension::Partial
    };
    let coverage = WorkspaceMatchCoverage::from_dimensions(candidate_coverage, filter_coverage);
    let digest = composition_digest(
        kind_domain,
        relevant_lane(
            WorkspaceLanePurpose::CandidateInventory,
            backend_lane_stamp(backend),
        ),
        index_lane_evidence(index_read),
        relevant_lane(
            WorkspaceLanePurpose::CandidateAndFilter,
            filesystem_read.clone(),
        ),
        dirty_lane_evidence(dirty_read),
    );
    WorkspaceInventorySnapshot::new(WorkspaceInventorySnapshotInputs {
        files,
        backend_coverage: backend.coverage(),
        backend_modules: backend.modules().clone(),
        coverage,
        kind_coverage: WorkspaceKindMatchCoverage::new(source_coverage, script_coverage),
        limitations,
        continuation_allowed: true,
        composition_digest: digest,
    })
}
