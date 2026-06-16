use crate::SCHEMA_VERSION;
use crate::cli;
use crate::config;
use crate::error::Result;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct InstallState {
    #[serde(default)]
    pub version: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub backend_version: String,
    #[serde(default)]
    pub installed_at: String,
    #[serde(default)]
    pub platform: String,
    #[serde(default)]
    pub components: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub backends: Vec<BackendComponentState>,
    #[serde(default)]
    pub managed_paths: Vec<String>,
    #[serde(default)]
    pub shell_rc_patches: Vec<Value>,
    #[serde(default)]
    pub repos: Vec<ManagedRepo>,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BackendComponentState {
    pub name: String,
    pub version: String,
    pub install_dir: String,
    pub runtime_libs_dir: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idea_home: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ManagedRepo {
    pub path: String,
    pub copilot_extension_version: String,
}

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
pub struct SelfDoctorResult {
    pub installed: bool,
    pub config_path: String,
    pub configuration: DoctorConfigurationDiagnostic,
    pub canonical_directory: DoctorCanonicalDirectoryDiagnostic,
    pub binary: DoctorBinaryDiagnostic,
    pub minimum_backend_version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallState>,
    pub ok: bool,
    pub issues: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

pub fn doctor() -> Result<SelfDoctorResult> {
    let config_path = config::global_config_path();
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
    let install = match read_install_state(&config_path) {
        Ok(install) => install,
        Err(error) => {
            issues.push(format!(
                "Install state could not be read from {}: {}",
                config_path.display(),
                error.message
            ));
            None
        }
    };
    let install_root = global_config.paths.install_root.clone();
    let canonical_directory = canonical_directory_diagnostic(&global_config.paths);
    let binary = binary_diagnostic(&global_config.cli);
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
    } else {
        issues.push("Install state is missing from config.toml".to_string());
    }
    Ok(SelfDoctorResult {
        installed: install.is_some(),
        config_path: config_path.display().to_string(),
        configuration,
        canonical_directory,
        binary,
        minimum_backend_version: minimum_backend_version.to_string(),
        install,
        ok: issues.is_empty(),
        issues,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
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
        descriptor_dir: paths.descriptor_dir.display().to_string(),
        socket_dir: paths.socket_dir.display().to_string(),
        schema_version: SCHEMA_VERSION,
    }
}

fn binary_diagnostic(cli: &config::CliConfig) -> DoctorBinaryDiagnostic {
    let running_binary = env::current_exe().unwrap_or_else(|_| cli.binary_path.clone());
    let configured_binary = cli.binary_path.clone();
    let configured_exists = configured_binary.is_file();
    DoctorBinaryDiagnostic {
        running_binary: running_binary.display().to_string(),
        configured_binary: configured_binary.display().to_string(),
        configured_exists,
        configured_matches_running: configured_exists
            && config::normalize(configured_binary) == config::normalize(running_binary),
        schema_version: SCHEMA_VERSION,
    }
}

pub fn write_install_state(install: &InstallState) -> Result<PathBuf> {
    let path = config::global_config_path();
    let mut document = default_config_document()?;
    merge_config_document(&mut document, read_config_document(&path)?);
    document.insert(
        "install".to_string(),
        toml::Value::Table(install_state_table(install)?),
    );
    write_config_document(&path, &document)?;
    Ok(path)
}

pub fn record_copilot_repo(github_dir: &Path, version: &str) -> Result<()> {
    let repo_root = github_dir
        .parent()
        .unwrap_or(github_dir)
        .to_path_buf()
        .components()
        .collect::<PathBuf>();
    let path = config::global_config_path();
    let mut install = read_install_state(&path)?.unwrap_or_else(|| InstallState {
        version: version.to_string(),
        backend_version: String::new(),
        installed_at: String::new(),
        platform: String::new(),
        components: vec![],
        backends: vec![],
        managed_paths: vec![],
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: SCHEMA_VERSION,
    });
    let repo_path = repo_root.display().to_string();
    install.repos.retain(|repo| repo.path != repo_path);
    install.repos.push(ManagedRepo {
        path: repo_path,
        copilot_extension_version: version.to_string(),
    });
    install.version = install.version.trim().to_string();
    if install.version.is_empty() {
        install.version = cli::version().to_string();
    }
    write_install_state(&install)?;
    Ok(())
}

fn read_install_state(path: &Path) -> Result<Option<InstallState>> {
    if !path.is_file() {
        return Ok(None);
    }
    let document = read_config_document(path)?;
    let Some(value) = document.get("install") else {
        return Ok(None);
    };
    Ok(Some(value.clone().try_into()?))
}

pub fn read_global_install_state() -> Result<Option<InstallState>> {
    read_install_state(&config::global_config_path())
}

pub fn update_global_config(
    mutator: impl FnOnce(&mut toml::Table) -> Result<()>,
) -> Result<PathBuf> {
    let path = config::global_config_path();
    let mut document = default_config_document()?;
    merge_config_document(&mut document, read_config_document(&path)?);
    mutator(&mut document)?;
    write_config_document(&path, &document)?;
    Ok(path)
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

fn install_state_table(install: &InstallState) -> Result<toml::Table> {
    Ok(toml::to_string_pretty(install)?.parse::<toml::Table>()?)
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

fn schema_version() -> u32 {
    SCHEMA_VERSION
}
