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
    WorkspaceInventorySnapshot, WorkspaceInventorySnapshotInputs, WorkspaceKindMatchCoverage,
    WorkspaceLaneEvidence, WorkspaceLanePurpose, WorkspaceLaneStamp,
    WorkspaceLaneUnavailableReason, WorkspaceMatchCoverage, WorkspaceRequestedKindDomain,
    WorkspaceRoot,
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

include!("parts/collect/entrypoint.rs");
include!("parts/collect/attempt.rs");
include!("parts/collect/composition.rs");
include!("parts/collect/evidence.rs");
include!("parts/collect/filesystem.rs");
include!("parts/collect/digest.rs");
