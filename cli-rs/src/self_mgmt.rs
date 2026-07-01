use crate::SCHEMA_VERSION;
use crate::cli;
use crate::cli::{InstallRepairArgs, ReadyTarget};
use crate::config::{self, PathResolutionReport};
use crate::error::Result;
use crate::install::{self, InstallRepairResult};
use crate::manifest;
use serde::Serialize;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

pub use crate::manifest::{
    KastInstallManifest as InstallState, ManagedRepo, ManagedRepoResource,
    ManagedRepoResourceHistory, ManagedResourceKind,
};

const MANAGED_RESOURCE_HISTORY_LIMIT: usize = 5;

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
    pub require_installed_plugin: bool,
    pub applied: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelfDoctorResult {
    pub target: ReadyTarget,
    pub installed: bool,
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

pub fn doctor(repair: bool, target: ReadyTarget) -> Result<SelfDoctorResult> {
    let config_path = config::global_config_path();
    let manifest_path = manifest::default_install_manifest_path();
    let mut issues = vec![];
    let mut warnings = vec![];
    let repair_result = if repair {
        manifest::install_current_executable()?;
        Some(install::repair_install_state(InstallRepairArgs {
            apply: true,
            jetbrains_config_root: None,
        })?)
    } else {
        None
    };
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
        for repo in &install.repos {
            if !repo.copilot_package_version.trim().is_empty()
                && !repo
                    .resources
                    .iter()
                    .any(|resource| resource.kind == ManagedResourceKind::CopilotPackage)
            {
                issues.push(format!(
                    "Managed repo {} uses retired copilotPackageVersion state, which is incompatible with manifest-backed resource verification; upgrade/reinstall Kast if needed, then run `kast ready --fix` to refresh from the active binary bundles",
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
    apply_ready_target_checks(target, install.as_ref(), &binary, &mut issues);
    Ok(SelfDoctorResult {
        target,
        installed: install.is_some(),
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

fn apply_ready_target_checks(
    target: ReadyTarget,
    install: Option<&InstallState>,
    binary: &DoctorBinaryDiagnostic,
    issues: &mut Vec<String>,
) {
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

pub fn record_repo_resource(repo_root: &Path, mut resource: ManagedRepoResource) -> Result<()> {
    let normalized_repo = config::normalize(repo_root.to_path_buf());
    let repo_path = normalized_repo.display().to_string();
    let mut install = read_global_install_state()?.unwrap_or_else(manifest::fresh_manifest);
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
    install.version = install.version.trim().to_string();
    if install.version.is_empty() {
        install.version = cli::version().to_string();
    }
    install.active_version = cli::version().to_string();
    install.updated_at = manifest::current_timestamp();
    write_install_state(&install)?;
    Ok(())
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

pub fn update_workspace_config(
    workspace_root: &Path,
    mutator: impl FnOnce(&mut toml::Table) -> Result<()>,
) -> Result<PathBuf> {
    let path = config::workspace_data_directory(workspace_root)?.join("config.toml");
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
        require_installed_plugin: true,
        applied: !dry_run,
        schema_version: SCHEMA_VERSION,
    })
}

pub(crate) fn write_developer_machine_idea_defaults(document: &mut toml::Table) -> Result<()> {
    table(document, "runtime")?.insert("defaultBackend".to_string(), "idea".into());
    let idea_launch = nested_table(document, "runtime", "ideaLaunch")?;
    idea_launch.insert("enabled".to_string(), true.into());
    idea_launch.insert("command".to_string(), "idea".into());
    idea_launch.insert("requireInstalledPlugin".to_string(), true.into());
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
}
