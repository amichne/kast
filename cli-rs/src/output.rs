use crate::cli::{OutputFormat, ReadyTarget};
use crate::config::PathResolutionReport;
use crate::error::{CliError, Result};
use crate::install::{
    ActivateBundleResult, AgentGuidanceSetupPlan, AgentGuidanceSetupResult,
    InstallIdeaPluginResult, InstallResult, InstallShellResult, InstallSkillResult,
};
use crate::orchestration::{SetupRuntimeNextAction, SetupRuntimeResult, SetupRuntimeStage};
use crate::package::{PackageResult, UbuntuDebianBundlePackageResult};
use crate::runtime::{
    DaemonStopResult, RuntimeCandidateStatus, RuntimeState, WorkspaceEnsureResult,
    WorkspaceRestartResult, WorkspaceStatusResult,
};
use crate::self_mgmt::{DeveloperMachineDefaultsResult, SelfDoctorResult};
use glamour::{Renderer, Style as GlamourStyle};
use serde::Serialize;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::{self, Write as FmtWrite};
use std::io::{self, IsTerminal, Write as IoWrite};

const SOURCE_MODULE_DISPLAY_LIMIT: usize = 30;

macro_rules! mdln {
    ($document:expr) => {
        $document.blank()
    };
    ($document:expr, $($arg:tt)*) => {
        $document.line(format_args!($($arg)*))
    };
}

include!("output/core.rs");
include!("output/setup_runtime.rs");
include!("output/package_runtime.rs");
include!("output/ready.rs");
include!("output/tables.rs");
include!("output/install.rs");
include!("output/runtime_helpers.rs");
include!("output/tests.rs");
