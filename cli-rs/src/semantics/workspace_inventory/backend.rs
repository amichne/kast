use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use serde::Deserialize;
use serde_json::{Value, json};
use thiserror::Error;

use super::model::{
    BackendModuleCoverage, BackendModuleInventory, BackendModuleName, BackendWorkspaceCoverage,
    BackendWorkspaceInventory, BackendWorkspacePageToken, BackendWorkspaceSnapshotToken,
    WorkspaceContainedRoot, WorkspaceFilePath, WorkspaceInventoryLimitationCode,
    WorkspaceLaneStamp, WorkspaceLaneUnavailableReason, WorkspaceRequestedKindDomain,
    WorkspaceRoot,
};

const PAGE_SIZE: usize = 200;

include!("parts/backend/protocol.rs");
include!("parts/backend/collection.rs");
include!("parts/backend/paging.rs");
include!("parts/backend/validation.rs");
include!("parts/backend/limitations.rs");
include!("parts/backend/containment.rs");
include!("parts/backend/inventory.rs");
include!("parts/backend/tests.rs");
