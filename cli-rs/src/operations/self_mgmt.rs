use crate::SCHEMA_VERSION;
use crate::cli;
use crate::cli::ReadyTarget;
use crate::config::{self, PathResolutionReport};
use crate::error::Result;
use crate::manifest;
#[cfg(target_os = "macos")]
use crate::runtime;
#[cfg(target_os = "macos")]
use serde::Deserialize;
use serde::Serialize;
#[cfg(target_os = "macos")]
use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

#[path = "self_mgmt/agent_readiness.rs"]
mod agent_readiness;

use agent_readiness::agent_environment_diagnostic;
pub use agent_readiness::{AgentResourceState, DoctorAgentEnvironmentDiagnostic};

pub use crate::manifest::KastInstallManifest as InstallState;

#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE: &str = ".kast/setup/workspace.json";
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION: u32 = 3;
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_PREPARED_BY: &str = "kast-intellij-plugin";
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_BACKEND: &str = "idea";
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorConfigurationDiagnostic {
    pub config_home: String,
    pub config_path: String,
    pub exists: bool,
    pub valid: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorCanonicalDirectoryDiagnostic {
    pub root: String,
    pub bin_dir: String,
    pub lib_dir: String,
    pub cache_dir: String,
    pub logs_dir: String,
    pub runtime_dir: String,
    pub descriptor_dir: String,
    pub socket_dir: String,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorBinaryDiagnostic {
    pub running_binary: String,
    pub configured_binary: String,
    pub configured_exists: bool,
    pub configured_matches_running: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum InstallAuthority {
    ActiveRelease,
    Missing,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelfDoctorResult {
    pub target: ReadyTarget,
    pub installed: bool,
    pub install_authority: InstallAuthority,
    pub config_path: String,
    pub manifest_path: String,
    pub configuration: DoctorConfigurationDiagnostic,
    pub canonical_directory: DoctorCanonicalDirectoryDiagnostic,
    pub binary: DoctorBinaryDiagnostic,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent_environment: Option<DoctorAgentEnvironmentDiagnostic>,
    pub path_resolution: PathResolutionReport,
    pub minimum_backend_version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallState>,
    pub ok: bool,
    pub issues: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

pub fn doctor(target: ReadyTarget, workspace_root: Option<&Path>) -> Result<SelfDoctorResult> {
    let config_path = config::global_config_path();
    let manifest_path = manifest::default_install_manifest_path();
    let mut issues = vec![];
    let mut warnings = vec![];
    let global_config = match config::KastConfig::load_global() {
        Ok(global_config) => global_config,
        Err(error) => {
            issues.push(format!(
                "Config is invalid at {}: {}",
                config_path.display(),
                error.message
            ));
            config::KastConfig::defaults()
        }
    };
    let configuration = configuration_diagnostic(&config_path, issues.first().cloned());
    let install = match read_global_install_state() {
        Ok(install) => install,
        Err(error) => {
            issues.push(format!(
                "Install manifest could not be read from {}: {}",
                manifest_path.display(),
                error.message
            ));
            None
        }
    };
    let install_root = global_config.paths.install_root.clone();
    let canonical_directory = canonical_directory_diagnostic(&global_config.paths);
    let binary = binary_diagnostic(&global_config.cli, install.as_ref());
    let path_resolution =
        config::path_resolution_report(&global_config, None, config::PathResolutionMode::Cli)?;
    if !binary.configured_exists {
        warnings.push(format!(
            "Configured kast binary is missing: {}",
            binary.configured_binary
        ));
    } else if !binary.configured_matches_running {
        warnings.push(format!(
            "Configured kast binary {} does not match the running binary {}",
            binary.configured_binary, binary.running_binary
        ));
    }
    let minimum_backend_version = minimum_backend_version();
    if let Some(install) = &install {
        let user_command = manifest::home_dir().join(".local/bin/kast");
        if install
            .owned_paths
            .iter()
            .any(|path| Path::new(path) == user_command)
            && !same_binary_path(&user_command, Path::new(&install.entrypoints.active_binary))
        {
            issues.push(format!(
                "Managed user command {} does not resolve to active binary {}",
                user_command.display(),
                install.entrypoints.active_binary
            ));
        }
        for path in &install.managed_paths {
            let managed_path = managed_path(&install_root.join("current"), path);
            if !managed_path.exists() {
                warnings.push(format!(
                    "Managed path is missing: {}",
                    managed_path.display()
                ));
            }
        }
        for backend in &install.backends {
            let backend_label = if backend.name.trim().is_empty() {
                "backend"
            } else {
                backend.name.trim()
            };
            if !Path::new(&backend.runtime_libs_dir)
                .join("classpath.txt")
                .is_file()
            {
                issues.push(format!(
                    "{} backend runtime-libs classpath is missing at {}",
                    backend_label, backend.runtime_libs_dir
                ));
            }
            match version_meets_minimum(&backend.version, minimum_backend_version) {
                Some(true) => {}
                Some(false) => issues.push(format!(
                    "{} backend {} is older than required minimum {}",
                    backend_label, backend.version, minimum_backend_version
                )),
                None => warnings.push(format!(
                    "{} backend version {} cannot be compared to required minimum {}",
                    backend_label, backend.version, minimum_backend_version
                )),
            }
        }
    } else {
        issues.push(format!(
            "Install manifest is missing at {}",
            manifest_path.display()
        ));
    }
    apply_ready_target_checks(
        target,
        workspace_root,
        install.as_ref(),
        &binary,
        &mut issues,
    );
    let install_authority = if install.is_some() {
        InstallAuthority::ActiveRelease
    } else {
        InstallAuthority::Missing
    };
    let agent_environment = if matches!(target, ReadyTarget::Agent | ReadyTarget::Kotlin) {
        Some(agent_environment_diagnostic(
            workspace_root,
            install_authority,
            install.as_ref(),
            &binary,
            &mut issues,
        )?)
    } else {
        None
    };
    Ok(SelfDoctorResult {
        target,
        installed: install.is_some(),
        install_authority,
        config_path: config_path.display().to_string(),
        manifest_path: manifest_path.display().to_string(),
        configuration,
        canonical_directory,
        binary,
        agent_environment,
        path_resolution,
        minimum_backend_version: minimum_backend_version.to_string(),
        install,
        ok: issues.is_empty(),
        issues,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn apply_ready_target_checks(
    target: ReadyTarget,
    workspace_root: Option<&Path>,
    install: Option<&InstallState>,
    binary: &DoctorBinaryDiagnostic,
    issues: &mut Vec<String>,
) {
    apply_macos_plugin_workspace_check(target, workspace_root, issues);
    match target {
        ReadyTarget::Agent | ReadyTarget::Release => {}
        ReadyTarget::Machine => {
            if !binary.configured_exists {
                issues.push(format!(
                    "Machine readiness requires the configured kast binary to exist at {}",
                    binary.configured_binary
                ));
            } else if !binary.configured_matches_running {
                issues.push(format!(
                    "Machine readiness requires the configured kast binary {} to resolve to the running binary {}",
                    binary.configured_binary, binary.running_binary
                ));
            }
        }
        ReadyTarget::Kotlin => {
            if install.is_none_or(|install| {
                install.backends.is_empty()
                    && !install
                        .components
                        .iter()
                        .any(|component| component == "idea-plugin")
            }) {
                issues.push(
                    "Kotlin readiness requires an installed semantic backend in the manifest"
                        .to_string(),
                );
            }
        }
    }
}

include!("parts/self_mgmt/macos_workspace.rs");
include!("parts/self_mgmt/diagnostics.rs");
