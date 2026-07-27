use std::collections::{BTreeSet, VecDeque};
use std::path::Path;

use rusqlite::params;

use super::model::{
    BackendModuleCoverage, BackendWorkspaceCoverage, DirtyWorkspaceRead,
    WorkspaceCoverageDimension, WorkspaceFileDirtyState, WorkspaceFileDrift,
    WorkspaceFileIndexState, WorkspaceFileKind, WorkspaceFilePath, WorkspaceIndexRead,
    WorkspaceIndexReadFailure, WorkspaceIndexSnapshot, WorkspaceInventoryLimitationCode,
    WorkspaceMatchCoverage, WorkspacePackageEvidence, WorkspacePackageInvalidReference,
    WorkspacePackageUnprovenReason, WorkspaceRequestedKindDomain, WorkspaceRoot,
    WorkspaceSourceSetEvidence,
};
use super::read_workspace_index;
use super::workspace_files_test_support::WorkspaceIndexFixture;

include!("parts/tests/fixtures/core.rs");
include!("parts/tests/index/provenance.rs");
include!("parts/tests/index/ownership.rs");
include!("parts/tests/index/containment.rs");
include!("parts/tests/backend/fixtures.rs");
include!("parts/tests/backend/paging.rs");
include!("parts/tests/backend/containment.rs");
include!("parts/tests/dirty/fixtures.rs");
include!("parts/tests/dirty/composition.rs");
include!("parts/tests/barrier/fixtures.rs");
include!("parts/tests/barrier/backend.rs");
include!("parts/tests/barrier/index.rs");
include!("parts/tests/barrier/availability.rs");
include!("parts/tests/barrier/relevance.rs");
include!("parts/tests/barrier/filesystem.rs");
include!("parts/tests/barrier/dirty.rs");
