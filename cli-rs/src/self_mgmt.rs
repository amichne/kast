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

mod agent_readiness;

use agent_readiness::agent_environment_diagnostic;
pub use agent_readiness::{AgentResourceState, DoctorAgentEnvironmentDiagnostic};

pub use crate::manifest::{KastInstallManifest as InstallState, ManagedRepo, ManagedResourceKind};

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
                    "Managed repo {} uses retired copilotPackageVersion state; rerun `kast setup --source <bundle>`",
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
            if install.is_none_or(|install| install.backends.is_empty()) {
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
    let strict_plugin_matching = config::KastConfig::load(&workspace_root)?
        .runtime
        .strict_plugin_matching;
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
    validate_macos_plugin_workspace_metadata(
        &workspace_root,
        &metadata_path,
        metadata,
        strict_plugin_matching,
    )
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
    strict_plugin_matching: bool,
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
    validate_prepared_compatibility_metadata(metadata_path, &metadata, strict_plugin_matching)?;
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
    strict_plugin_matching: bool,
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
    match runtime::assess_runtime_compatibility_with_plugin_matching(
        facts,
        None,
        strict_plugin_matching,
    )? {
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
    if required_artifacts != [PathBuf::from(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE)] {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata must declare only its exact-root artifact `{}`",
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
        && configured_binary_matches_running(
            &configured_binary,
            &running_binary,
            install.map(|install| Path::new(&install.entrypoints.active_binary)),
        );
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
    active_binary: Option<&Path>,
) -> bool {
    same_binary_path(configured_binary, running_binary)
        || active_binary
            .is_some_and(|active_binary| same_binary_path(active_binary, running_binary))
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

pub fn read_global_install_state() -> Result<Option<InstallState>> {
    manifest::read_install_manifest()
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

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_plugin_workspace_metadata_accepts_resolved_config_socket_path() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace_root = config::normalize(temp.path().join("workspace"));
        fs::create_dir_all(workspace_root.join(".kast/setup")).expect("metadata dir");
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
                        std::num::NonZeroU32::new(2).expect("revision"),
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
                required_artifacts: vec![PathBuf::from(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE)],
            },
            true,
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

        assert!(configured_binary_matches_running(
            configured_binary,
            running_binary,
            Some(running_binary)
        ));
        assert!(!configured_binary_matches_running(
            configured_binary,
            Path::new("/other/bin/kast"),
            Some(running_binary)
        ));
    }
}
