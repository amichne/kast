use crate::SCHEMA_VERSION;
use crate::bundle::{
    BUNDLE_MANIFEST_FILE, BUNDLE_MANIFEST_KIND, BUNDLE_MANIFEST_SCHEMA_VERSION, BundleManifest,
    BundleVersion, HEADLESS_BACKEND_KIND, HEADLESS_BACKEND_NAME,
    UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID,
};
use crate::cli::SetupArgs;
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
include!("install/bundle_entrypoint.rs");
include!("install/bundle_source.rs");
include!("install/bundle_validation.rs");
include!("install/bundle_install.rs");
include!("install/bundle_helpers.rs");
include!("install/idea_plugin.rs");

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn idea_launch_migration_extends_an_existing_partial_table() {
        let migrated = migrate_missing_idea_launch_choice(
            "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\ncommand = \"/Applications/IntelliJ IDEA.app\"\nwaitTimeoutMillis = 45000\n".to_string(),
        )
        .expect("migration");
        let value: toml::Value = toml::from_str(&migrated).expect("valid TOML");
        let launch = value
            .get("runtime")
            .and_then(toml::Value::as_table)
            .and_then(|runtime| runtime.get("ideaLaunch"))
            .and_then(toml::Value::as_table)
            .expect("idea launch table");

        assert_eq!(
            launch.get("enabled").and_then(toml::Value::as_bool),
            Some(true)
        );
        assert_eq!(
            launch.get("command").and_then(toml::Value::as_str),
            Some("/Applications/IntelliJ IDEA.app"),
        );
        assert_eq!(
            launch
                .get("waitTimeoutMillis")
                .and_then(toml::Value::as_integer),
            Some(45_000),
        );
    }
}
