use crate::SCHEMA_VERSION;
use crate::backend::{self, BackendResult};
use crate::cli;
use crate::cli::{
    BackendComponent, BackendInstallArgs, CurrentCommand, HeadlessInstallArgs,
    IdeaPluginInstallArgs, InstallArgs, InstallCommand, ResourceCurrentArgs, ResourceInstallArgs,
    ShellInstallArgs, ShellKind, UninstallArgs, UninstallCommand,
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
const COPILOT_EXTENSION_DIR: &str = "extensions/kast";
const COPILOT_EXTENSION_MARKER: &str = ".kast-copilot-version";
const SHELL_BLOCK_START: &str = "# >>> kast shell integration >>>";
const SHELL_BLOCK_END: &str = "# <<< kast shell integration <<<";
const LEGACY_COPILOT_HOOK_FILES: &[&str] = &[
    "export-session.py",
    "hook-state.sh",
    "hooks.json",
    "record-paths.sh",
    "require-skills.sh",
    "resolve-kast-path.sh",
    "session-end.sh",
    "session-start.sh",
    "skill-shadowing.json",
];

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
pub struct InstallIdeaPluginResult {
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
pub struct InstallShellResult {
    pub shell: String,
    pub command_name: String,
    pub bin_dir: String,
    pub config_home: String,
    pub source_file: String,
    pub profile: String,
    pub profile_updated: bool,
    pub dry_run: bool,
    pub source_line: String,
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
    IdeaPlugin(InstallIdeaPluginResult),
    Shell(InstallShellResult),
    Headless(backend::BackendInstallResult),
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CurrentComponentResult {
    pub component: String,
    pub installed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cask_token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install_dir: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_dir: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime_libs_dir: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub idea_home: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

pub fn install(args: InstallArgs) -> Result<InstallResult> {
    match args.command {
        Some(InstallCommand::Headless(headless_args)) => {
            install_headless(headless_args).map(InstallResult::Headless)
        }
        Some(InstallCommand::Skill(resource_args)) => {
            install_skill(resource_args).map(InstallResult::Skill)
        }
        Some(InstallCommand::Copilot(resource_args)) => {
            install_copilot_extension(resource_args).map(InstallResult::Copilot)
        }
        Some(InstallCommand::Plugin(resource_args)) => {
            install_idea_plugin(resource_args).map(InstallResult::IdeaPlugin)
        }
        Some(InstallCommand::Shell(shell_args)) => {
            install_shell(shell_args).map(InstallResult::Shell)
        }
        Some(InstallCommand::Completion(_)) => Err(CliError::new(
            "CLI_USAGE",
            "`kast install completion` must be handled as raw completion output",
        )),
        None => install_archive(args).map(InstallResult::Archive),
    }
}

pub fn current(command: CurrentCommand) -> Result<CurrentComponentResult> {
    match command {
        CurrentCommand::Plugin => current_plugin(),
        CurrentCommand::Headless => current_headless(),
        CurrentCommand::Skill(args) => {
            current_resource("skill", current_skill_target(args), ".kast-version", vec![])
        }
        CurrentCommand::Copilot(args) => current_resource(
            "copilot",
            current_copilot_target(args),
            COPILOT_EXTENSION_MARKER,
            vec![],
        ),
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

fn current_plugin() -> Result<CurrentComponentResult> {
    let mut warnings = vec![];
    let cask_token = match homebrew_formula_tap() {
        Ok(tap) => format!("{tap}/{KAST_PLUGIN_CASK_NAME}"),
        Err(error) => {
            warnings.push(format!(
                "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
                error.message
            ));
            format!("{DEFAULT_KAST_TAP}/{KAST_PLUGIN_CASK_NAME}")
        }
    };
    let cask_name = cask_name(&cask_token);
    let version = match homebrew_cask_version(&cask_name) {
        Ok(version) => version,
        Err(error) => {
            warnings.push(format!(
                "Could not resolve the installed Homebrew cask version for {cask_name}: {}",
                error.message
            ));
            None
        }
    };
    Ok(CurrentComponentResult {
        component: "plugin".to_string(),
        installed: version.is_some(),
        version,
        cask_token: Some(cask_token),
        install_dir: None,
        target_dir: None,
        runtime_libs_dir: None,
        idea_home: None,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn install_headless(args: HeadlessInstallArgs) -> Result<backend::BackendInstallResult> {
    let backend_args = BackendInstallArgs {
        backend: BackendComponent::Headless,
        archive: args.archive,
        version: args.version,
        base_url: args.base_url,
        force: args.force,
        yes: args.yes,
    };
    match backend::run(cli::BackendCommand::Install(backend_args))? {
        BackendResult::Install(result) => Ok(result),
        BackendResult::Uninstall(_) => Err(CliError::new(
            "CLI_USAGE",
            "install headless unexpectedly returned an uninstall result",
        )),
    }
}

fn current_headless() -> Result<CurrentComponentResult> {
    let backend = self_mgmt::read_global_install_state()?.and_then(|install| {
        install
            .backends
            .into_iter()
            .find(|backend| backend.name == BackendComponent::Headless.canonical())
    });
    Ok(match backend {
        Some(backend) => CurrentComponentResult {
            component: "headless".to_string(),
            installed: true,
            version: Some(backend.version),
            cask_token: None,
            install_dir: Some(backend.install_dir),
            target_dir: None,
            runtime_libs_dir: Some(backend.runtime_libs_dir),
            idea_home: backend.idea_home,
            warnings: vec![],
            schema_version: SCHEMA_VERSION,
        },
        None => CurrentComponentResult {
            component: "headless".to_string(),
            installed: false,
            version: None,
            cask_token: None,
            install_dir: None,
            target_dir: None,
            runtime_libs_dir: None,
            idea_home: None,
            warnings: vec![],
            schema_version: SCHEMA_VERSION,
        },
    })
}

fn current_resource(
    component: &str,
    target: PathBuf,
    marker_name: &str,
    warnings: Vec<String>,
) -> Result<CurrentComponentResult> {
    let marker = target.join(marker_name);
    let version = marker
        .is_file()
        .then(|| {
            fs::read_to_string(&marker)
                .unwrap_or_default()
                .trim()
                .to_string()
        })
        .filter(|value| !value.is_empty());
    Ok(CurrentComponentResult {
        component: component.to_string(),
        installed: version.is_some(),
        version,
        cask_token: None,
        install_dir: None,
        target_dir: Some(target.display().to_string()),
        runtime_libs_dir: None,
        idea_home: None,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn current_skill_target(args: ResourceCurrentArgs) -> PathBuf {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_skill_target_dir);
    let name = args
        .name
        .or(args.link_name)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    target_root.join(name)
}

fn current_copilot_target(args: ResourceCurrentArgs) -> PathBuf {
    let target = args.target_dir.map(config::normalize).unwrap_or_else(|| {
        config::normalize(
            env::current_dir()
                .unwrap_or_else(|_| PathBuf::from("."))
                .join(".github"),
        )
    });
    target.join(COPILOT_EXTENSION_DIR)
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
        args.force || args.yes.unwrap_or(false),
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
    let extension_target = target.join(COPILOT_EXTENSION_DIR);
    let skipped = install_copilot_extension_dir(&extension_target, args.force)?;
    write_copilot_rpc_catalog(&extension_target)?;
    self_mgmt::record_copilot_repo(&target, cli::version())?;
    Ok(InstallCopilotExtensionResult {
        installed_at: extension_target.display().to_string(),
        version: cli::version().to_string(),
        skipped,
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_idea_plugin(args: IdeaPluginInstallArgs) -> Result<InstallIdeaPluginResult> {
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
        return install_idea_plugin_into_jetbrains_profiles(args, homebrew, cask_token, warnings);
    }
    download_idea_plugin(args, homebrew, cask_token, warnings)
}

pub fn install_shell(args: ShellInstallArgs) -> Result<InstallShellResult> {
    let config = config::KastConfig::load_global()?;
    let config_home = config::kast_config_home();
    let shell = args.shell.map(Ok).unwrap_or_else(detect_shell)?;
    let command_name = args
        .command_name
        .unwrap_or_else(default_shell_command_name)
        .trim()
        .to_string();
    validate_shell_command_name(&command_name)?;
    let source_file = args.source_file.map(config::normalize).unwrap_or_else(|| {
        config_home
            .join("shell")
            .join(format!("{command_name}.{}", shell.extension()))
    });
    let profile = args
        .profile
        .map(config::normalize)
        .unwrap_or_else(|| default_shell_profile(shell));
    let source_line = format!("source {}", shell_quote_path(&source_file));
    let source_content =
        shell_source_content(shell, &command_name, &config.paths.bin_dir, &config_home);

    if !args.dry_run {
        if let Some(parent) = source_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&source_file, source_content)?;
    }
    let profile_updated = patch_shell_profile(&profile, &source_line, args.dry_run)?;

    Ok(InstallShellResult {
        shell: shell.canonical().to_string(),
        command_name,
        bin_dir: config.paths.bin_dir.display().to_string(),
        config_home: config_home.display().to_string(),
        source_file: source_file.display().to_string(),
        profile: profile.display().to_string(),
        profile_updated,
        dry_run: args.dry_run,
        source_line,
        schema_version: SCHEMA_VERSION,
    })
}

fn detect_shell() -> Result<ShellKind> {
    let shell = env::var_os("SHELL")
        .and_then(|value| PathBuf::from(value).file_name().map(|name| name.to_owned()))
        .and_then(|name| name.to_str().map(str::to_string))
        .unwrap_or_default();
    match shell.as_str() {
        "bash" => Ok(ShellKind::Bash),
        "zsh" => Ok(ShellKind::Zsh),
        _ => Err(CliError::new(
            "CLI_USAGE",
            "Could not infer a supported shell from SHELL. Pass `kast install shell --shell bash` or `--shell zsh`.",
        )),
    }
}

fn default_shell_command_name() -> String {
    env::current_exe()
        .ok()
        .and_then(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .map(str::to_string)
        })
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

fn default_shell_profile(shell: ShellKind) -> PathBuf {
    match shell {
        ShellKind::Bash => config::home_dir().join(".bashrc"),
        ShellKind::Zsh => config::home_dir().join(".zshrc"),
    }
}

fn validate_shell_command_name(command_name: &str) -> Result<()> {
    let valid = !command_name.is_empty()
        && command_name
            .chars()
            .all(|char| char.is_ascii_alphanumeric() || matches!(char, '-' | '_' | '.' | '+'));
    if valid {
        return Ok(());
    }
    let mut error = CliError::new(
        "CLI_USAGE",
        "Shell command name must contain only ASCII letters, digits, dash, underscore, dot, or plus.",
    );
    error
        .details
        .insert("commandName".to_string(), command_name.to_string());
    Err(error)
}

fn shell_source_content(
    shell: ShellKind,
    command_name: &str,
    bin_dir: &Path,
    config_home: &Path,
) -> String {
    format!(
        r#"# Managed by `kast install shell`; re-run that command after moving Kast.
export KAST_CONFIG_HOME={}
_kast_bin_dir={}
case ":${{PATH}}:" in
  *":${{_kast_bin_dir}}:"*) ;;
  *) export PATH="${{_kast_bin_dir}}:${{PATH}}" ;;
esac
unset _kast_bin_dir

if command -v {command_name} >/dev/null 2>&1; then
  source <({command_name} install completion {} --command-name {command_name})
fi
"#,
        shell_quote(&config_home.display().to_string()),
        shell_quote(&bin_dir.display().to_string()),
        shell.canonical(),
    )
}

fn patch_shell_profile(profile: &Path, source_line: &str, dry_run: bool) -> Result<bool> {
    let block = format!(
        "{SHELL_BLOCK_START}\n# Managed by `kast install shell`; edit the generated source file instead.\n{source_line}\n{SHELL_BLOCK_END}\n"
    );
    let original = match fs::read_to_string(profile) {
        Ok(content) => content,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    let updated = replace_managed_block(&original, &block);
    if updated == original {
        return Ok(false);
    }
    if !dry_run {
        if let Some(parent) = profile.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(profile, updated)?;
    }
    Ok(true)
}

fn replace_managed_block(original: &str, block: &str) -> String {
    if let Some(start) = original.find(SHELL_BLOCK_START)
        && let Some(end_offset) = original[start..].find(SHELL_BLOCK_END)
    {
        let end = start + end_offset + SHELL_BLOCK_END.len();
        let mut updated = String::new();
        updated.push_str(&original[..start]);
        updated.push_str(block);
        updated.push_str(original[end..].trim_start_matches('\n'));
        return updated;
    }
    let mut updated = original.to_string();
    if !updated.is_empty() && !updated.ends_with('\n') {
        updated.push('\n');
    }
    if !updated.is_empty() {
        updated.push('\n');
    }
    updated.push_str(block);
    updated
}

fn shell_quote_path(path: &Path) -> String {
    shell_quote(&path.display().to_string())
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

fn download_idea_plugin(
    args: IdeaPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    warnings: Vec<String>,
) -> Result<InstallIdeaPluginResult> {
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
        eprintln!("Fetching Kast IDEA plugin cask {cask_token} with Homebrew...");
        let output = run_brew_command(&brew_args)?;
        if !output.status.success() {
            return Err(command_error(
                "HOMEBREW_CASK_FETCH_FAILED",
                "Homebrew failed to fetch the Kast IDEA plugin cask",
                &brew_args,
                &output,
            ));
        }
        let cache_path = homebrew_cask_cache_path(&cask_token)?;
        let destination = download_destination(&cache_path, &download_dir)?;
        fs::copy(&cache_path, &destination)?;
        eprintln!("Downloaded Kast IDEA plugin to {}", destination.display());
        downloaded_path = Some(destination.display().to_string());
    }

    Ok(InstallIdeaPluginResult {
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

fn install_idea_plugin_into_jetbrains_profiles(
    args: IdeaPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    warnings: Vec<String>,
) -> Result<InstallIdeaPluginResult> {
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
    let mut brew_args = vec![
        brew_action.to_string(),
        "--cask".to_string(),
        cask_token.clone(),
    ];
    if args.force {
        brew_args.insert(2, "--force".to_string());
    }
    if !args.dry_run {
        let output = run_brew_with_jetbrains_root(&brew_args, &jetbrains_config_root)?;
        if !output.status.success() {
            return Err(command_error(
                "HOMEBREW_CASK_INSTALL_FAILED",
                "Homebrew failed to install the Kast IDEA plugin cask",
                &brew_args,
                &output,
            ));
        }
    }

    Ok(InstallIdeaPluginResult {
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
    let extension_target = target.join(COPILOT_EXTENSION_DIR);
    let shared_catalog = extension_target.join("_shared/commands.json");
    if shared_catalog.is_file() {
        fs::remove_file(shared_catalog)?;
    }
    if let Some(source) = COPILOT_EXTENSION.get_dir(COPILOT_EXTENSION_DIR) {
        remove_embedded_files_stripped(source, source.path(), &extension_target)?;
    }
    let marker = extension_target.join(COPILOT_EXTENSION_MARKER);
    if marker.exists() {
        fs::remove_file(marker)?;
    }
    let _ = fs::remove_dir(&extension_target);
    remove_legacy_copilot_hooks(&target)?;
    let legacy_marker = target.join(COPILOT_EXTENSION_MARKER);
    if legacy_marker.exists() {
        fs::remove_file(legacy_marker)?;
    }
    self_mgmt::forget_copilot_repo(&target)?;
    Ok(InstallCopilotExtensionResult {
        installed_at: extension_target.display().to_string(),
        version: cli::version().to_string(),
        skipped: false,
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn verify_extension() -> Result<VerifyExtensionResult> {
    let marker = env::current_dir()?
        .join(".github")
        .join(COPILOT_EXTENSION_DIR)
        .join(COPILOT_EXTENSION_MARKER);
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
        backends: vec![],
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
    let shared_catalog = target.join("_shared/commands.json");
    if let Some(parent) = shared_catalog.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(shared_catalog, KAST_RPC_COMMANDS)?;
    Ok(())
}

fn install_copilot_extension_dir(target: &Path, force: bool) -> Result<bool> {
    let marker = target.join(COPILOT_EXTENSION_MARKER);
    let skipped = !force
        && marker
            .is_file()
            .then(|| fs::read_to_string(&marker).unwrap_or_default())
            .is_some_and(|current| current.trim() == cli::version());
    let source = COPILOT_EXTENSION
        .get_dir(COPILOT_EXTENSION_DIR)
        .ok_or_else(|| {
            CliError::new(
                "INSTALL_RESOURCE_MISSING",
                format!("Packaged Copilot extension directory {COPILOT_EXTENSION_DIR} is missing"),
            )
        })?;
    fs::create_dir_all(target)?;
    copy_dir_entries_stripped(source, source.path(), target)?;
    fs::write(marker, format!("{}\n", cli::version()))?;
    Ok(skipped)
}

fn install_dir(dir: &Dir<'_>, target: &Path, marker_name: &str, force: bool) -> Result<bool> {
    let marker = target.join(marker_name);
    if marker.is_file() {
        let current = fs::read_to_string(&marker).unwrap_or_default();
        if !force && current.trim() == cli::version() {
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

fn copy_dir_entries(dir: &Dir<'_>, target: &Path) -> Result<()> {
    for entry in dir.entries() {
        copy_entry(entry, target)?;
    }
    Ok(())
}

fn copy_dir_entries_stripped(dir: &Dir<'_>, strip_prefix: &Path, target: &Path) -> Result<()> {
    for entry in dir.entries() {
        copy_entry_stripped(entry, strip_prefix, target)?;
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

fn copy_entry_stripped(
    entry: &DirEntry<'_>,
    strip_prefix: &Path,
    target_root: &Path,
) -> Result<()> {
    match entry {
        DirEntry::Dir(dir) => {
            let relative = dir.path().strip_prefix(strip_prefix).map_err(|error| {
                CliError::new(
                    "INSTALL_RESOURCE_PATH_INVALID",
                    format!(
                        "Packaged resource path {} is not under {}: {error}",
                        dir.path().display(),
                        strip_prefix.display()
                    ),
                )
            })?;
            let target = target_root.join(relative);
            fs::create_dir_all(&target)?;
            for child in dir.entries() {
                copy_entry_stripped(child, strip_prefix, target_root)?;
            }
        }
        DirEntry::File(file) => {
            let relative = file.path().strip_prefix(strip_prefix).map_err(|error| {
                CliError::new(
                    "INSTALL_RESOURCE_PATH_INVALID",
                    format!(
                        "Packaged resource path {} is not under {}: {error}",
                        file.path().display(),
                        strip_prefix.display()
                    ),
                )
            })?;
            let target = target_root.join(relative);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(&target, file.contents())?;
            set_executable_if_script(&target)?;
        }
    }
    Ok(())
}

fn remove_embedded_files_stripped(
    dir: &Dir<'_>,
    strip_prefix: &Path,
    target_root: &Path,
) -> Result<()> {
    for entry in dir.entries() {
        remove_entry_stripped(entry, strip_prefix, target_root)?;
    }
    Ok(())
}

fn remove_entry_stripped(
    entry: &DirEntry<'_>,
    strip_prefix: &Path,
    target_root: &Path,
) -> Result<()> {
    match entry {
        DirEntry::Dir(dir) => {
            for child in dir.entries() {
                remove_entry_stripped(child, strip_prefix, target_root)?;
            }
            let relative = dir.path().strip_prefix(strip_prefix).map_err(|error| {
                CliError::new(
                    "INSTALL_RESOURCE_PATH_INVALID",
                    format!(
                        "Packaged resource path {} is not under {}: {error}",
                        dir.path().display(),
                        strip_prefix.display()
                    ),
                )
            })?;
            let target = target_root.join(relative);
            if target.is_dir() {
                let _ = fs::remove_dir(&target);
            }
        }
        DirEntry::File(file) => {
            let relative = file.path().strip_prefix(strip_prefix).map_err(|error| {
                CliError::new(
                    "INSTALL_RESOURCE_PATH_INVALID",
                    format!(
                        "Packaged resource path {} is not under {}: {error}",
                        file.path().display(),
                        strip_prefix.display()
                    ),
                )
            })?;
            let target = target_root.join(relative);
            if target.is_file() {
                fs::remove_file(target)?;
            }
        }
    }
    Ok(())
}

fn remove_legacy_copilot_hooks(target_root: &Path) -> Result<()> {
    let hooks_dir = target_root.join("hooks");
    for file_name in LEGACY_COPILOT_HOOK_FILES {
        let path = hooks_dir.join(file_name);
        if path.is_file() {
            fs::remove_file(path)?;
        }
    }
    let _ = fs::remove_dir(&hooks_dir);
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
            "`kast install plugin` must be run from the Homebrew-installed kast binary under {}",
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

fn homebrew_cask_version(cask_name: &str) -> Result<Option<String>> {
    let output = run_brew(&["list", "--cask", "--versions", cask_name])?;
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(parse_homebrew_cask_version(&stdout, cask_name))
}

fn parse_homebrew_cask_version(output: &str, cask_name: &str) -> Option<String> {
    output.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        let name = parts.next()?;
        if name != cask_name {
            return None;
        }
        let version = parts.collect::<Vec<_>>().join(" ");
        (!version.trim().is_empty()).then_some(version)
    })
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
            force: false,
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
            "AndroidStudio2025.2",
            "AndroidStudio2026.2",
            "GoLand2024.2",
            "PyCharmCE2024.1",
            "AndroidStudio2025.2-backup/2025-07-27-00-54",
            "Toolbox",
            "AndroidStudio2025.2/plugins/python-ce/helpers/typeshed/stubs/flake8/flake8",
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
                "AndroidStudio2026.2/plugins",
                "AndroidStudio2025.2/plugins",
                "GoLand2024.2/plugins",
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
                "/Users/example/Library/Caches/Homebrew/downloads/hash--kast-idea-v0.7.26.zip",
            ),
            Path::new("/Users/example/Downloads"),
        )
        .unwrap();

        assert_eq!(
            destination,
            Path::new("/Users/example/Downloads/kast-idea-v0.7.26.zip")
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
