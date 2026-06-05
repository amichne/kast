use crate::SCHEMA_VERSION;
use crate::cli;
use crate::config;
use crate::error::Result;
use serde::{Deserialize, Serialize};
use serde_json::Value;
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
pub struct SelfStatusResult {
    pub installed: bool,
    pub config_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallState>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelfDoctorResult {
    pub installed: bool,
    pub config_path: String,
    pub minimum_backend_version: String,
    pub ok: bool,
    pub issues: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelfUninstallResult {
    pub skipped: bool,
    pub removed_managed_paths: Vec<String>,
    pub cleaned_shell_rc_files: Vec<String>,
    pub removed_install_state: bool,
    pub removed_install_root: bool,
    pub schema_version: u32,
}

pub fn status() -> Result<SelfStatusResult> {
    let config_path = config::global_config_path();
    let install = read_install_state(&config_path)?;
    Ok(SelfStatusResult {
        installed: install.is_some(),
        config_path: config_path.display().to_string(),
        install,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn doctor() -> Result<SelfDoctorResult> {
    let config_path = config::global_config_path();
    let install = read_install_state(&config_path)?;
    let global_config = config::KastConfig::load_global()?;
    let install_root = global_config.paths.install_root;
    let minimum_backend_version = minimum_backend_version();
    let mut issues = vec![];
    let mut warnings = vec![];
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
        minimum_backend_version: minimum_backend_version.to_string(),
        ok: issues.is_empty(),
        issues,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn uninstall() -> Result<SelfUninstallResult> {
    let path = config::global_config_path();
    let Some(install) = read_install_state(&path)? else {
        return Ok(SelfUninstallResult {
            skipped: true,
            removed_managed_paths: vec![],
            cleaned_shell_rc_files: vec![],
            removed_install_state: false,
            removed_install_root: false,
            schema_version: SCHEMA_VERSION,
        });
    };
    let global_config = config::KastConfig::load_global()?;
    let install_root = global_config.paths.install_root;
    let mut removed = vec![];
    for managed_path_value in install.managed_paths {
        let managed = managed_path(&install_root, &managed_path_value);
        if managed.is_file() {
            fs::remove_file(&managed)?;
            removed.push(managed_path_value);
        } else if managed.is_dir() {
            fs::remove_dir_all(&managed)?;
            removed.push(managed_path_value);
        }
    }
    let removed_install_state = remove_install_state(&path)?;
    let removed_install_root =
        if install_root.is_dir() && fs::read_dir(&install_root)?.next().is_none() {
            fs::remove_dir(&install_root)?;
            true
        } else {
            false
        };
    Ok(SelfUninstallResult {
        skipped: false,
        removed_managed_paths: removed,
        cleaned_shell_rc_files: vec![],
        removed_install_state,
        removed_install_root,
        schema_version: SCHEMA_VERSION,
    })
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

pub fn forget_copilot_repo(github_dir: &Path) -> Result<()> {
    let path = config::global_config_path();
    let Some(mut install) = read_install_state(&path)? else {
        return Ok(());
    };
    let repo_root = github_dir
        .parent()
        .unwrap_or(github_dir)
        .to_path_buf()
        .components()
        .collect::<PathBuf>();
    let repo_path = repo_root.display().to_string();
    install.repos.retain(|repo| repo.path != repo_path);
    if install.components.is_empty()
        && install.backends.is_empty()
        && install.managed_paths.is_empty()
        && install.shell_rc_patches.is_empty()
        && install.repos.is_empty()
    {
        remove_install_state(&path)?;
    } else {
        write_install_state(&install)?;
    }
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

pub fn remove_global_install_state() -> Result<bool> {
    remove_install_state(&config::global_config_path())
}

fn remove_install_state(path: &Path) -> Result<bool> {
    let mut document = read_config_document(path)?;
    let removed = document.remove("install").is_some();
    if !removed {
        return Ok(false);
    }
    if document.is_empty() {
        fs::remove_file(path)?;
    } else {
        write_config_document(path, &document)?;
    }
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
