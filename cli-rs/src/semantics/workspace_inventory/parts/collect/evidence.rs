fn file_index_and_drift(
    path: &WorkspaceFilePath,
    kind: WorkspaceFileKind,
    filesystem_state: &WorkspaceFilesystemPathState,
    backend_modules: &BTreeSet<super::model::BackendModuleName>,
    backend: &super::model::BackendWorkspaceInventory,
    indexed: Option<&WorkspaceInventoryFile>,
    index_snapshot: Option<&WorkspaceIndexSnapshot>,
) -> (WorkspaceFileDrift, Option<WorkspaceInventoryLimitationCode>) {
    if kind == WorkspaceFileKind::Script {
        return (WorkspaceFileDrift::NotApplicable, None);
    }
    if matches!(
        filesystem_state,
        WorkspaceFilesystemPathState::Missing { .. }
    ) {
        return (WorkspaceFileDrift::MissingOnDisk, None);
    }
    let index_exact = index_snapshot.is_some_and(|snapshot| {
        snapshot.coverage().candidate_inventory() == WorkspaceCoverageDimension::Complete
    });
    let backend_owners_complete = !backend_modules.is_empty()
        && backend.coverage() == BackendWorkspaceCoverage::Complete
        && backend_modules.iter().all(|owner| {
            backend
                .modules()
                .get(owner)
                .is_some_and(|module| module.coverage() == BackendModuleCoverage::Complete)
        });
    match (!backend_modules.is_empty(), indexed.is_some()) {
        (true, true) if backend_owners_complete && index_exact => {
            (WorkspaceFileDrift::InSync, None)
        }
        (true, false) if backend_owners_complete && index_exact => {
            (WorkspaceFileDrift::FilesystemOnly, None)
        }
        (false, true) if index_exact && every_containing_owner_complete(path, backend) => {
            (WorkspaceFileDrift::IndexOnly, None)
        }
        (false, true) => (
            WorkspaceFileDrift::Unknown,
            Some(WorkspaceInventoryLimitationCode::ProjectModelOwnershipUnknown),
        ),
        _ => (WorkspaceFileDrift::Unknown, None),
    }
}

fn every_containing_owner_complete(
    path: &WorkspaceFilePath,
    backend: &super::model::BackendWorkspaceInventory,
) -> bool {
    if backend.coverage() != BackendWorkspaceCoverage::Complete {
        return false;
    }
    let owners: Vec<_> = backend
        .modules()
        .values()
        .filter(|module| {
            module
                .source_roots()
                .iter()
                .chain(module.content_roots())
                .any(|root| path.as_path().starts_with(root.as_path()))
        })
        .collect();
    !owners.is_empty()
        && owners
            .iter()
            .all(|module| module.coverage() == BackendModuleCoverage::Complete)
}

fn backend_lane_stamp(
    backend: &super::model::BackendWorkspaceInventory,
) -> WorkspaceLaneStamp<BackendWorkspaceStamp> {
    match backend.stamp() {
        Some(stamp) => WorkspaceLaneStamp::Available(stamp),
        None => WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(format!(
            "BACKEND_{:?}:{:?}",
            backend.coverage(),
            backend.limitations()
        ))),
    }
}

fn index_lane_stamp(index: &WorkspaceIndexRead) -> WorkspaceLaneStamp<SourceIndexSnapshotStamp> {
    match index {
        WorkspaceIndexRead::Snapshot(snapshot) => {
            WorkspaceLaneStamp::Available(snapshot.stamp().clone())
        }
        WorkspaceIndexRead::Unavailable(failure) | WorkspaceIndexRead::Incompatible(failure) => {
            WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(format!(
                "{:?}:{}",
                failure.limitation(),
                failure.detail()
            )))
        }
    }
}

fn dirty_lane_stamp(dirty: &DirtyWorkspaceRead) -> WorkspaceLaneStamp<DirtyWorkspaceStamp> {
    match dirty {
        DirtyWorkspaceRead::Snapshot(snapshot) => {
            WorkspaceLaneStamp::Available(snapshot.stamp().clone())
        }
        DirtyWorkspaceRead::Unavailable(reason) => WorkspaceLaneStamp::Unavailable(reason.clone()),
    }
}

fn relevant_lane<Stamp>(
    purpose: WorkspaceLanePurpose,
    stamp: WorkspaceLaneStamp<Stamp>,
) -> WorkspaceLaneEvidence<Stamp> {
    WorkspaceLaneEvidence::Relevant { purpose, stamp }
}

fn index_lane_evidence(
    index: Option<&WorkspaceIndexRead>,
) -> WorkspaceLaneEvidence<SourceIndexSnapshotStamp> {
    index.map_or(WorkspaceLaneEvidence::Irrelevant, |read| {
        relevant_lane(
            WorkspaceLanePurpose::CandidateInventory,
            index_lane_stamp(read),
        )
    })
}

fn dirty_lane_evidence(
    dirty: Option<&DirtyWorkspaceRead>,
) -> WorkspaceLaneEvidence<DirtyWorkspaceStamp> {
    dirty.map_or(WorkspaceLaneEvidence::Irrelevant, |read| {
        relevant_lane(WorkspaceLanePurpose::FilterEvidence, dirty_lane_stamp(read))
    })
}
