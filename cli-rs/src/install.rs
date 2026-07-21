#![allow(dead_code)]

use crate::SCHEMA_VERSION;
use crate::bundle::{
    BUNDLE_MANIFEST_FILE, BUNDLE_MANIFEST_KIND, BUNDLE_MANIFEST_SCHEMA_VERSION, BundleManifest,
    BundleVersion, HEADLESS_BACKEND_KIND, HEADLESS_BACKEND_NAME,
    UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID,
};
use crate::cli;
use crate::cli::{
    ActivateBundleArgs, InstallArgs, InstallCommand, InstallRepairArgs, ShellInstallArgs, ShellKind,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::manifest;
use crate::manifest::current_timestamp;
use crate::self_mgmt;
use flate2::read::GzDecoder;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::path::{Component, Path, PathBuf};
use std::process::{Command as ProcessCommand, Output};
use std::time::{SystemTime, UNIX_EPOCH};

const KAST_FORMULA_NAME: &str = "kast";
const SHELL_BLOCK_START: &str = "# >>> kast shell integration >>>";
const SHELL_BLOCK_END: &str = "# <<< kast shell integration <<<";

include!("install/types.rs");
include!("install/macos_homebrew_receipt.rs");
include!("install/legacy_idea_plugin_cleanup.rs");
include!("install/dispatch.rs");
include!("install/bundle_entrypoint.rs");
include!("install/bundle_source.rs");
include!("install/bundle_validation.rs");
include!("install/bundle_install.rs");
include!("install/bundle_helpers.rs");
include!("install/repair.rs");
include!("install/shell.rs");

fn command_error(code: &'static str, message: &str, args: &[String], output: &Output) -> CliError {
    let mut error = CliError::new(
        code,
        format!(
            "{message}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ),
    );
    error.details.insert("command".to_string(), args.join(" "));
    error.details.insert(
        "exitCode".to_string(),
        output.status.code().unwrap_or(-1).to_string(),
    );
    error
}

include!("install/tests.rs");
