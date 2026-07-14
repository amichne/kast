use std::collections::{BTreeMap, BTreeSet};
use std::convert::Infallible;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use super::backend::{
    BackendWorkspaceRpc, collect_backend_inventory, revalidate_backend_inventory,
};
use super::barrier::collect_with_single_retry;
use super::dirty::read_dirty_workspace;
use super::model::{
    BackendModuleCoverage, BackendWorkspaceCoverage, BackendWorkspaceStamp, DirtyWorkspaceRead,
    DirtyWorkspaceStamp, SourceIndexSnapshotStamp, WorkspaceCoverageDimension,
    WorkspaceEvidenceSource, WorkspaceFileDirtyState, WorkspaceFileDrift, WorkspaceFileKind,
    WorkspaceFilePath, WorkspaceFilesystemPathState, WorkspaceFilesystemStamp, WorkspaceIndexRead,
    WorkspaceIndexSnapshot, WorkspaceInventoryFile, WorkspaceInventoryLimitationCode,
    WorkspaceInventorySnapshot, WorkspaceKindMatchCoverage, WorkspaceLaneEvidence,
    WorkspaceLanePurpose, WorkspaceLaneStamp, WorkspaceLaneUnavailableReason,
    WorkspaceMatchCoverage, WorkspaceRequestedKindDomain, WorkspaceRoot,
};

pub(crate) trait WorkspaceInventoryLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead;

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead;

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &BTreeSet<WorkspaceFilePath>,
    ) -> WorkspaceLaneStamp<WorkspaceFilesystemStamp>;
}

pub(crate) struct SystemWorkspaceLaneReader;

impl WorkspaceInventoryLaneReader for SystemWorkspaceLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::read_workspace_index(root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        read_dirty_workspace(root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &BTreeSet<WorkspaceFilePath>,
    ) -> WorkspaceLaneStamp<WorkspaceFilesystemStamp> {
        WorkspaceLaneStamp::Available(observe_filesystem(root, paths))
    }
}

pub(crate) struct WorkspaceInventoryInputs<'a> {
    pub(crate) root: WorkspaceRoot,
    pub(crate) kind_domain: WorkspaceRequestedKindDomain,
    pub(crate) dirty_evidence_relevant: bool,
    pub(crate) backend: &'a mut dyn BackendWorkspaceRpc,
    pub(crate) lanes: &'a mut dyn WorkspaceInventoryLaneReader,
}

pub(crate) type WorkspaceInventoryCollectionError = Infallible;

pub(crate) fn collect_workspace_inventory(
    mut inputs: WorkspaceInventoryInputs<'_>,
) -> Result<WorkspaceInventorySnapshot, WorkspaceInventoryCollectionError> {
    let (mut snapshot, stable) = collect_with_single_retry(|| {
        let collected = collect_attempt(&mut inputs);
        let stable = collected.before == collected.after;
        (collected.snapshot, stable)
    });
    if !stable {
        snapshot.mark_unstable();
    }
    Ok(snapshot)
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CompositionLaneStamps {
    backend: WorkspaceLaneEvidence<BackendWorkspaceStamp>,
    index: WorkspaceLaneEvidence<SourceIndexSnapshotStamp>,
    filesystem: WorkspaceLaneEvidence<WorkspaceFilesystemStamp>,
    dirty: WorkspaceLaneEvidence<DirtyWorkspaceStamp>,
}

struct CollectedAttempt {
    snapshot: WorkspaceInventorySnapshot,
    before: CompositionLaneStamps,
    after: CompositionLaneStamps,
}

fn collect_attempt(inputs: &mut WorkspaceInventoryInputs<'_>) -> CollectedAttempt {
    let backend = collect_backend_inventory(&inputs.root, inputs.kind_domain, inputs.backend);
    let index_before = inputs
        .kind_domain
        .includes_sources()
        .then(|| inputs.lanes.read_source_index(&inputs.root));
    let candidates = candidate_paths(inputs.kind_domain, &backend, index_before.as_ref());
    let filesystem_before = inputs.lanes.read_filesystem(&inputs.root, &candidates);
    let dirty_before = inputs
        .dirty_evidence_relevant
        .then(|| inputs.lanes.read_dirty_workspace(&inputs.root));

    let backend_before_stamp = backend_lane_stamp(&backend);
    let index_before_evidence = index_lane_evidence(index_before.as_ref());
    let dirty_before_evidence = dirty_lane_evidence(dirty_before.as_ref());
    let snapshot = compose_snapshot(
        inputs.kind_domain,
        &backend,
        index_before.as_ref(),
        &filesystem_before,
        dirty_before.as_ref(),
    );

    let index_after = inputs
        .kind_domain
        .includes_sources()
        .then(|| inputs.lanes.read_source_index(&inputs.root));
    let filesystem_after = inputs.lanes.read_filesystem(&inputs.root, &candidates);
    let dirty_after = inputs
        .dirty_evidence_relevant
        .then(|| inputs.lanes.read_dirty_workspace(&inputs.root));
    let backend_after_stamp =
        revalidate_backend_inventory(&inputs.root, inputs.kind_domain, &backend, inputs.backend);

    CollectedAttempt {
        snapshot,
        before: CompositionLaneStamps {
            backend: relevant_lane(
                WorkspaceLanePurpose::CandidateInventory,
                backend_before_stamp,
            ),
            index: index_before_evidence,
            filesystem: relevant_lane(WorkspaceLanePurpose::CandidateAndFilter, filesystem_before),
            dirty: dirty_before_evidence,
        },
        after: CompositionLaneStamps {
            backend: relevant_lane(
                WorkspaceLanePurpose::CandidateInventory,
                backend_after_stamp,
            ),
            index: index_lane_evidence(index_after.as_ref()),
            filesystem: relevant_lane(WorkspaceLanePurpose::CandidateAndFilter, filesystem_after),
            dirty: dirty_lane_evidence(dirty_after.as_ref()),
        },
    }
}

fn candidate_paths(
    kind_domain: WorkspaceRequestedKindDomain,
    backend: &super::model::BackendWorkspaceInventory,
    index: Option<&WorkspaceIndexRead>,
) -> BTreeSet<WorkspaceFilePath> {
    let mut paths: BTreeSet<_> = backend
        .files()
        .keys()
        .filter(|path| kind_domain_includes_path(kind_domain, path))
        .cloned()
        .collect();
    if let Some(WorkspaceIndexRead::Snapshot(index)) = index {
        paths.extend(index.files().iter().map(|file| file.path().clone()));
    }
    paths
}

fn kind_domain_includes_path(
    kind_domain: WorkspaceRequestedKindDomain,
    path: &WorkspaceFilePath,
) -> bool {
    match path
        .as_path()
        .extension()
        .and_then(|extension| extension.to_str())
    {
        Some("kt") => kind_domain.includes_sources(),
        Some("kts") => kind_domain.includes_scripts(),
        _ => false,
    }
}

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
    WorkspaceInventorySnapshot::new(
        files,
        backend.coverage(),
        coverage,
        WorkspaceKindMatchCoverage::new(source_coverage, script_coverage),
        limitations,
        true,
        digest,
    )
}

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

fn observe_filesystem(
    root: &WorkspaceRoot,
    paths: &BTreeSet<WorkspaceFilePath>,
) -> WorkspaceFilesystemStamp {
    let states = paths
        .iter()
        .map(|path| (path.clone(), observe_path(root.as_path(), path.as_path())))
        .collect();
    WorkspaceFilesystemStamp::new(states)
}

fn observe_path(root: &Path, relative: &Path) -> WorkspaceFilesystemPathState {
    let candidate = root.join(relative);
    if std::fs::symlink_metadata(&candidate).is_ok() {
        return std::fs::canonicalize(&candidate)
            .ok()
            .filter(|canonical| canonical.starts_with(root))
            .map(WorkspaceFilesystemPathState::Present)
            .unwrap_or(WorkspaceFilesystemPathState::Unprovable);
    }
    let mut ancestor = candidate.as_path();
    let mut suffix = PathBuf::new();
    while std::fs::symlink_metadata(ancestor).is_err() {
        let Some(name) = ancestor.file_name() else {
            return WorkspaceFilesystemPathState::Unprovable;
        };
        suffix = Path::new(name).join(suffix);
        let Some(parent) = ancestor.parent() else {
            return WorkspaceFilesystemPathState::Unprovable;
        };
        ancestor = parent;
    }
    std::fs::canonicalize(ancestor)
        .ok()
        .filter(|canonical| canonical.starts_with(root))
        .map(|canonical_ancestor| WorkspaceFilesystemPathState::Missing {
            canonical_ancestor,
            missing_suffix: suffix,
        })
        .unwrap_or(WorkspaceFilesystemPathState::Unprovable)
}

fn composition_digest(
    kind_domain: WorkspaceRequestedKindDomain,
    backend: WorkspaceLaneEvidence<BackendWorkspaceStamp>,
    index: WorkspaceLaneEvidence<SourceIndexSnapshotStamp>,
    filesystem: WorkspaceLaneEvidence<WorkspaceFilesystemStamp>,
    dirty: WorkspaceLaneEvidence<DirtyWorkspaceStamp>,
) -> String {
    let canonical = format!(
        "kind={kind_domain:?}|backend={backend:?}|index={index:?}|filesystem={filesystem:?}|dirty={dirty:?}"
    );
    hex::encode(Sha256::digest(canonical.as_bytes()))
}

fn merge_limitations(
    target: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    source: &BTreeMap<WorkspaceInventoryLimitationCode, usize>,
) {
    for (code, count) in source {
        target
            .entry(*code)
            .and_modify(|current| *current += count)
            .or_insert(*count);
    }
}

fn increment(
    limitations: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    code: WorkspaceInventoryLimitationCode,
) {
    limitations
        .entry(code)
        .and_modify(|count| *count += 1)
        .or_insert(1);
}
