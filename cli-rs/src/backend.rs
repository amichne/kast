use crate::SCHEMA_VERSION;
use crate::cli::{
    self, BackendCommand, BackendComponent, BackendInstallArgs, BackendUninstallArgs,
};
use crate::config::{self, KastConfig};
use crate::error::{CliError, Result};
use crate::self_mgmt::{self, BackendComponentState, InstallState};
use serde::Serialize;
use std::env;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum BackendResult {
    Install(BackendInstallResult),
    Uninstall(BackendUninstallResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendInstallResult {
    pub backend_name: String,
    pub version: String,
    pub installed_at: String,
    pub install_dir: String,
    pub runtime_libs_dir: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub idea_home: Option<String>,
    pub source_archive: String,
    pub downloaded: bool,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendUninstallResult {
    pub backend_name: String,
    pub skipped: bool,
    pub removed_paths: Vec<String>,
    pub schema_version: u32,
}

struct BackendLayout {
    archive_root: &'static str,
    install_name_prefix: &'static str,
    launcher: &'static str,
}

struct TempTree {
    path: PathBuf,
}

impl Drop for TempTree {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

pub fn run(command: BackendCommand) -> Result<BackendResult> {
    match command {
        BackendCommand::Install(args) => install(args).map(BackendResult::Install),
        BackendCommand::Uninstall(args) => uninstall(args).map(BackendResult::Uninstall),
    }
}

fn install(args: BackendInstallArgs) -> Result<BackendInstallResult> {
    let config = KastConfig::load_global()?;
    let layout = layout(args.backend);
    let version_source = args.version.as_deref().unwrap_or(cli::version());
    let version = release_tag(version_source);
    let (archive, downloaded, temp_download) = resolve_archive(&args, &version)?;
    let install_dir = install_dir(&config, args.backend, &version);
    let current_dir = current_dir(&config, args.backend);
    let skipped = install_dir.exists() && !args.yes.unwrap_or(false);

    if !skipped {
        let temp = temp_tree("kast-backend-install")?;
        let extract_dir = temp.path.join("extract");
        extract_zip_archive(&archive, &extract_dir)?;
        let source_root = extract_dir.join(layout.archive_root);
        validate_backend_source(args.backend, &source_root)?;
        remove_path(&install_dir)?;
        if let Some(parent) = install_dir.parent() {
            fs::create_dir_all(parent)?;
        }
        copy_dir(&source_root, &install_dir)?;
    }

    remove_path(&current_dir)?;
    if let Some(parent) = current_dir.parent() {
        fs::create_dir_all(parent)?;
    }
    link_current(&install_dir, &current_dir)?;

    let runtime_libs_dir = current_dir.join("runtime-libs");
    let idea_home =
        (args.backend == BackendComponent::Headless).then(|| current_dir.join("idea-home"));
    update_backend_config(args.backend, &runtime_libs_dir, idea_home.as_deref())?;
    update_install_state(
        args.backend,
        &version,
        &install_dir,
        &runtime_libs_dir,
        idea_home.as_deref(),
    )?;

    let _cleanup = temp_download;
    Ok(BackendInstallResult {
        backend_name: args.backend.canonical().to_string(),
        version,
        installed_at: current_timestamp(),
        install_dir: install_dir.display().to_string(),
        runtime_libs_dir: runtime_libs_dir.display().to_string(),
        idea_home: idea_home.map(|path| path.display().to_string()),
        source_archive: archive.display().to_string(),
        downloaded,
        skipped,
        schema_version: SCHEMA_VERSION,
    })
}

fn uninstall(args: BackendUninstallArgs) -> Result<BackendUninstallResult> {
    let config = KastConfig::load_global()?;
    let component_name = args.backend.canonical();
    let mut install = self_mgmt::read_global_install_state()?.unwrap_or_else(default_install_state);
    let matching: Vec<_> = install
        .backends
        .iter()
        .filter(|backend| backend.name == component_name)
        .cloned()
        .collect();
    if matching.is_empty() {
        return Ok(BackendUninstallResult {
            backend_name: component_name.to_string(),
            skipped: true,
            removed_paths: vec![],
            schema_version: SCHEMA_VERSION,
        });
    }

    let current = current_dir(&config, args.backend);
    let mut removed_paths = vec![];
    remove_path_recording(&current, &mut removed_paths)?;
    for backend in &matching {
        remove_path_recording(Path::new(&backend.install_dir), &mut removed_paths)?;
    }

    install
        .backends
        .retain(|backend| backend.name != component_name);
    install
        .components
        .retain(|component| component != &format!("backend:{component_name}"));
    install.managed_paths.retain(|path| {
        path != &relative_or_display(&config.paths.install_root, &current)
            && matching.iter().all(|backend| {
                path != &relative_or_display(
                    &config.paths.install_root,
                    Path::new(&backend.install_dir),
                )
            })
    });
    if install.components.is_empty()
        && install.backends.is_empty()
        && install.managed_paths.is_empty()
        && install.shell_rc_patches.is_empty()
        && install.repos.is_empty()
    {
        self_mgmt::remove_global_install_state()?;
    } else {
        self_mgmt::write_install_state(&install)?;
    }

    Ok(BackendUninstallResult {
        backend_name: component_name.to_string(),
        skipped: false,
        removed_paths,
        schema_version: SCHEMA_VERSION,
    })
}

fn resolve_archive(
    args: &BackendInstallArgs,
    version: &str,
) -> Result<(PathBuf, bool, Option<TempTree>)> {
    if let Some(archive) = &args.archive {
        let path = config::normalize(archive.clone());
        if !path.is_file() {
            return Err(CliError::new(
                "BACKEND_ARCHIVE_NOT_FOUND",
                format!("Backend archive not found at {}", path.display()),
            ));
        }
        return Ok((path, false, None));
    }

    let temp = temp_tree("kast-backend-download")?;
    let asset_name = backend_asset_name(args.backend, version);
    let archive = temp.path.join(&asset_name);
    let base_url = args
        .base_url
        .clone()
        .unwrap_or_else(|| format!("https://github.com/amichne/kast/releases/download/{version}"));
    let url = format!("{}/{}", base_url.trim_end_matches('/'), asset_name);
    download_with_curl(&url, &archive)?;
    Ok((archive, true, Some(temp)))
}

fn download_with_curl(url: &str, output: &Path) -> Result<()> {
    let status = Command::new("curl")
        .args([
            "--fail",
            "--location",
            "--retry",
            "3",
            "--retry-delay",
            "2",
            "--silent",
            "--show-error",
            "--output",
            output.to_str().unwrap_or_default(),
            url,
        ])
        .status()
        .map_err(|error| {
            CliError::new(
                "BACKEND_DOWNLOAD_FAILED",
                format!("Unable to run curl: {error}"),
            )
        })?;
    if !status.success() {
        return Err(CliError::new(
            "BACKEND_DOWNLOAD_FAILED",
            format!("Failed to download backend archive from {url}"),
        ));
    }
    Ok(())
}

fn extract_zip_archive(archive: &Path, output_dir: &Path) -> Result<()> {
    let file = fs::File::open(archive)?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|error| CliError::new("BACKEND_ARCHIVE_INVALID", error.to_string()))?;
    fs::create_dir_all(output_dir)?;
    let resolved_output = output_dir.canonicalize()?;
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|error| CliError::new("BACKEND_ARCHIVE_INVALID", error.to_string()))?;
        let Some(enclosed_name) = entry.enclosed_name() else {
            return Err(CliError::new(
                "BACKEND_ARCHIVE_INVALID",
                format!("Unsafe zip member: {}", entry.name()),
            ));
        };
        let target = output_dir.join(enclosed_name);
        let resolved_target = target
            .parent()
            .unwrap_or(output_dir)
            .canonicalize()
            .unwrap_or_else(|_| resolved_output.clone());
        if resolved_target != resolved_output && !resolved_target.starts_with(&resolved_output) {
            return Err(CliError::new(
                "BACKEND_ARCHIVE_INVALID",
                format!("Unsafe zip member: {}", entry.name()),
            ));
        }
        if entry.is_dir() {
            fs::create_dir_all(&target)?;
        } else {
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut output = fs::File::create(&target)?;
            io::copy(&mut entry, &mut output)?;
            #[cfg(unix)]
            if let Some(mode) = entry.unix_mode() {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&target, fs::Permissions::from_mode(mode))?;
            }
        }
    }
    Ok(())
}

fn validate_backend_source(backend: BackendComponent, source_root: &Path) -> Result<()> {
    let layout = layout(backend);
    require_file(
        source_root.join("runtime-libs/classpath.txt"),
        "runtime-libs/classpath.txt",
    )?;
    require_file(source_root.join(layout.launcher), layout.launcher)?;
    if backend == BackendComponent::Headless {
        require_file(
            source_root.join("idea-home/lib/nio-fs.jar"),
            "idea-home/lib/nio-fs.jar",
        )?;
        require_file(
            source_root.join("idea-home/modules/module-descriptors.dat"),
            "idea-home/modules/module-descriptors.dat",
        )?;
        if !source_root.join("idea-home/plugins/kast-headless").is_dir() {
            return Err(CliError::new(
                "BACKEND_ARCHIVE_INVALID",
                "Headless backend archive missing idea-home/plugins/kast-headless",
            ));
        }
    }
    Ok(())
}

fn require_file(path: PathBuf, label: &str) -> Result<()> {
    if path.is_file() {
        return Ok(());
    }
    Err(CliError::new(
        "BACKEND_ARCHIVE_INVALID",
        format!("Backend archive missing {label}"),
    ))
}

fn update_backend_config(
    backend: BackendComponent,
    runtime_libs_dir: &Path,
    idea_home: Option<&Path>,
) -> Result<()> {
    self_mgmt::update_global_config(|document| {
        let backends = table_entry(document, "backends")?;
        let backend_table = table_entry(backends, backend.canonical())?;
        backend_table.insert(
            "runtimeLibsDir".to_string(),
            toml::Value::String(runtime_libs_dir.display().to_string()),
        );
        if let Some(idea_home) = idea_home {
            backend_table.insert(
                "ideaHome".to_string(),
                toml::Value::String(idea_home.display().to_string()),
            );
        }
        Ok(())
    })?;
    Ok(())
}

fn table_entry<'a>(table: &'a mut toml::Table, key: &str) -> Result<&'a mut toml::Table> {
    let value = table
        .entry(key.to_string())
        .or_insert_with(|| toml::Value::Table(toml::Table::new()));
    value.as_table_mut().ok_or_else(|| {
        CliError::new(
            "CONFIG_ERROR",
            format!("config key {key} must be a table to install a backend"),
        )
    })
}

fn update_install_state(
    backend: BackendComponent,
    version: &str,
    install_dir: &Path,
    runtime_libs_dir: &Path,
    idea_home: Option<&Path>,
) -> Result<()> {
    let config = KastConfig::load_global()?;
    let mut install = self_mgmt::read_global_install_state()?.unwrap_or_else(default_install_state);
    let component = format!("backend:{}", backend.canonical());
    if !install.components.contains(&component) {
        install.components.push(component);
    }
    install
        .backends
        .retain(|state| state.name != backend.canonical());
    install.backends.push(BackendComponentState {
        name: backend.canonical().to_string(),
        version: version.to_string(),
        install_dir: install_dir.display().to_string(),
        runtime_libs_dir: runtime_libs_dir.display().to_string(),
        idea_home: idea_home.map(|path| path.display().to_string()),
    });
    for managed_path in [
        relative_or_display(&config.paths.install_root, install_dir),
        relative_or_display(&config.paths.install_root, &current_dir(&config, backend)),
    ] {
        if !install.managed_paths.contains(&managed_path) {
            install.managed_paths.push(managed_path);
        }
    }
    install.version = cli::version().to_string();
    install.installed_at = current_timestamp();
    install.platform = format!("{}-{}", env::consts::OS, env::consts::ARCH);
    self_mgmt::write_install_state(&install)?;
    Ok(())
}

fn default_install_state() -> InstallState {
    InstallState {
        version: cli::version().to_string(),
        backend_version: String::new(),
        installed_at: current_timestamp(),
        platform: format!("{}-{}", env::consts::OS, env::consts::ARCH),
        components: vec![],
        backends: vec![],
        managed_paths: vec![],
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: SCHEMA_VERSION,
    }
}

fn backend_asset_name(backend: BackendComponent, version: &str) -> String {
    match backend {
        BackendComponent::Standalone => format!("kast-standalone-{version}.zip"),
        BackendComponent::Headless => format!("kast-headless-{version}.zip"),
    }
}

fn layout(backend: BackendComponent) -> BackendLayout {
    match backend {
        BackendComponent::Standalone => BackendLayout {
            archive_root: "backend-standalone",
            install_name_prefix: "standalone",
            launcher: "kast-standalone",
        },
        BackendComponent::Headless => BackendLayout {
            archive_root: "backend-headless",
            install_name_prefix: "headless",
            launcher: "kast-headless",
        },
    }
}

fn install_dir(config: &KastConfig, backend: BackendComponent, version: &str) -> PathBuf {
    let name = format!("{}-{version}", layout(backend).install_name_prefix);
    match backend {
        BackendComponent::Standalone => config.paths.lib_dir.join("backends").join(name),
        BackendComponent::Headless => config.paths.lib_dir.join("backends/headless").join(name),
    }
}

fn current_dir(config: &KastConfig, backend: BackendComponent) -> PathBuf {
    match backend {
        BackendComponent::Standalone => config.paths.lib_dir.join("backends/current"),
        BackendComponent::Headless => config.paths.lib_dir.join("backends/headless/current"),
    }
}

fn release_tag(value: &str) -> String {
    let trimmed = value.trim();
    if trimmed.starts_with('v') {
        trimmed.to_string()
    } else {
        format!("v{trimmed}")
    }
}

fn temp_tree(prefix: &str) -> Result<TempTree> {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let path = env::temp_dir().join(format!("{prefix}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&path)?;
    Ok(TempTree { path })
}

fn copy_dir(source: &Path, target: &Path) -> Result<()> {
    fs::create_dir_all(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let source_path = entry.path();
        let target_path = target.join(entry.file_name());
        let file_type = entry.file_type()?;
        if file_type.is_dir() {
            copy_dir(&source_path, &target_path)?;
        } else if file_type.is_file() {
            fs::copy(&source_path, &target_path)?;
            let permissions = fs::metadata(&source_path)?.permissions();
            fs::set_permissions(&target_path, permissions)?;
        }
    }
    Ok(())
}

fn remove_path_recording(path: &Path, removed_paths: &mut Vec<String>) -> Result<()> {
    if remove_path(path)? {
        removed_paths.push(path.display().to_string());
    }
    Ok(())
}

fn remove_path(path: &Path) -> Result<bool> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error.into()),
    };
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        fs::remove_dir_all(path)?;
    } else {
        fs::remove_file(path)?;
    }
    Ok(true)
}

#[cfg(unix)]
fn link_current(target: &Path, current: &Path) -> Result<()> {
    std::os::unix::fs::symlink(target, current)?;
    Ok(())
}

#[cfg(not(unix))]
fn link_current(target: &Path, current: &Path) -> Result<()> {
    copy_dir(target, current)
}

fn relative_or_display(root: &Path, path: &Path) -> String {
    path.strip_prefix(root)
        .map(|relative| relative.display().to_string())
        .unwrap_or_else(|_| path.display().to_string())
}

fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    format!("unix:{seconds}")
}
