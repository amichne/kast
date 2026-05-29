use crate::SCHEMA_VERSION;
use crate::cli;
use crate::cli::{
    InstallArgs, InstallCommand, IntellijPluginInstallArgs, ResourceInstallArgs, UninstallArgs,
    UninstallCommand,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::self_mgmt;
use include_dir::{Dir, DirEntry, include_dir};
use serde::Serialize;
use serde_json::Value;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command as ProcessCommand, Output};
use std::time::{SystemTime, UNIX_EPOCH};

static KAST_SKILL: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/resources/kast-skill");
static COPILOT_EXTENSION: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/resources/copilot-extension");
static KAST_RPC_COMMANDS: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast-skill/references/commands.json"
));
const KAST_FORMULA_NAME: &str = "kast";
const KAST_PLUGIN_CASK_NAME: &str = "kast-plugin";
const DEFAULT_KAST_TAP: &str = "amichne/kast";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallSkillResult {
    pub installed_at: String,
    pub version: String,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallCopilotExtensionResult {
    pub installed_at: String,
    pub version: String,
    pub skipped: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallIntellijPluginResult {
    pub cask_token: String,
    pub brew_action: String,
    pub brew_command: Vec<String>,
    pub brew_prefix: String,
    pub formula_prefix: String,
    pub cli_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub download_dir: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub downloaded_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jetbrains_config_root: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub plugin_directories: Vec<String>,
    pub dry_run: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyExtensionResult {
    pub ok: bool,
    #[serde(rename = "cli_version")]
    pub cli_version: String,
    #[serde(rename = "extension_version")]
    pub extension_version: String,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum InstallResult {
    Skill(InstallSkillResult),
    Copilot(InstallCopilotExtensionResult),
    IntellijPlugin(InstallIntellijPluginResult),
    Archive(ArchiveInstallResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchiveInstallResult {
    pub installed_at: String,
    pub instance: String,
    pub skipped: bool,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum UninstallResult {
    SelfManaged(self_mgmt::SelfUninstallResult),
    Copilot(InstallCopilotExtensionResult),
}

pub fn install(args: InstallArgs) -> Result<InstallResult> {
    match args.command {
        Some(InstallCommand::Skill(resource_args)) => {
            install_skill(resource_args).map(InstallResult::Skill)
        }
        Some(InstallCommand::CopilotExtension(resource_args)) => {
            install_copilot_extension(resource_args).map(InstallResult::Copilot)
        }
        Some(InstallCommand::IntellijPlugin(resource_args)) => {
            install_intellij_plugin(resource_args).map(InstallResult::IntellijPlugin)
        }
        None => install_archive(args).map(InstallResult::Archive),
    }
}

pub fn uninstall(args: UninstallArgs) -> Result<UninstallResult> {
    match args.command {
        Some(UninstallCommand::CopilotExtension(resource_args)) => {
            uninstall_copilot_extension(resource_args).map(UninstallResult::Copilot)
        }
        None => self_mgmt::uninstall().map(UninstallResult::SelfManaged),
    }
}

pub fn install_skill(args: ResourceInstallArgs) -> Result<InstallSkillResult> {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_skill_target_dir);
    let name = args
        .name
        .or(args.link_name)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    let target = target_root.join(name);
    let skipped = install_dir(
        &KAST_SKILL,
        &target,
        ".kast-version",
        args.yes.unwrap_or(false),
    )?;
    Ok(InstallSkillResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        skipped,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_copilot_extension(
    args: ResourceInstallArgs,
) -> Result<InstallCopilotExtensionResult> {
    let target = args.target_dir.map(config::normalize).unwrap_or_else(|| {
        config::normalize(
            env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(".github"),
        )
    });
    let skipped = install_dir_preserving_target(
        &COPILOT_EXTENSION,
        &target,
        ".kast-copilot-version",
        args.yes.unwrap_or(false),
    )?;
    write_copilot_rpc_catalog(&target)?;
    self_mgmt::record_copilot_repo(&target, cli::version())?;
    Ok(InstallCopilotExtensionResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        skipped,
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_intellij_plugin(
    args: IntellijPluginInstallArgs,
) -> Result<InstallIntellijPluginResult> {
    let homebrew = discover_homebrew_context()?;
    verify_homebrew_cli(&homebrew)?;
    let mut warnings = vec![];
    let formula_tap = match homebrew_formula_tap() {
        Ok(tap) => tap,
        Err(error) => {
            warnings.push(format!(
                "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
                error.message
            ));
            DEFAULT_KAST_TAP.to_string()
        }
    };
    let cask_token = args
        .cask_token
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{formula_tap}/{KAST_PLUGIN_CASK_NAME}"));
    if args.link_jetbrains_profiles {
        return install_intellij_plugin_into_jetbrains_profiles(
            args, homebrew, cask_token, warnings,
        );
    }
    download_intellij_plugin(args, homebrew, cask_token, warnings)
}

fn download_intellij_plugin(
    args: IntellijPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    warnings: Vec<String>,
) -> Result<InstallIntellijPluginResult> {
    let download_dir = args
        .download_dir
        .map(config::normalize)
        .unwrap_or_else(default_download_dir);
    let brew_args = vec![
        "fetch".to_string(),
        "--cask".to_string(),
        "--force".to_string(),
        "--retry".to_string(),
        cask_token.clone(),
    ];
    let mut downloaded_path = None;
    if !args.dry_run {
        fs::create_dir_all(&download_dir)?;
        let output = run_brew_command(&brew_args)?;
        if !output.status.success() {
            return Err(command_error(
                "HOMEBREW_CASK_FETCH_FAILED",
                "Homebrew failed to fetch the Kast IntelliJ plugin cask",
                &brew_args,
                &output,
            ));
        }
        let cache_path = homebrew_cask_cache_path(&cask_token)?;
        let destination = download_destination(&cache_path, &download_dir)?;
        fs::copy(&cache_path, &destination)?;
        downloaded_path = Some(destination.display().to_string());
    }

    Ok(InstallIntellijPluginResult {
        cask_token,
        brew_action: "fetch".to_string(),
        brew_command: std::iter::once("brew".to_string())
            .chain(brew_args)
            .collect(),
        brew_prefix: homebrew.brew_prefix.display().to_string(),
        formula_prefix: homebrew.formula_prefix.display().to_string(),
        cli_path: homebrew.cli_path.display().to_string(),
        download_dir: Some(download_dir.display().to_string()),
        downloaded_path,
        jetbrains_config_root: None,
        plugin_directories: vec![],
        dry_run: args.dry_run,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn install_intellij_plugin_into_jetbrains_profiles(
    args: IntellijPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    warnings: Vec<String>,
) -> Result<InstallIntellijPluginResult> {
    let jetbrains_config_root = args
        .jetbrains_config_root
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    let plugin_directories = jetbrains_plugin_dirs(&jetbrains_config_root)?;
    if plugin_directories.is_empty() {
        let mut error = CliError::new(
            "JETBRAINS_CONFIG_NOT_FOUND",
            format!(
                "No JetBrains IDE profile directories were found under {}",
                jetbrains_config_root.display()
            ),
        );
        error.details.insert(
            "expectedRoot".to_string(),
            jetbrains_config_root.display().to_string(),
        );
        return Err(error);
    }

    let cask_name = cask_name(&cask_token);
    let cask_installed = homebrew_cask_installed(&cask_name)?;
    let brew_action = if cask_installed {
        "reinstall"
    } else {
        "install"
    };
    let brew_args = vec![
        brew_action.to_string(),
        "--cask".to_string(),
        cask_token.clone(),
    ];
    if !args.dry_run {
        let output = run_brew_with_jetbrains_root(&brew_args, &jetbrains_config_root)?;
        if !output.status.success() {
            return Err(command_error(
                "HOMEBREW_CASK_INSTALL_FAILED",
                "Homebrew failed to install the Kast IntelliJ plugin cask",
                &brew_args,
                &output,
            ));
        }
    }

    Ok(InstallIntellijPluginResult {
        cask_token,
        brew_action: brew_action.to_string(),
        brew_command: std::iter::once("brew".to_string())
            .chain(brew_args)
            .collect(),
        brew_prefix: homebrew.brew_prefix.display().to_string(),
        formula_prefix: homebrew.formula_prefix.display().to_string(),
        cli_path: homebrew.cli_path.display().to_string(),
        download_dir: None,
        downloaded_path: None,
        jetbrains_config_root: Some(jetbrains_config_root.display().to_string()),
        plugin_directories: plugin_directories
            .into_iter()
            .map(|path| path.display().to_string())
            .collect(),
        dry_run: args.dry_run,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn uninstall_copilot_extension(
    args: ResourceInstallArgs,
) -> Result<InstallCopilotExtensionResult> {
    let target = args.target_dir.map(config::normalize).unwrap_or_else(|| {
        config::normalize(
            env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(".github"),
        )
    });
    remove_embedded_files(&COPILOT_EXTENSION, &target)?;
    let shared_catalog = target.join("extensions/_shared/commands.json");
    if shared_catalog.is_file() {
        fs::remove_file(shared_catalog)?;
    }
    let marker = target.join(".kast-copilot-version");
    if marker.exists() {
        fs::remove_file(marker)?;
    }
    self_mgmt::forget_copilot_repo(&target)?;
    Ok(InstallCopilotExtensionResult {
        installed_at: target.display().to_string(),
        version: cli::version().to_string(),
        skipped: false,
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn verify_extension() -> Result<VerifyExtensionResult> {
    let marker = env::current_dir()?.join(".github/.kast-copilot-version");
    let extension_version = fs::read_to_string(marker)
        .unwrap_or_default()
        .trim()
        .to_string();
    let cli_version = cli::version().to_string();
    Ok(VerifyExtensionResult {
        ok: extension_version == cli_version,
        cli_version,
        extension_version,
    })
}

fn install_archive(args: InstallArgs) -> Result<ArchiveInstallResult> {
    let archive = args.archive.ok_or_else(|| {
        CliError::new(
            "CLI_USAGE",
            "`kast install` requires --archive or a resource subcommand",
        )
    })?;
    if !archive.is_file() {
        return Err(CliError::new(
            "INSTALL_ARCHIVE_NOT_FOUND",
            format!("Archive not found at {}", archive.display()),
        ));
    }
    let config = config::KastConfig::load_global()?;
    initialize_install_directories(&config)?;
    let install = self_mgmt::InstallState {
        version: cli::version().to_string(),
        backend_version: String::new(),
        installed_at: current_timestamp(),
        platform: format!("{}-{}", env::consts::OS, env::consts::ARCH),
        components: vec!["cli".to_string(), "config".to_string()],
        managed_paths: vec![
            "bin".to_string(),
            "lib".to_string(),
            "cache".to_string(),
            "logs".to_string(),
        ],
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: SCHEMA_VERSION,
    };
    self_mgmt::write_install_state(&install)?;
    Ok(ArchiveInstallResult {
        installed_at: config.paths.install_root.display().to_string(),
        instance: args.instance.unwrap_or_else(|| "default".to_string()),
        skipped: false,
        schema_version: SCHEMA_VERSION,
    })
}

fn initialize_install_directories(config: &config::KastConfig) -> Result<()> {
    fs::create_dir_all(&config.paths.install_root)?;
    fs::create_dir_all(&config.paths.bin_dir)?;
    fs::create_dir_all(&config.paths.lib_dir)?;
    fs::create_dir_all(&config.paths.cache_dir)?;
    fs::create_dir_all(&config.paths.logs_dir)?;
    Ok(())
}

fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    format!("unix:{seconds}")
}

fn write_copilot_rpc_catalog(target: &Path) -> Result<()> {
    let shared_catalog = target.join("extensions/_shared/commands.json");
    if let Some(parent) = shared_catalog.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(shared_catalog, KAST_RPC_COMMANDS)?;
    Ok(())
}

fn install_dir(dir: &Dir<'_>, target: &Path, marker_name: &str, force: bool) -> Result<bool> {
    let marker = target.join(marker_name);
    if marker.is_file() {
        let current = fs::read_to_string(&marker).unwrap_or_default();
        if current.trim() == cli::version() {
            return Ok(true);
        }
    }
    if target.exists() && !force {
        return Err(CliError::new(
            "INSTALL_TARGET_EXISTS",
            format!(
                "{} already exists. Pass --yes=true to replace it.",
                target.display()
            ),
        ));
    }
    if target.exists() {
        fs::remove_dir_all(target)?;
    }
    fs::create_dir_all(target)?;
    copy_dir_entries(dir, target)?;
    fs::write(marker, format!("{}\n", cli::version()))?;
    Ok(false)
}

fn install_dir_preserving_target(
    dir: &Dir<'_>,
    target: &Path,
    marker_name: &str,
    force: bool,
) -> Result<bool> {
    let marker = target.join(marker_name);
    if marker.is_file() {
        let current = fs::read_to_string(&marker).unwrap_or_default();
        if current.trim() == cli::version() {
            return Ok(true);
        }
    }
    if target.exists() && !force {
        return Err(CliError::new(
            "INSTALL_TARGET_EXISTS",
            format!(
                "{} already exists. Pass --yes=true to update packaged resources.",
                target.display()
            ),
        ));
    }
    fs::create_dir_all(target)?;
    copy_dir_entries(dir, target)?;
    fs::write(marker, format!("{}\n", cli::version()))?;
    Ok(false)
}

fn copy_dir_entries(dir: &Dir<'_>, target: &Path) -> Result<()> {
    for entry in dir.entries() {
        copy_entry(entry, target)?;
    }
    Ok(())
}

fn copy_entry(entry: &DirEntry<'_>, target_root: &Path) -> Result<()> {
    match entry {
        DirEntry::Dir(dir) => {
            let target = target_root.join(dir.path());
            fs::create_dir_all(&target)?;
            for child in dir.entries() {
                copy_entry(child, target_root)?;
            }
        }
        DirEntry::File(file) => {
            let target = target_root.join(file.path());
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(&target, file.contents())?;
            set_executable_if_script(&target)?;
        }
    }
    Ok(())
}

fn remove_embedded_files(dir: &Dir<'_>, target_root: &Path) -> Result<()> {
    for entry in dir.entries() {
        remove_entry(entry, target_root)?;
    }
    Ok(())
}

fn remove_entry(entry: &DirEntry<'_>, target_root: &Path) -> Result<()> {
    match entry {
        DirEntry::Dir(dir) => {
            for child in dir.entries() {
                remove_entry(child, target_root)?;
            }
            let target = target_root.join(dir.path());
            if target.is_dir() {
                let _ = fs::remove_dir(&target);
            }
        }
        DirEntry::File(file) => {
            let target = target_root.join(file.path());
            if target.is_file() {
                fs::remove_file(target)?;
            }
        }
    }
    Ok(())
}

#[cfg(unix)]
fn set_executable_if_script(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let executable = path
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| matches!(extension, "sh" | "py" | "mjs"));
    if executable {
        let mut permissions = fs::metadata(path)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_executable_if_script(_path: &Path) -> Result<()> {
    Ok(())
}

#[derive(Debug)]
struct HomebrewContext {
    brew_prefix: PathBuf,
    formula_prefix: PathBuf,
    cli_path: PathBuf,
}

#[derive(Debug)]
struct JetBrainsPluginDir {
    product: String,
    year: u32,
    minor: u32,
    patch: u32,
    path: PathBuf,
}

fn discover_homebrew_context() -> Result<HomebrewContext> {
    let brew_prefix = homebrew_prefix(&["--prefix"])?;
    let formula_prefix = homebrew_prefix(&["--prefix", KAST_FORMULA_NAME])?;
    let cli_path = env::current_exe()?;
    Ok(HomebrewContext {
        brew_prefix,
        formula_prefix,
        cli_path,
    })
}

fn verify_homebrew_cli(homebrew: &HomebrewContext) -> Result<()> {
    if path_is_below_homebrew_formula(&homebrew.cli_path, &homebrew.formula_prefix) {
        return Ok(());
    }
    let mut error = CliError::new(
        "HOMEBREW_INSTALL_REQUIRED",
        format!(
            "`kast install intellij-plugin` must be run from the Homebrew-installed kast binary under {}",
            homebrew.formula_prefix.display()
        ),
    );
    error.details.insert(
        "cliPath".to_string(),
        homebrew.cli_path.display().to_string(),
    );
    error.details.insert(
        "formulaPrefix".to_string(),
        homebrew.formula_prefix.display().to_string(),
    );
    Err(error)
}

fn homebrew_prefix(args: &[&str]) -> Result<PathBuf> {
    let output = run_brew(args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_PREFIX_FAILED",
            "Homebrew did not report the expected install prefix",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let prefix = stdout.trim();
    if prefix.is_empty() {
        return Err(CliError::new(
            "HOMEBREW_PREFIX_FAILED",
            "Homebrew returned an empty install prefix",
        ));
    }
    Ok(PathBuf::from(prefix))
}

fn homebrew_formula_tap() -> Result<String> {
    let args = ["info", "--json=v2", KAST_FORMULA_NAME];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_TAP_LOOKUP_FAILED",
            "Homebrew did not report metadata for the kast formula",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    parse_homebrew_formula_tap(&stdout).ok_or_else(|| {
        CliError::new(
            "HOMEBREW_TAP_LOOKUP_FAILED",
            "Homebrew metadata did not include a tap for the kast formula",
        )
    })
}

fn parse_homebrew_formula_tap(json: &str) -> Option<String> {
    let value: Value = serde_json::from_str(json).ok()?;
    value
        .get("formulae")?
        .as_array()?
        .first()?
        .get("tap")?
        .as_str()
        .map(str::trim)
        .filter(|tap| !tap.is_empty())
        .map(str::to_string)
}

fn homebrew_cask_installed(cask_name: &str) -> Result<bool> {
    let output = run_brew(&["list", "--cask", cask_name])?;
    Ok(output.status.success())
}

fn homebrew_cask_cache_path(cask_token: &str) -> Result<PathBuf> {
    let args = vec![
        "--cache".to_string(),
        "--cask".to_string(),
        cask_token.to_string(),
    ];
    let output = run_brew_command(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_CASK_CACHE_FAILED",
            "Homebrew did not report the cask cache path",
            &args,
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let path = stdout.trim();
    if path.is_empty() {
        return Err(CliError::new(
            "HOMEBREW_CASK_CACHE_FAILED",
            "Homebrew returned an empty cask cache path",
        ));
    }
    Ok(PathBuf::from(path))
}

fn run_brew(args: &[&str]) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command.args(args);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn run_brew_command(args: &[String]) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command.args(args);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn run_brew_with_jetbrains_root(args: &[String], jetbrains_config_root: &Path) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command
        .args(args)
        .env("KAST_JETBRAINS_CONFIG_ROOT", jetbrains_config_root);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn command_error(code: &'static str, message: &str, args: &[String], output: &Output) -> CliError {
    let mut error = CliError::new(code, message);
    error
        .details
        .insert("command".to_string(), format!("brew {}", args.join(" ")));
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !stdout.is_empty() {
        error.details.insert("stdout".to_string(), stdout);
    }
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if !stderr.is_empty() {
        error.details.insert("stderr".to_string(), stderr);
    }
    error
}

fn path_is_below_homebrew_formula(path: &Path, formula_prefix: &Path) -> bool {
    let canonical_path = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let canonical_formula_prefix =
        fs::canonicalize(formula_prefix).unwrap_or_else(|_| formula_prefix.to_path_buf());
    canonical_path.starts_with(&canonical_formula_prefix) || path.starts_with(formula_prefix)
}

fn default_jetbrains_config_root() -> PathBuf {
    config::home_dir().join("Library/Application Support/JetBrains")
}

fn default_download_dir() -> PathBuf {
    config::home_dir().join("Downloads")
}

fn download_destination(cache_path: &Path, download_dir: &Path) -> Result<PathBuf> {
    let file_name = cache_path
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .ok_or_else(|| {
            CliError::new(
                "HOMEBREW_CASK_CACHE_FAILED",
                format!(
                    "Homebrew cache path has no file name: {}",
                    cache_path.display()
                ),
            )
        })?;
    let artifact_name = file_name
        .split_once("--")
        .map(|(_, artifact_name)| artifact_name)
        .unwrap_or(file_name);
    Ok(download_dir.join(artifact_name))
}

fn jetbrains_plugin_dirs(root: &Path) -> Result<Vec<PathBuf>> {
    if !root.is_dir() {
        return Ok(vec![]);
    }
    let mut dirs = vec![];
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let Some((product, year, minor, patch)) = parse_jetbrains_profile_name(name) else {
            continue;
        };
        dirs.push(JetBrainsPluginDir {
            product,
            year,
            minor,
            patch,
            path: path.join("plugins"),
        });
    }
    dirs.sort_by(|left, right| {
        left.product
            .cmp(&right.product)
            .then_with(|| right.year.cmp(&left.year))
            .then_with(|| right.minor.cmp(&left.minor))
            .then_with(|| right.patch.cmp(&left.patch))
            .then_with(|| {
                left.path
                    .display()
                    .to_string()
                    .cmp(&right.path.display().to_string())
            })
    });
    Ok(dirs.into_iter().map(|dir| dir.path).collect())
}

fn parse_jetbrains_profile_name(name: &str) -> Option<(String, u32, u32, u32)> {
    let (version_start, _) = name.char_indices().find(|(_, ch)| ch.is_ascii_digit())?;
    let product = &name[..version_start];
    let first = product.chars().next()?;
    if !first.is_ascii_alphabetic() || !product.chars().all(|ch| ch.is_ascii_alphanumeric()) {
        return None;
    }
    let rest = &name[version_start..];
    let mut parts = rest.split('.');
    let year = parse_fixed_digits(parts.next()?, 4)?;
    let minor = parse_digits(parts.next()?)?;
    let patch = match parts.next() {
        Some(value) => parse_digits(value)?,
        None => 0,
    };
    if parts.next().is_some() {
        return None;
    }
    Some((product.to_string(), year, minor, patch))
}

fn parse_fixed_digits(value: &str, len: usize) -> Option<u32> {
    if value.len() != len {
        return None;
    }
    parse_digits(value)
}

fn parse_digits(value: &str) -> Option<u32> {
    if value.is_empty() || !value.chars().all(|ch| ch.is_ascii_digit()) {
        return None;
    }
    value.parse().ok()
}

fn cask_name(cask_token: &str) -> String {
    cask_token
        .rsplit('/')
        .next()
        .unwrap_or(cask_token)
        .to_string()
}

fn default_skill_target_dir() -> PathBuf {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for candidate in [".agents/skills", ".github/skills", ".claude/skills"] {
        let path = cwd.join(candidate);
        if path.is_dir() {
            return config::normalize(path);
        }
    }
    config::home_dir().join(".kast/lib/skills")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn install_skill_writes_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            link_name: None,
            yes: Some(false),
        };
        let first = install_skill(args.clone()).unwrap();
        assert!(!first.skipped);
        assert!(temp.path().join("kast/SKILL.md").is_file());
        let second = install_skill(args).unwrap();
        assert!(second.skipped);
    }

    #[test]
    fn jetbrains_plugin_dirs_match_cask_profile_filter() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path();
        for dir in [
            "IntelliJIdea2025.2",
            "IntelliJIdea2026.2",
            "GoLand2024.2",
            "PyCharmCE2024.1",
            "IntelliJIdea2025.2-backup/2025-07-27-00-54",
            "Toolbox",
            "IntelliJIdea2025.2/plugins/python-ce/helpers/typeshed/stubs/flake8/flake8",
        ] {
            fs::create_dir_all(root.join(dir)).unwrap();
        }

        let dirs = jetbrains_plugin_dirs(root).unwrap();
        let relative: Vec<_> = dirs
            .iter()
            .map(|path| path.strip_prefix(root).unwrap().display().to_string())
            .collect();

        assert_eq!(
            relative,
            vec![
                "GoLand2024.2/plugins",
                "IntelliJIdea2026.2/plugins",
                "IntelliJIdea2025.2/plugins",
                "PyCharmCE2024.1/plugins",
            ]
        );
    }

    #[test]
    fn parses_homebrew_formula_tap() {
        let json = r#"{"formulae":[{"name":"kast","tap":"amichne/kast"}],"casks":[]}"#;
        assert_eq!(
            parse_homebrew_formula_tap(json).as_deref(),
            Some("amichne/kast")
        );
    }

    #[test]
    fn cask_name_uses_last_token_segment() {
        assert_eq!(cask_name("amichne/kast/kast-plugin"), "kast-plugin");
        assert_eq!(cask_name("kast-plugin"), "kast-plugin");
    }

    #[test]
    fn download_destination_strips_homebrew_cache_hash() {
        let destination = download_destination(
            Path::new(
                "/Users/example/Library/Caches/Homebrew/downloads/hash--kast-intellij-v0.7.26.zip",
            ),
            Path::new("/Users/example/Downloads"),
        )
        .unwrap();

        assert_eq!(
            destination,
            Path::new("/Users/example/Downloads/kast-intellij-v0.7.26.zip")
        );
    }

    #[test]
    fn homebrew_formula_path_check_accepts_cellar_binary() {
        let prefix = Path::new("/opt/homebrew/Cellar/kast/0.7.16");
        let cli = Path::new("/opt/homebrew/Cellar/kast/0.7.16/bin/kast");

        assert!(path_is_below_homebrew_formula(cli, prefix));
        assert!(!path_is_below_homebrew_formula(
            Path::new("/Users/example/kast/target/release/kast"),
            prefix
        ));
    }
}
