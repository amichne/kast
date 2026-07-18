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
use std::num::NonZeroU32;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

include!("runtime/types.rs");
#[cfg(target_os = "macos")]
include!("runtime/compatibility.rs");
include!("runtime/workspace_admission.rs");
include!("runtime/workspace.rs");
include!("runtime/lifecycle.rs");
include!("runtime/rpc.rs");
include!("runtime/inspect.rs");
include!("runtime/backend_selection.rs");
include!("runtime/descriptors.rs");
include!("runtime/idea_launch.rs");
include!("runtime/serialization.rs");
include!("runtime/lease.rs");
include!("runtime/tests.rs");
