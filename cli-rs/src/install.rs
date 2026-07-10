#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::bundle::{
    BUNDLE_MANIFEST_FILE, BUNDLE_MANIFEST_KIND, BUNDLE_MANIFEST_SCHEMA_VERSION, BundleManifest,
    BundleVersion, HEADLESS_BACKEND_KIND, HEADLESS_BACKEND_NAME,
    UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID,
};
use crate::cli;
use crate::cli::{
    ActivateBundleArgs, IdeaPluginInstallArgs, InstallArgs, InstallCommand, InstallRepairArgs,
    ResourceInstallArgs, ShellInstallArgs, ShellKind,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::manifest::{
    self, ManagedRepoResource, ManagedResourceChecksumRegion, ManagedResourceKind,
    ManagedResourceOutputChecksum,
};
use crate::self_mgmt;
use flate2::read::GzDecoder;
use indicatif::{ProgressBar, ProgressDrawTarget, ProgressStyle};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::io::{self, IsTerminal};
use std::path::{Component, Path, PathBuf};
use std::process::{Command as ProcessCommand, Output, Stdio};
use std::thread;
use std::time::Duration;
use std::time::{SystemTime, UNIX_EPOCH};

const KAST_FORMULA_NAME: &str = "kast";
const KAST_PLUGIN_CASK_NAME: &str = "kast-plugin";
const DEFAULT_KAST_TAP: &str = "amichne/kast";
const RESOURCE_MARKER: &str = ".kast-version";
const SHELL_BLOCK_START: &str = "# >>> kast shell integration >>>";
const SHELL_BLOCK_END: &str = "# <<< kast shell integration <<<";
const DEFAULT_AGENT_GUIDANCE_FILE: &str = "AGENTS.local.md";
const KAST_MANAGED_FENCE_START: &str = "<kast>";
const KAST_MANAGED_FENCE_END: &str = "</kast>";
const ATTRIBUTE_KAST_MANAGED_FENCE_START: &str =
    r#"<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">"#;
const LEGACY_KAST_MANAGED_FENCE_START: &str = "<!-- BEGIN KAST MANAGED -->";
const LEGACY_KAST_MANAGED_FENCE_END: &str = "<!-- END KAST MANAGED -->";

include!("install/reporting.rs");
include!("install/types.rs");
include!("install/macos_homebrew_receipt.rs");
include!("install/dispatch.rs");
include!("install/bundle_entrypoint.rs");
include!("install/agent_guidance.rs");
include!("install/bundle_source.rs");
include!("install/bundle_validation.rs");
include!("install/bundle_install.rs");
include!("install/bundle_helpers.rs");
include!("install/repair.rs");
include!("install/resource_installs.rs");
include!("install/idea_plugin_entrypoint.rs");
include!("install/shell.rs");
include!("install/jetbrains_profiles.rs");
include!("install/embedded_resources.rs");
include!("install/homebrew_idea_plugin.rs");
include!("install/resource_targets.rs");
include!("install/tests.rs");
