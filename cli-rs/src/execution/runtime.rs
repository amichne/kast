use crate::SCHEMA_VERSION;
#[cfg(target_os = "macos")]
use crate::cli;
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
#[cfg(target_os = "macos")]
use std::collections::BTreeSet;
use std::fs;
#[cfg(target_os = "macos")]
use std::io::Write;
#[cfg(target_os = "macos")]
use std::num::NonZeroU32;
#[cfg(target_os = "macos")]
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
#[cfg(target_os = "macos")]
use uuid::Uuid;

include!("runtime/types.rs");
#[cfg(target_os = "macos")]
include!("runtime/compatibility.rs");
include!("runtime/backend/workspace_admission.rs");
include!("runtime/backend/workspace.rs");
include!("runtime/control/lifecycle.rs");
include!("runtime/wire/rpc.rs");
include!("runtime/control/inspect.rs");
include!("runtime/backend/backend_selection.rs");
include!("runtime/backend/descriptors.rs");
include!("runtime/control/idea_launch.rs");
include!("runtime/wire/serialization.rs");
include!("runtime/control/lease.rs");
include!("runtime/tests.rs");
