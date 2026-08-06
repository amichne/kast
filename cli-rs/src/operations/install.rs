use crate::SCHEMA_VERSION;
use crate::bundle::{
    AGENT_CLI_BUNDLE_PATH, BUNDLE_MANIFEST_FILE, BUNDLE_MANIFEST_KIND,
    BUNDLE_MANIFEST_SCHEMA_VERSION, BundleManifest, BundleVersion, DEFAULT_SETUP_PLATFORM_ID,
    INDEXER_KIND, INDEXER_NAME, is_macos_indexer,
};
use crate::cli::{KastHarness, SetupArgs};
use crate::config;
use crate::error::{CliError, Result};
use crate::manifest;
use flate2::read::GzDecoder;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};
use std::process::{Command as ProcessCommand, Output};
use std::time::{SystemTime, UNIX_EPOCH};

include!("install/types.rs");
include!("install/force_reset.rs");
include!("install/bundle_entrypoint.rs");
include!("install/bundle_source.rs");
include!("install/bundle_validation.rs");
include!("install/bundle_entrypoint/bundle_activation.rs");
include!("install/bundle_install.rs");
include!("install/bundle_helpers.rs");
include!("install/agent_resources.rs");

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
