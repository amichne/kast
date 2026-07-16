use crate::SCHEMA_VERSION;
use crate::cli;
use crate::cli::{InstallRepairArgs, ReadyTarget};
use crate::config::{self, PathResolutionReport};
use crate::error::Result;
use crate::install::{self, InstallRepairResult};
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

pub use crate::manifest::{
    KastInstallManifest as InstallState, ManagedRepo, ManagedRepoResource,
    ManagedRepoResourceHistory, ManagedResourceKind,
};

const MANAGED_RESOURCE_HISTORY_LIMIT: usize = 5;
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE: &str = ".kast/setup/workspace.json";
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION: u32 = 3;
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_PREPARED_BY: &str = "kast-intellij-plugin";
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_WORKSPACE_BACKEND: &str = "idea";
#[cfg(target_os = "macos")]
const MACOS_PLUGIN_REQUIRED_SKILL_RELATIVE: &str = ".agents/skills/kast/SKILL.md";

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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeveloperMachineDefaultsResult {
    pub config_path: String,
    pub default_backend: config::RuntimeDefaultBackend,
    pub idea_launch_enabled: bool,
    pub idea_launch_command: String,
    pub applied: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum InstallAuthority {
    MacosHomebrew,
    ManagedLocal,
    Missing,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyShadowDiagnostic {
    pub path: String,
    pub managed: bool,
    pub writable: bool,
    pub homebrew_is_next: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cleanup_command: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelfDoctorResult {
    pub target: ReadyTarget,
    pub installed: bool,
    pub install_authority: InstallAuthority,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub homebrew_install: Option<install::MacosHomebrewInstallReceipt>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub legacy_shadow: Option<LegacyShadowDiagnostic>,
    pub config_path: String,
    pub manifest_path: String,
    pub configuration: DoctorConfigurationDiagnostic,
    pub canonical_directory: DoctorCanonicalDirectoryDiagnostic,
    pub binary: DoctorBinaryDiagnostic,
    pub path_resolution: PathResolutionReport,
    pub minimum_backend_version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallState>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub repair: Option<InstallRepairResult>,
    pub ok: bool,
    pub issues: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

pub fn doctor(
    repair: bool,
    target: ReadyTarget,
    workspace_root: Option<&Path>,
) -> Result<SelfDoctorResult> {
    let config_path = config::global_config_path();
    let manifest_path = manifest::default_install_manifest_path();
    let mut issues = vec![];
    let mut warnings = vec![];
    let repair_result = if repair {
        let repair_args = InstallRepairArgs {
            apply: true,
            jetbrains_config_root: None,
        };
        if !install::macos_homebrew_repair_authority_is_provable()? {
            manifest::install_current_executable()?;
        }
        Some(install::repair_install_state(repair_args)?)
    } else {
        None
    };
    #[cfg(target_os = "macos")]
    let homebrew_install = install::read_macos_homebrew_receipt()?;
    #[cfg(not(target_os = "macos"))]
    let homebrew_install = None;
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
    let legacy_shadow = homebrew_install
        .as_ref()
        .and_then(|receipt| legacy_shadow_diagnostic(receipt, install.as_ref()));
    if let Some(shadow) = &legacy_shadow {
        if let Some(command) = &shadow.cleanup_command {
            warnings.push(format!(
                "Legacy kast at {} shadows Homebrew on PATH; clean it up with: {command}",
                shadow.path
            ));
        } else {
            warnings.push(format!(
                "kast at {} shadows the authoritative Homebrew executable; no automatic cleanup is safe",
                shadow.path
            ));
        }
    }
    if homebrew_install.is_some() && install.is_some() {
        warnings.push(format!(
            "Inactive legacy install manifest remains at {}; the macOS Homebrew receipt is authoritative",
            manifest_path.display()
        ));
    } else if let Some(install) = &install {
        for path in &install.managed_paths {
            let managed_path = managed_path(&install_root, path);
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
        for repo in install
            .repos
            .iter()
            .filter(|repo| should_verify_repo_resources_for_target(target, workspace_root, repo))
        {
            if !repo.copilot_package_version.trim().is_empty()
                && !repo
                    .resources
                    .iter()
                    .any(|resource| resource.kind == ManagedResourceKind::CopilotPackage)
            {
                issues.push(format!(
                    "Managed repo {} uses retired copilotPackageVersion state, which is incompatible with manifest-backed resource verification; upgrade/reinstall Kast if needed, then run `kast repair --apply` to refresh from the active binary bundles",
                    repo.path
                ));
            }
            for resource in &repo.resources {
                let verification = manifest::verify_managed_resource_outputs(resource)?;
                if !verification.ok {
                    issues.extend(verification.issues);
                }
                if resource.primitive_version != cli::version() {
                    warnings.push(format!(
                        "{} resource at {} was installed by version {}, current binary is {}",
                        resource.kind,
                        resource.target_path,
                        resource.primitive_version,
                        cli::version()
                    ));
                }
            }
        }
    } else if homebrew_install.is_none() {
        issues.push(format!(
            "Install manifest is missing at {}",
            manifest_path.display()
        ));
    }
    apply_ready_target_checks(
        target,
        workspace_root,
        install.as_ref(),
        homebrew_install.is_some(),
        &binary,
        &mut issues,
    );
    Ok(SelfDoctorResult {
        target,
        installed: homebrew_install.is_some() || install.is_some(),
        install_authority: if homebrew_install.is_some() {
            InstallAuthority::MacosHomebrew
        } else if install.is_some() {
            InstallAuthority::ManagedLocal
        } else {
            InstallAuthority::Missing
        },
        homebrew_install,
        legacy_shadow,
        config_path: config_path.display().to_string(),
        manifest_path: manifest_path.display().to_string(),
        configuration,
        canonical_directory,
        binary,
        path_resolution,
        minimum_backend_version: minimum_backend_version.to_string(),
        install,
        repair: repair_result,
        ok: issues.is_empty(),
        issues,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn legacy_shadow_diagnostic(
    receipt: &install::MacosHomebrewInstallReceipt,
    legacy_install: Option<&InstallState>,
) -> Option<LegacyShadowDiagnostic> {
    let candidates = env::var_os("PATH")
        .map(|path| {
            env::split_paths(&path)
                .map(|directory| directory.join("kast"))
                .filter(|candidate| candidate.is_file())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let first = candidates.first()?;
    if same_binary_path(first, &receipt.cli.binary) {
        return None;
    }
    let managed = legacy_install.is_some_and(|install| {
        let shim = Path::new(&install.entrypoints.shim);
        config::normalize(shim.to_path_buf()) == config::normalize(first.to_path_buf())
            && manifest::is_managed_shim_for(shim, Path::new(&install.entrypoints.active_binary))
    });
    let writable = managed && install::path_parent_is_writable(first);
    let homebrew_is_next = candidates
        .get(1)
        .is_some_and(|candidate| same_binary_path(candidate, &receipt.cli.binary));
    let cleanup_command = (managed && writable && homebrew_is_next).then(|| {
        format!(
            "{} repair --for machine --apply && hash -r",
            shell_quote_for_remediation(&receipt.cli.binary.display().to_string())
        )
    });
    Some(LegacyShadowDiagnostic {
        path: first.display().to_string(),
        managed,
        writable,
        homebrew_is_next,
        cleanup_command,
    })
}

fn shell_quote_for_remediation(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

fn apply_ready_target_checks(
    target: ReadyTarget,
    workspace_root: Option<&Path>,
    install: Option<&InstallState>,
    homebrew_install: bool,
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
            if !homebrew_install && install.is_none_or(|install| install.backends.is_empty()) {
                issues.push(
                    "Kotlin readiness requires an installed semantic backend in the manifest"
                        .to_string(),
                );
            }
        }
    }
}

#[cfg(target_os = "macos")]
fn should_verify_repo_resources_for_target(
    target: ReadyTarget,
    _workspace_root: Option<&Path>,
    _repo: &ManagedRepo,
) -> bool {
    !matches!(target, ReadyTarget::Agent | ReadyTarget::Kotlin)
}

#[cfg(not(target_os = "macos"))]
fn should_verify_repo_resources_for_target(
    target: ReadyTarget,
    workspace_root: Option<&Path>,
    repo: &ManagedRepo,
) -> bool {
    if matches!(target, ReadyTarget::Agent | ReadyTarget::Kotlin) {
        return workspace_root.is_some_and(|workspace_root| {
            config::normalize(PathBuf::from(&repo.path))
                == config::normalize(workspace_root.to_path_buf())
        });
    }
    true
}

#[cfg(target_os = "macos")]
fn apply_macos_plugin_workspace_check(
    target: ReadyTarget,
    workspace_root: Option<&Path>,
    issues: &mut Vec<String>,
) {
    if !matches!(target, ReadyTarget::Agent | ReadyTarget::Kotlin) {
        return;
    }
    match workspace_root {
        Some(workspace_root) => {
            if let Err(error) = validate_macos_plugin_workspace(workspace_root) {
                issues.push(error.message);
            }
        }
        None => issues.push(
            "macOS agent and Kotlin readiness require --workspace-root so the plugin-prepared workspace metadata can be verified".to_string(),
        ),
    }
}

#[cfg(not(target_os = "macos"))]
fn apply_macos_plugin_workspace_check(
    _target: ReadyTarget,
    _workspace_root: Option<&Path>,
    _issues: &mut Vec<String>,
) {
}

#[cfg(target_os = "macos")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MacosPluginWorkspaceMetadata {
    schema_version: u32,
    prepared_by: String,
    workspace_root: PathBuf,
    cli_binary: PathBuf,
    backend: String,
    socket_path: PathBuf,
    compatibility: runtime::RuntimeCompatibilityFacts,
    required_artifacts: Vec<PathBuf>,
}

#[cfg(target_os = "macos")]
pub fn validate_macos_plugin_workspace(workspace_root: &Path) -> Result<()> {
    let workspace_root = config::normalize(workspace_root.to_path_buf());
    let metadata_path = workspace_root.join(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE);
    let raw = fs::read_to_string(&metadata_path).map_err(|error| {
        macos_plugin_workspace_error(format!(
            "macOS Kast invocation requires workspace metadata prepared by the Kast IntelliJ plugin at {}: {error}",
            metadata_path.display()
        ))
    })?;
    let metadata: MacosPluginWorkspaceMetadata = serde_json::from_str(&raw).map_err(|error| {
        macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata is not valid for this CLI version at {}; update the CLI and plugin, reopen this exact project, and refresh workspace metadata: {error}",
            metadata_path.display(),
        ))
    })?;
    validate_macos_plugin_workspace_metadata(&workspace_root, &metadata_path, metadata)
}

#[cfg(not(target_os = "macos"))]
pub fn validate_macos_plugin_workspace(_workspace_root: &Path) -> Result<()> {
    Ok(())
}

#[cfg(target_os = "macos")]
fn validate_macos_plugin_workspace_metadata(
    workspace_root: &Path,
    metadata_path: &Path,
    metadata: MacosPluginWorkspaceMetadata,
) -> Result<()> {
    if metadata.schema_version != MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata schemaVersion {} is not recognized by this Kast version; expected {} at {}; update the CLI and plugin, reopen this exact project, and refresh workspace metadata",
            metadata.schema_version,
            MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION,
            metadata_path.display()
        )));
    }
    if metadata.prepared_by != MACOS_PLUGIN_WORKSPACE_PREPARED_BY {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata was prepared by `{}` instead of `{}` at {}",
            metadata.prepared_by,
            MACOS_PLUGIN_WORKSPACE_PREPARED_BY,
            metadata_path.display()
        )));
    }
    validate_prepared_compatibility_metadata(metadata_path, &metadata)?;
    let current_version = cli::version();
    if metadata.compatibility.cli_version != current_version {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata describes CLI version {} but the running CLI is {}; update Kast, reopen this exact project, and refresh workspace metadata at {}",
            metadata.compatibility.cli_version,
            current_version,
            metadata_path.display(),
        )));
    }
    let metadata_workspace_root = config::normalize(metadata.workspace_root);
    if metadata_workspace_root != workspace_root {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata root {} does not match requested workspace {}",
            metadata_workspace_root.display(),
            workspace_root.display()
        )));
    }
    if metadata.backend != MACOS_PLUGIN_WORKSPACE_BACKEND {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata backend `{}` is not recognized by this Kast version; expected `{}`",
            metadata.backend, MACOS_PLUGIN_WORKSPACE_BACKEND
        )));
    }
    let expected_socket_path = config::default_socket_path(workspace_root);
    if metadata.socket_path != expected_socket_path {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata socketPath {} does not match expected socket {}",
            metadata.socket_path.display(),
            expected_socket_path.display()
        )));
    }
    validate_macos_plugin_cli_binary(&metadata.cli_binary)?;
    validate_macos_plugin_required_artifacts(workspace_root, &metadata.required_artifacts)
}

#[cfg(target_os = "macos")]
fn validate_prepared_compatibility_metadata(
    metadata_path: &Path,
    metadata: &MacosPluginWorkspaceMetadata,
) -> Result<()> {
    let facts = &metadata.compatibility;
    if facts.workspace_metadata_revision.0.get() != metadata.schema_version {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace compatibility metadata revision {} does not match schemaVersion {} at {}",
            facts.workspace_metadata_revision.0,
            metadata.schema_version,
            metadata_path.display(),
        )));
    }
    if facts
        .runtime_identity
        .implementation_version
        .chars()
        .any(char::is_whitespace)
        || facts.runtime_identity.implementation_version.is_empty()
    {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace runtime identity has an invalid implementation version at {}",
            metadata_path.display(),
        )));
    }
    if facts.runtime_identity.backend_kind.metadata_name() != metadata.backend {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace runtime identity backend does not match metadata backend at {}",
            metadata_path.display(),
        )));
    }
    if facts
        .read_capabilities
        .iter()
        .copied()
        .collect::<BTreeSet<_>>()
        .len()
        != facts.read_capabilities.len()
        || facts
            .mutation_capabilities
            .iter()
            .copied()
            .collect::<BTreeSet<_>>()
            .len()
            != facts.mutation_capabilities.len()
    {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace compatibility capabilities contain duplicates at {}",
            metadata_path.display(),
        )));
    }
    match runtime::assess_runtime_compatibility(facts, None)? {
        runtime::RuntimeCompatibilityAssessment::Compatible => Ok(()),
        runtime::RuntimeCompatibilityAssessment::UpdateRequired {
            requirement,
            plugin_version,
            cli_version,
        } => Err(macos_plugin_workspace_error(format!(
            "Kast runtime compatibility does not support plugin {plugin_version} with CLI {cli_version} because of {requirement:?}; update the CLI and plugin, reopen this exact project, and refresh workspace metadata at {}",
            metadata_path.display(),
        ))),
        runtime::RuntimeCompatibilityAssessment::MissingCapability { capability } => {
            Err(macos_plugin_workspace_error(format!(
                "Kast runtime compatibility is missing required capability {capability:?}; update the plugin, reopen this exact project, and refresh workspace metadata at {}",
                metadata_path.display(),
            )))
        }
    }
}

#[cfg(target_os = "macos")]
fn validate_macos_plugin_cli_binary(cli_binary: &Path) -> Result<()> {
    let configured = fs::canonicalize(cli_binary).map_err(|error| {
        macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata cliBinary {} cannot be resolved: {error}",
            cli_binary.display()
        ))
    })?;
    let running = env::current_exe()
        .map_err(|error| {
            macos_plugin_workspace_error(format!(
                "Current Kast executable cannot be resolved for macOS workspace validation: {error}"
            ))
        })
        .and_then(|path| {
            fs::canonicalize(&path).map_err(|error| {
                macos_plugin_workspace_error(format!(
                    "Current Kast executable {} cannot be canonicalized for macOS workspace validation: {error}",
                    path.display()
                ))
            })
        })?;
    if configured != running {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata cliBinary {} does not match the running Kast executable {}",
            configured.display(),
            running.display()
        )));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn validate_macos_plugin_required_artifacts(
    workspace_root: &Path,
    required_artifacts: &[PathBuf],
) -> Result<()> {
    if !required_artifacts
        .iter()
        .any(|artifact| artifact == Path::new(MACOS_PLUGIN_REQUIRED_SKILL_RELATIVE))
    {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata does not declare required artifact `{}`",
            MACOS_PLUGIN_REQUIRED_SKILL_RELATIVE
        )));
    }
    if !required_artifacts
        .iter()
        .any(|artifact| artifact == Path::new(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE))
    {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata does not declare required artifact `{}`",
            MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE
        )));
    }
    for artifact in required_artifacts {
        if !is_safe_relative_artifact(artifact) {
            return Err(macos_plugin_workspace_error(format!(
                "macOS Kast workspace metadata contains an unsupported artifact path `{}`",
                artifact.display()
            )));
        }
        let path = workspace_root.join(artifact);
        if !path.exists() {
            return Err(macos_plugin_workspace_error(format!(
                "macOS Kast workspace metadata requires missing artifact {}",
                path.display()
            )));
        }
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn is_safe_relative_artifact(path: &Path) -> bool {
    path.is_relative()
        && path
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
}

#[cfg(target_os = "macos")]
fn macos_plugin_workspace_error(message: String) -> crate::error::CliError {
    crate::error::CliError::new("MACOS_PLUGIN_WORKSPACE_REQUIRED", message)
}

fn configuration_diagnostic(
    config_path: &Path,
    error: Option<String>,
) -> DoctorConfigurationDiagnostic {
    DoctorConfigurationDiagnostic {
        config_home: config::kast_config_home().display().to_string(),
        config_path: config_path.display().to_string(),
        exists: config_path.is_file(),
        valid: error.is_none(),
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn canonical_directory_diagnostic(
    paths: &config::PathsConfig,
) -> DoctorCanonicalDirectoryDiagnostic {
    DoctorCanonicalDirectoryDiagnostic {
        root: paths.install_root.display().to_string(),
        bin_dir: paths.bin_dir.display().to_string(),
        lib_dir: paths.lib_dir.display().to_string(),
        cache_dir: paths.cache_dir.display().to_string(),
        logs_dir: paths.logs_dir.display().to_string(),
        runtime_dir: paths.runtime_dir.display().to_string(),
        descriptor_dir: paths.descriptor_dir.display().to_string(),
        socket_dir: paths.socket_dir.display().to_string(),
        schema_version: SCHEMA_VERSION,
    }
}

fn binary_diagnostic(
    cli: &config::CliConfig,
    install: Option<&InstallState>,
) -> DoctorBinaryDiagnostic {
    let running_binary = env::current_exe().unwrap_or_else(|_| cli.binary_path.clone());
    let configured_binary = cli.binary_path.clone();
    let configured_exists = configured_binary.is_file();
    let configured_matches_running = configured_exists
        && configured_binary_matches_running(&configured_binary, &running_binary, install);
    DoctorBinaryDiagnostic {
        running_binary: running_binary.display().to_string(),
        configured_binary: configured_binary.display().to_string(),
        configured_exists,
        configured_matches_running,
        schema_version: SCHEMA_VERSION,
    }
}

fn configured_binary_matches_running(
    configured_binary: &Path,
    running_binary: &Path,
    install: Option<&InstallState>,
) -> bool {
    same_binary_path(configured_binary, running_binary)
        || install.is_some_and(|install| {
            same_binary_path(
                Path::new(&install.entrypoints.active_binary),
                running_binary,
            )
        })
}

fn same_binary_path(left: &Path, right: &Path) -> bool {
    if config::normalize(left.to_path_buf()) == config::normalize(right.to_path_buf()) {
        return true;
    }
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

pub fn write_install_state(install: &InstallState) -> Result<PathBuf> {
    manifest::write_install_manifest(install)
}

pub fn record_repo_resource(repo_root: &Path, resource: ManagedRepoResource) -> Result<()> {
    let paths = manifest::default_resolved_paths();
    manifest::with_install_lock(&paths, || {
        let mut install =
            manifest::read_install_manifest()?.unwrap_or_else(manifest::fresh_manifest);
        upsert_repo_resource(&mut install, repo_root, resource);
        install.version = install.version.trim().to_string();
        if install.version.is_empty() {
            install.version = cli::version().to_string();
        }
        install.active_version = cli::version().to_string();
        install.updated_at = manifest::current_timestamp();
        let paths = manifest::paths_from_manifest(&install)?;
        manifest::write_manifest_atomic(&paths.manifest_file, &install)
    })
}

fn upsert_repo_resource(
    install: &mut InstallState,
    repo_root: &Path,
    mut resource: ManagedRepoResource,
) {
    let normalized_repo = config::normalize(repo_root.to_path_buf());
    let repo_path = normalized_repo.display().to_string();
    let repo_index = install
        .repos
        .iter()
        .position(|repo| repo.path == repo_path)
        .unwrap_or_else(|| {
            install.repos.push(ManagedRepo {
                path: repo_path.clone(),
                copilot_package_version: String::new(),
                resources: vec![],
            });
            install.repos.len() - 1
        });
    let repo = &mut install.repos[repo_index];
    if resource.kind == ManagedResourceKind::CopilotPackage {
        repo.copilot_package_version.clear();
    }
    let normalized_target = config::normalize(PathBuf::from(&resource.target_path));
    if let Some(existing_index) = repo.resources.iter().position(|existing| {
        existing.kind == resource.kind
            && config::normalize(PathBuf::from(&existing.target_path)) == normalized_target
    }) {
        let existing = repo.resources.remove(existing_index);
        let mut history = existing.history;
        history.insert(
            0,
            ManagedRepoResourceHistory {
                primitive_version: existing.primitive_version,
                source_bundle_sha256: existing.source_bundle_sha256,
                installed_at: existing.installed_at,
                output_checksums: existing.output_checksums,
            },
        );
        history.truncate(MANAGED_RESOURCE_HISTORY_LIMIT);
        resource.history = history;
    }
    repo.resources.push(resource);
    repo.resources.sort_by(|left, right| {
        left.kind
            .cmp(&right.kind)
            .then_with(|| left.target_path.cmp(&right.target_path))
    });
}

pub fn read_global_install_state() -> Result<Option<InstallState>> {
    manifest::read_install_manifest()
}

pub fn update_global_config(
    mutator: impl FnOnce(&mut toml::Table) -> Result<()>,
) -> Result<PathBuf> {
    let path = config::global_config_path();
    update_config_file(&path, mutator)
}

pub fn configure_developer_machine_defaults(
    dry_run: bool,
) -> Result<DeveloperMachineDefaultsResult> {
    let config_path = config::global_config_path();
    if !dry_run {
        update_global_config(write_developer_machine_idea_defaults)?;
    }
    Ok(DeveloperMachineDefaultsResult {
        config_path: config_path.display().to_string(),
        default_backend: config::RuntimeDefaultBackend::Idea,
        idea_launch_enabled: true,
        idea_launch_command: "idea".to_string(),
        applied: !dry_run,
        schema_version: SCHEMA_VERSION,
    })
}

pub(crate) fn write_developer_machine_idea_defaults(document: &mut toml::Table) -> Result<()> {
    table(document, "runtime")?.insert("defaultBackend".to_string(), "idea".into());
    let idea_launch = nested_table(document, "runtime", "ideaLaunch")?;
    idea_launch.insert("enabled".to_string(), true.into());
    idea_launch.insert("command".to_string(), "idea".into());
    Ok(())
}

fn update_config_file(
    path: &Path,
    mutator: impl FnOnce(&mut toml::Table) -> Result<()>,
) -> Result<PathBuf> {
    let mut document = default_config_document()?;
    merge_config_document(&mut document, read_config_document(path)?);
    mutator(&mut document)?;
    write_config_document(path, &document)?;
    Ok(path.to_path_buf())
}

pub fn remove_global_install_state() -> Result<bool> {
    let path = manifest::default_install_manifest_path();
    if !path.exists() {
        return Ok(false);
    }
    fs::remove_file(path)?;
    Ok(true)
}

fn read_config_document(path: &Path) -> Result<toml::Table> {
    if !path.is_file() {
        return Ok(toml::Table::new());
    }
    Ok(fs::read_to_string(path)?.parse::<toml::Table>()?)
}

fn write_config_document(path: &Path, document: &toml::Table) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, toml::to_string_pretty(document)?)?;
    Ok(())
}

fn default_config_document() -> Result<toml::Table> {
    Ok(config::default_config_template()?.parse::<toml::Table>()?)
}

fn table<'a>(document: &'a mut toml::Table, key: &str) -> Result<&'a mut toml::Table> {
    document
        .entry(key.to_string())
        .or_insert_with(|| toml::Value::Table(toml::Table::new()))
        .as_table_mut()
        .ok_or_else(|| {
            crate::error::CliError::new(
                "CONFIG_ERROR",
                format!(
                    "Cannot write developer-machine config because `{key}` is not a TOML table."
                ),
            )
        })
}

fn nested_table<'a>(
    document: &'a mut toml::Table,
    first: &str,
    second: &str,
) -> Result<&'a mut toml::Table> {
    table(document, first)?
        .entry(second.to_string())
        .or_insert_with(|| toml::Value::Table(toml::Table::new()))
        .as_table_mut()
        .ok_or_else(|| {
            crate::error::CliError::new(
                "CONFIG_ERROR",
                format!(
                    "Cannot write developer-machine config because `{first}.{second}` is not a TOML table."
                ),
            )
        })
}

fn merge_config_document(base: &mut toml::Table, overlay: toml::Table) {
    for (key, value) in overlay {
        match (base.get_mut(&key), value) {
            (Some(toml::Value::Table(base_table)), toml::Value::Table(overlay_table)) => {
                merge_config_document(base_table, overlay_table);
            }
            (_, value) => {
                base.insert(key, value);
            }
        }
    }
}

fn managed_path(install_root: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        install_root.join(path)
    }
}

fn minimum_backend_version() -> &'static str {
    option_env!("KAST_MIN_DAEMON_VERSION").unwrap_or("0.7.11")
}

fn version_meets_minimum(version: &str, minimum: &str) -> Option<bool> {
    Some(parse_version_triplet(version)? >= parse_version_triplet(minimum)?)
}

fn parse_version_triplet(value: &str) -> Option<(u64, u64, u64)> {
    let normalized = value.trim().trim_start_matches('v');
    let mut parts = normalized.split(['.', '-', '+']);
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next()?.parse().ok()?;
    Some((major, minor, patch))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_repo_resource(
        kind: ManagedResourceKind,
        target_path: &str,
        version: &str,
    ) -> ManagedRepoResource {
        ManagedRepoResource {
            kind,
            target_path: target_path.to_string(),
            primitive_version: version.to_string(),
            source_bundle_sha256: format!("source-{version}"),
            output_paths: vec![format!("{target_path}/output.md")],
            output_checksums: vec![],
            installed_at: format!("installed-{version}"),
            history: vec![],
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_plugin_workspace_metadata_accepts_resolved_config_socket_path() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace_root = config::normalize(temp.path().join("workspace"));
        fs::create_dir_all(workspace_root.join(".agents/skills/kast")).expect("skill dir");
        fs::create_dir_all(workspace_root.join(".kast/setup")).expect("metadata dir");
        fs::write(
            workspace_root.join(MACOS_PLUGIN_REQUIRED_SKILL_RELATIVE),
            "skill",
        )
        .expect("skill file");
        let metadata_path = workspace_root.join(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE);
        fs::write(&metadata_path, "{}").expect("metadata file");
        let socket_path = config::KastConfig::defaults()
            .paths
            .socket_dir
            .join(format!(
                "kast-{}.sock",
                config::workspace_hash(&workspace_root)
            ));

        validate_macos_plugin_workspace_metadata(
            &workspace_root,
            &metadata_path,
            MacosPluginWorkspaceMetadata {
                schema_version: MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION,
                prepared_by: MACOS_PLUGIN_WORKSPACE_PREPARED_BY.to_string(),
                workspace_root: workspace_root.clone(),
                cli_binary: env::current_exe().expect("current exe"),
                backend: MACOS_PLUGIN_WORKSPACE_BACKEND.to_string(),
                socket_path,
                compatibility: runtime::RuntimeCompatibilityFacts {
                    plugin_version: cli::version().to_string(),
                    cli_version: cli::version().to_string(),
                    protocol_revision: runtime::ProtocolRevision(
                        std::num::NonZeroU32::new(1).expect("revision"),
                    ),
                    workspace_metadata_revision: runtime::WorkspaceMetadataRevision(
                        std::num::NonZeroU32::new(MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION)
                            .expect("revision"),
                    ),
                    read_capabilities: vec![
                        runtime::WorkspaceReadCapability::Diagnostics,
                        runtime::WorkspaceReadCapability::ResolveSymbol,
                        runtime::WorkspaceReadCapability::WorkspaceFiles,
                    ],
                    mutation_capabilities: vec![
                        runtime::WorkspaceMutationCapability::ApplyEdits,
                        runtime::WorkspaceMutationCapability::RefreshWorkspace,
                        runtime::WorkspaceMutationCapability::Rename,
                    ],
                    runtime_identity: runtime::WorkspaceRuntimeIdentity {
                        implementation_version: cli::version().to_string(),
                        backend_kind: runtime::WorkspaceRuntimeBackendKind::Idea,
                    },
                },
                required_artifacts: vec![
                    PathBuf::from(MACOS_PLUGIN_REQUIRED_SKILL_RELATIVE),
                    PathBuf::from(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE),
                ],
            },
        )
        .expect("resolved config socket path should be accepted");
    }

    #[test]
    fn agent_readiness_scopes_repo_resource_verification_to_requested_workspace() {
        let workspace_root = Path::new("/workspace/kast/.worktrees/feature");
        let parent_repo = ManagedRepo {
            path: "/workspace/kast".to_string(),
            copilot_package_version: String::new(),
            resources: vec![],
        };
        #[cfg(not(target_os = "macos"))]
        let workspace_repo = ManagedRepo {
            path: workspace_root.display().to_string(),
            copilot_package_version: String::new(),
            resources: vec![],
        };

        assert!(!should_verify_repo_resources_for_target(
            ReadyTarget::Agent,
            Some(workspace_root),
            &parent_repo
        ));
        #[cfg(not(target_os = "macos"))]
        assert!(should_verify_repo_resources_for_target(
            ReadyTarget::Agent,
            Some(workspace_root),
            &workspace_repo
        ));
        assert!(should_verify_repo_resources_for_target(
            ReadyTarget::Machine,
            Some(workspace_root),
            &parent_repo
        ));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_agent_readiness_uses_plugin_metadata_instead_of_manifest_repo_resources() {
        let workspace_root = Path::new("/workspace/kast/.worktrees/feature");
        let workspace_repo = ManagedRepo {
            path: workspace_root.display().to_string(),
            copilot_package_version: String::new(),
            resources: vec![],
        };

        assert!(!should_verify_repo_resources_for_target(
            ReadyTarget::Agent,
            Some(workspace_root),
            &workspace_repo
        ));
        assert!(!should_verify_repo_resources_for_target(
            ReadyTarget::Kotlin,
            Some(workspace_root),
            &workspace_repo
        ));
    }

    #[test]
    fn configured_binary_match_accepts_manifest_active_binary() {
        let configured_binary = Path::new("/example/bin/kast");
        let running_binary = Path::new("/example/versions/0.1.0/bin/kast");
        let mut install = manifest::fresh_manifest();
        install.entrypoints.active_binary = running_binary.display().to_string();

        assert!(configured_binary_matches_running(
            configured_binary,
            running_binary,
            Some(&install)
        ));
        assert!(!configured_binary_matches_running(
            configured_binary,
            Path::new("/other/bin/kast"),
            Some(&install)
        ));
    }

    #[test]
    fn repo_resource_upsert_preserves_resources_and_records_history() {
        let mut install = manifest::fresh_manifest();
        let repo_root = Path::new("/workspace/kast");
        let skill_target = "/workspace/kast/.agents/skills/kast";
        let instructions_target = "/workspace/kast/.agents/instructions/kast";

        upsert_repo_resource(
            &mut install,
            repo_root,
            test_repo_resource(ManagedResourceKind::Skill, skill_target, "1.0.0"),
        );
        upsert_repo_resource(
            &mut install,
            repo_root,
            test_repo_resource(
                ManagedResourceKind::Instructions,
                instructions_target,
                "1.0.0",
            ),
        );
        upsert_repo_resource(
            &mut install,
            repo_root,
            test_repo_resource(ManagedResourceKind::Skill, skill_target, "1.0.1"),
        );

        let repo = install
            .repos
            .iter()
            .find(|repo| repo.path == repo_root.display().to_string())
            .expect("repo resource entry");
        assert_eq!(repo.resources.len(), 2);
        let skill = repo
            .resources
            .iter()
            .find(|resource| resource.kind == ManagedResourceKind::Skill)
            .expect("skill resource");
        assert_eq!(skill.primitive_version, "1.0.1");
        assert_eq!(skill.history.len(), 1);
        assert_eq!(skill.history[0].primitive_version, "1.0.0");
        assert!(
            repo.resources
                .iter()
                .any(|resource| resource.kind == ManagedResourceKind::Instructions)
        );
    }
}
