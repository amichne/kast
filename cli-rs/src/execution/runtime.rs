use crate::SCHEMA_VERSION;
use crate::cli::{
    AgentLeaseAccessArgs, AgentLeaseAcquireArgs, AgentWorkspaceLeaseId, BackendName,
    DaemonStartArgs, RuntimeArgs,
};
use crate::config::{self, KastConfig, PathResolutionReport};
use crate::daemon;
use crate::error::{CliError, Result};
use crate::rpc;
use crate::self_mgmt;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

include!("runtime/types.rs");
#[path = "runtime/backend/indexer_authority.rs"]
mod indexer_authority;
#[cfg(target_os = "macos")]
include!("runtime/backend/sidecar_host.rs");
include!("runtime/backend/workspace_admission.rs");
include!("runtime/backend/workspace.rs");
include!("runtime/control/lifecycle.rs");
include!("runtime/wire/rpc.rs");
include!("runtime/control/inspect.rs");
include!("runtime/backend/descriptors.rs");
include!("runtime/wire/serialization.rs");
include!("runtime/control/lease.rs");
include!("runtime/tests.rs");
