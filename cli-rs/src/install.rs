use crate::SCHEMA_VERSION;
use crate::backend::{self, BackendResult};
use crate::cli;
use crate::cli::{
    AffectedInstallArgs, BackendComponent, BackendInstallArgs, HeadlessInstallArgs,
    IdeaPluginInstallArgs, InstallArgs, InstallCommand, ResourceInstallArgs, SetupArgs,
    ShellInstallArgs, ShellKind,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::self_mgmt;
use include_dir::{Dir, DirEntry, include_dir};
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeSet;
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
pub struct InstallAffectedAction {
    pub kind: String,
    pub target: String,
    pub status: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallAffectedResult {
    pub applied: bool,
    pub config_path: String,
    pub apply_command: String,
    pub actions: Vec<InstallAffectedAction>,
    pub backups: Vec<String>,
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupResult {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub repair: Option<InstallAffectedResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub headless: Option<backend::BackendInstallResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shell: Option<InstallShellResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skill: Option<InstallSkillResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub copilot: Option<InstallCopilotExtensionResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub idea_plugin: Option<InstallIdeaPluginResult>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum InstallResult {
    Skill(InstallSkillResult),
    Copilot(InstallCopilotExtensionResult),
    IdeaPlugin(InstallIdeaPluginResult),
    Shell(InstallShellResult),
    Affected(InstallAffectedResult),
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

pub fn install(args: InstallArgs) -> Result<InstallResult> {
    match args.command {
        Some(InstallCommand::Headless(headless_args)) => {
            install_headless(headless_args).map(InstallResult::Headless)
        }
        Some(InstallCommand::Affected(affected_args)) => {
            install_affected(affected_args).map(InstallResult::Affected)
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

pub fn setup(args: SetupArgs) -> Result<SetupResult> {
    let repair = if args.skip_repair {
        None
    } else {
        Some(install_affected(AffectedInstallArgs {
            apply: true,
            jetbrains_config_root: args.jetbrains_config_root.clone(),
        })?)
    };
    let headless_present = if args.skip_headless {
        false
    } else {
        setup_headless_present()?
    };
    if !args.skip_headless
        && !headless_present
        && (args.headless_archive.is_some() || args.version.is_some() || args.base_url.is_some())
    {
        return Err(CliError::new(
            "CLI_USAGE",
            "`kast setup` does not add a new headless backend. Run `kast install headless` for first-time headless backend installation.",
        ));
    }
    let headless = if args.skip_headless || !headless_present {
        None
    } else {
        Some(install_headless(HeadlessInstallArgs {
            archive: args.headless_archive,
            version: args.version.clone(),
            base_url: args.base_url,
            insecure_skip_tls_verify: false,
            force: args.force,
        })?)
    };
    let shell = if args.skip_shell {
        None
    } else {
        Some(install_shell(ShellInstallArgs {
            shell: args.shell,
            profile: None,
            source_file: None,
            command_name: None,
            dry_run: false,
        })?)
    };
    let skill = if args.include_skill && !args.skip_skill {
        Some(install_skill(ResourceInstallArgs {
            target_dir: args.skill_target_dir,
            name: None,
            force: args.force,
        })?)
    } else {
        None
    };
    let copilot = if args.include_copilot && !args.skip_copilot {
        Some(install_copilot_extension(ResourceInstallArgs {
            target_dir: args.copilot_target_dir,
            name: None,
            force: args.force,
        })?)
    } else {
        None
    };
    let link_jetbrains_profiles = args.link_jetbrains_profiles
        || setup_detected_jetbrains_profiles(&args.jetbrains_config_root)?;
    let idea_plugin = if link_jetbrains_profiles && !args.skip_plugin {
        Some(install_idea_plugin(IdeaPluginInstallArgs {
            jetbrains_config_root: args.jetbrains_config_root,
            link_jetbrains_profiles: true,
            cask_token: None,
            force: args.force,
            dry_run: false,
        })?)
    } else {
        None
    };
    Ok(SetupResult {
        repair,
        headless,
        shell,
        skill,
        copilot,
        idea_plugin,
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

fn setup_detected_jetbrains_profiles(jetbrains_config_root: &Option<PathBuf>) -> Result<bool> {
    if !cfg!(target_os = "macos") {
        return Ok(false);
    }
    let root = jetbrains_config_root
        .clone()
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    Ok(!jetbrains_plugin_dirs(&root)?.is_empty())
}

fn setup_headless_present() -> Result<bool> {
    if self_mgmt::read_global_install_state()?.is_some_and(|install| {
        install.backends.into_iter().any(|backend| {
            backend.name == BackendComponent::Headless.canonical()
                && Path::new(&backend.runtime_libs_dir)
                    .join("classpath.txt")
                    .is_file()
                && path_exists_or_symlink(Path::new(&backend.install_dir))
        })
    }) {
        return Ok(true);
    }
    let global_config = config::KastConfig::load_global()?;
    Ok(global_config
        .backends
        .headless
        .runtime_libs_dir
        .is_some_and(|runtime_libs_dir| runtime_libs_dir.join("classpath.txt").is_file()))
}

pub fn repair_if_running_cli_version_changed() -> Result<Option<InstallAffectedResult>> {
    let Some(install) = self_mgmt::read_global_install_state()? else {
        return Ok(None);
    };
    if install.version.trim() == cli::version() {
        return Ok(None);
    }
    install_affected(AffectedInstallArgs {
        apply: true,
        jetbrains_config_root: None,
    })
    .map(Some)
}

pub fn install_headless(args: HeadlessInstallArgs) -> Result<backend::BackendInstallResult> {
    let backend_args = BackendInstallArgs {
        backend: BackendComponent::Headless,
        archive: args.archive,
        version: args.version,
        base_url: args.base_url,
        insecure_skip_tls_verify: args.insecure_skip_tls_verify,
        force: args.force,
    };
    match backend::run(cli::BackendCommand::Install(backend_args))? {
        BackendResult::Install(result) => Ok(result),
    }
}

pub fn install_affected(args: AffectedInstallArgs) -> Result<InstallAffectedResult> {
    let config_path = config::global_config_path();
    let backup_root = config::kast_config_home()
        .join("backups")
        .join(format!("install-affected-{}", backup_timestamp()));
    let mut result = InstallAffectedResult {
        applied: args.apply,
        config_path: config_path.display().to_string(),
        apply_command: "kast install affected --apply".to_string(),
        actions: vec![],
        backups: vec![],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    };
    let mut config_backed_up = false;

    if !config_path.is_file() {
        push_affected_action(
            &mut result,
            "provision-config",
            &config_path,
            "Create the global Kast config from current defaults.",
            None,
        );
        if args.apply {
            config::init_config()?;
        }
    }

    let global_config = config::KastConfig::load_global()?;
    repair_affected_config_state(
        &args,
        &global_config,
        &mut result,
        &backup_root,
        &mut config_backed_up,
    )?;
    repair_affected_skill_targets(&args, &mut result, &backup_root)?;
    repair_affected_copilot_repos(&args, &mut result, &backup_root)?;
    repair_affected_shell_sources(&args, &mut result, &backup_root)?;
    repair_affected_jetbrains_profiles(&args, &mut result, &backup_root)?;

    Ok(result)
}

fn repair_global_config_for_running_cli(apply: bool) -> Result<()> {
    let config_path = config::global_config_path();
    let backup_root = config::kast_config_home()
        .join("backups")
        .join(format!("install-affected-{}", backup_timestamp()));
    let mut result = InstallAffectedResult {
        applied: apply,
        config_path: config_path.display().to_string(),
        apply_command: "kast install affected --apply".to_string(),
        actions: vec![],
        backups: vec![],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    };
    let mut config_backed_up = false;

    if !config_path.is_file() {
        push_affected_action(
            &mut result,
            "provision-config",
            &config_path,
            "Create the global Kast config from current defaults.",
            None,
        );
        if apply {
            config::init_config()?;
        }
    }

    let global_config = config::KastConfig::load_global()?;
    repair_affected_config_state(
        &AffectedInstallArgs {
            apply,
            jetbrains_config_root: None,
        },
        &global_config,
        &mut result,
        &backup_root,
        &mut config_backed_up,
    )
}

fn repair_affected_config_state(
    args: &AffectedInstallArgs,
    global_config: &config::KastConfig,
    result: &mut InstallAffectedResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<()> {
    let config_path = config::global_config_path();
    let document = read_toml_document(&config_path)?;
    let remove_standalone_table = document
        .get("backends")
        .and_then(toml::Value::as_table)
        .is_some_and(|backends| backends.contains_key("standalone"));
    let current_exe = env::current_exe()?;
    let cli_binary_update = document
        .get("cli")
        .and_then(toml::Value::as_table)
        .and_then(|cli| cli.get("binaryPath"))
        .and_then(toml::Value::as_str)
        .map(PathBuf::from)
        .filter(|path| should_update_cli_binary_path(path, &current_exe))
        .map(|path| (path, current_exe.clone()));

    if remove_standalone_table {
        push_affected_action(
            result,
            "remove-retired-backend-config",
            &config_path,
            "Remove the retired standalone backend config table while preserving supported settings.",
            None,
        );
    }
    if let Some((old_path, new_path)) = &cli_binary_update {
        push_affected_action(
            result,
            "update-cli-binary-path",
            old_path,
            &format!(
                "Update stale cli.binaryPath to the running kast binary at {}.",
                new_path.display()
            ),
            None,
        );
    }
    if args.apply && (remove_standalone_table || cli_binary_update.is_some()) {
        backup_config_once(result, backup_root, config_backed_up)?;
        self_mgmt::update_global_config(|document| {
            if remove_standalone_table
                && let Some(toml::Value::Table(backends)) = document.get_mut("backends")
            {
                backends.remove("standalone");
            }
            if let Some((_, new_path)) = &cli_binary_update {
                let cli = document
                    .entry("cli".to_string())
                    .or_insert_with(|| toml::Value::Table(toml::Table::new()));
                let toml::Value::Table(cli) = cli else {
                    return Err(CliError::new(
                        "CONFIG_UPDATE_FAILED",
                        "The `cli` config value is not a TOML table.",
                    ));
                };
                cli.insert(
                    "binaryPath".to_string(),
                    toml::Value::String(new_path.display().to_string()),
                );
            }
            Ok(())
        })?;
    }

    let Some(mut install) = self_mgmt::read_global_install_state()? else {
        return Ok(());
    };
    let mut install_changed = false;
    if install.version.trim() != cli::version() {
        push_affected_action(
            result,
            "update-install-version",
            &config_path,
            &format!(
                "Record the running kast CLI version {} in install metadata.",
                cli::version()
            ),
            None,
        );
        install_changed = true;
    }

    let mut surviving_backends = vec![];
    for backend in install.backends {
        let unsupported = backend.name != BackendComponent::Headless.canonical();
        let classpath_missing = !Path::new(&backend.runtime_libs_dir)
            .join("classpath.txt")
            .is_file();
        let install_dir_missing = !path_exists_or_symlink(Path::new(&backend.install_dir));
        if unsupported || classpath_missing || install_dir_missing {
            let reason = if unsupported {
                format!(
                    "Remove retired {} backend state from install metadata.",
                    backend.name
                )
            } else {
                format!(
                    "Remove backend state whose runtime files are missing at {}.",
                    backend.runtime_libs_dir
                )
            };
            push_affected_action(
                result,
                "remove-stale-backend-state",
                Path::new(&backend.install_dir),
                &reason,
                Some("kast install headless".to_string()),
            );
            if args.apply {
                let install_dir = Path::new(&backend.install_dir);
                backup_existing_path(install_dir, backup_root, result)?;
                remove_existing_path(install_dir)?;
            }
            install_changed = true;
        } else {
            surviving_backends.push(backend);
        }
    }
    install.backends = surviving_backends;

    let surviving_backend_components = install
        .backends
        .iter()
        .map(|backend| format!("backend:{}", backend.name))
        .collect::<BTreeSet<_>>();
    let original_components = install.components.clone();
    install.components.retain(|component| {
        !component.starts_with("backend:") || surviving_backend_components.contains(component)
    });
    for component in original_components {
        if !install.components.contains(&component) {
            push_affected_action(
                result,
                "remove-stale-component-state",
                Path::new(&component),
                "Remove install metadata for a backend component that is no longer present.",
                Some("kast install headless".to_string()),
            );
            install_changed = true;
        }
    }

    let original_managed_paths = std::mem::take(&mut install.managed_paths);
    for managed_path_value in original_managed_paths {
        let managed = managed_install_path(&global_config.paths.install_root, &managed_path_value);
        if !path_exists_or_symlink(&managed) {
            push_affected_action(
                result,
                "prune-missing-managed-path",
                &managed,
                "Remove a missing managed path from install metadata.",
                None,
            );
            install_changed = true;
            continue;
        }
        if managed_path_value.contains("standalone") || managed_path_value == "lib/backends/current"
        {
            push_affected_action(
                result,
                "remove-retired-managed-path",
                &managed,
                "Back up and remove a retired managed backend path.",
                None,
            );
            if args.apply {
                backup_existing_path(&managed, backup_root, result)?;
                remove_existing_path(&managed)?;
            }
            install_changed = true;
            continue;
        }
        install.managed_paths.push(managed_path_value);
    }

    let mut seen_repos = BTreeSet::new();
    let mut deduped_repos = vec![];
    for repo in install.repos {
        let normalized = config::normalize(PathBuf::from(&repo.path));
        let normalized_value = normalized.display().to_string();
        if seen_repos.insert(normalized_value.clone()) {
            deduped_repos.push(self_mgmt::ManagedRepo {
                path: normalized_value,
                copilot_extension_version: repo.copilot_extension_version,
            });
        } else {
            push_affected_action(
                result,
                "dedupe-managed-repo",
                &normalized,
                "Remove duplicate managed repo install metadata.",
                None,
            );
            install_changed = true;
        }
    }
    install.repos = deduped_repos;

    if install_changed {
        install.version = cli::version().to_string();
        install.installed_at = current_timestamp();
        install.platform = format!("{}-{}", env::consts::OS, env::consts::ARCH);
    }
    if args.apply && install_changed {
        backup_config_once(result, backup_root, config_backed_up)?;
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
    }

    Ok(())
}

fn repair_affected_skill_targets(
    args: &AffectedInstallArgs,
    result: &mut InstallAffectedResult,
    backup_root: &Path,
) -> Result<()> {
    for target_root in affected_skill_target_roots()? {
        let target = target_root.join("kast");
        if !target.is_dir() {
            continue;
        }
        let marker = target.join(".kast-version");
        let installed_version = fs::read_to_string(&marker).unwrap_or_default();
        if installed_version.trim() == cli::version() {
            continue;
        }
        push_affected_action(
            result,
            "refresh-skill",
            &target,
            "Back up and refresh a stale installed Kast skill.",
            Some(format!(
                "kast install skill --target-dir {} --force",
                shell_quote_path(&target_root)
            )),
        );
        if args.apply {
            backup_existing_path(&target, backup_root, result)?;
            install_skill(ResourceInstallArgs {
                target_dir: Some(target_root),
                name: Some("kast".to_string()),
                force: true,
            })?;
        }
    }
    Ok(())
}

fn repair_affected_copilot_repos(
    args: &AffectedInstallArgs,
    result: &mut InstallAffectedResult,
    backup_root: &Path,
) -> Result<()> {
    let Some(install) = self_mgmt::read_global_install_state()? else {
        return Ok(());
    };
    let mut seen = BTreeSet::new();
    for repo in install.repos {
        let repo_root = config::normalize(PathBuf::from(repo.path));
        if !seen.insert(repo_root.display().to_string()) {
            continue;
        }
        let github_dir = repo_root.join(".github");
        let extension_target = github_dir.join(COPILOT_EXTENSION_DIR);
        let marker = extension_target.join(COPILOT_EXTENSION_MARKER);
        let installed_version = fs::read_to_string(&marker).unwrap_or_default();
        if installed_version.trim() == cli::version() {
            continue;
        }
        push_affected_action(
            result,
            "refresh-copilot-extension",
            &extension_target,
            "Back up and refresh a stale managed Copilot extension install.",
            Some(format!(
                "kast install copilot --target-dir {} --force",
                shell_quote_path(&github_dir)
            )),
        );
        if args.apply {
            backup_existing_path(&extension_target, backup_root, result)?;
            remove_existing_path(&extension_target)?;
            install_copilot_extension(ResourceInstallArgs {
                target_dir: Some(github_dir),
                name: None,
                force: true,
            })?;
        }
    }
    Ok(())
}

fn repair_affected_shell_sources(
    args: &AffectedInstallArgs,
    result: &mut InstallAffectedResult,
    backup_root: &Path,
) -> Result<()> {
    let shell_dir = config::kast_config_home().join("shell");
    if !shell_dir.is_dir() {
        return Ok(());
    }
    let mut entries = fs::read_dir(&shell_dir)?.collect::<std::result::Result<Vec<_>, _>>()?;
    entries.sort_by_key(|entry| entry.path());
    for entry in entries {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(shell) = path
            .extension()
            .and_then(|extension| extension.to_str())
            .and_then(|extension| match extension {
                "bash" => Some(ShellKind::Bash),
                "zsh" => Some(ShellKind::Zsh),
                _ => None,
            })
        else {
            continue;
        };
        let Some(command_name) = path
            .file_stem()
            .and_then(|stem| stem.to_str())
            .filter(|name| !name.trim().is_empty())
        else {
            continue;
        };
        validate_shell_command_name(command_name)?;
        let content = fs::read_to_string(&path)?;
        if !content.contains("Managed by `kast install shell`") {
            continue;
        }
        let Some(bin_dir) = resolve_command_bin_dir(command_name)? else {
            result.warnings.push(format!(
                "Could not resolve `{command_name}` on PATH; leaving managed shell source {} unchanged",
                path.display()
            ));
            continue;
        };
        if content.contains(&format!(
            "_kast_bin_dir={}",
            shell_quote(&bin_dir.display().to_string())
        )) {
            continue;
        }
        push_affected_action(
            result,
            "refresh-shell-source",
            &path,
            &format!(
                "Back up and rewrite managed shell integration for `{command_name}` to use {}.",
                bin_dir.display()
            ),
            Some("kast install affected --apply".to_string()),
        );
        if args.apply {
            backup_existing_path(&path, backup_root, result)?;
            fs::write(
                &path,
                shell_source_content(shell, command_name, &bin_dir, &config::kast_config_home()),
            )?;
        }
    }
    Ok(())
}

fn repair_affected_jetbrains_profiles(
    args: &AffectedInstallArgs,
    result: &mut InstallAffectedResult,
    backup_root: &Path,
) -> Result<()> {
    let Some(expected_plugin_target) = expected_homebrew_plugin_target(result)? else {
        return Ok(());
    };
    let jetbrains_config_root = args
        .jetbrains_config_root
        .clone()
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    for plugin_dir in jetbrains_plugin_dirs(&jetbrains_config_root)? {
        let plugin_link = plugin_dir.join("kast");
        if !path_exists_or_symlink(&plugin_link) {
            continue;
        }
        if fs::read_link(&plugin_link)
            .ok()
            .is_some_and(|target| target == expected_plugin_target)
        {
            continue;
        }
        push_affected_action(
            result,
            "refresh-idea-plugin-link",
            &plugin_link,
            &format!(
                "Back up and relink a stale IDEA or Android Studio profile plugin to {}.",
                expected_plugin_target.display()
            ),
            Some("kast install plugin --force".to_string()),
        );
        if args.apply {
            backup_existing_path(&plugin_link, backup_root, result)?;
            remove_existing_path(&plugin_link)?;
            if let Some(parent) = plugin_link.parent() {
                fs::create_dir_all(parent)?;
            }
            create_plugin_link(&expected_plugin_target, &plugin_link, &mut result.warnings)?;
        }
    }
    Ok(())
}

fn push_affected_action(
    result: &mut InstallAffectedResult,
    kind: &str,
    target: &Path,
    message: &str,
    command: Option<String>,
) {
    result.actions.push(InstallAffectedAction {
        kind: kind.to_string(),
        target: target.display().to_string(),
        status: if result.applied { "applied" } else { "planned" }.to_string(),
        message: message.to_string(),
        command,
    });
}

fn backup_config_once(
    result: &mut InstallAffectedResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<()> {
    if *config_backed_up {
        return Ok(());
    }
    backup_existing_path(&config::global_config_path(), backup_root, result)?;
    *config_backed_up = true;
    Ok(())
}

fn backup_existing_path(
    path: &Path,
    backup_root: &Path,
    result: &mut InstallAffectedResult,
) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    fs::create_dir_all(backup_root)?;
    let backup_path = backup_root.join(format!(
        "{:03}-{}",
        result.backups.len() + 1,
        backup_label(path)
    ));
    if metadata.file_type().is_symlink() {
        let target = fs::read_link(path)?;
        fs::write(
            &backup_path,
            format!("symlink {}\n", target.display()).as_bytes(),
        )?;
    } else if metadata.is_file() {
        fs::copy(path, &backup_path)?;
    } else if metadata.is_dir() {
        copy_path_recursive(path, &backup_path)?;
    }
    result.backups.push(backup_path.display().to_string());
    Ok(())
}

fn copy_path_recursive(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        let link_target = fs::read_link(source)?;
        fs::write(
            target,
            format!("symlink {}\n", link_target.display()).as_bytes(),
        )?;
    } else if metadata.is_file() {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(source, target)?;
    } else if metadata.is_dir() {
        fs::create_dir_all(target)?;
        for entry in fs::read_dir(source)? {
            let entry = entry?;
            copy_path_recursive(&entry.path(), &target.join(entry.file_name()))?;
        }
    }
    Ok(())
}

fn backup_label(path: &Path) -> String {
    let raw = path
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("path");
    let sanitized = raw
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '_'
            }
        })
        .collect::<String>();
    if sanitized.is_empty() {
        "path".to_string()
    } else {
        sanitized
    }
}

fn remove_existing_path(path: &Path) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    if metadata.file_type().is_symlink() || metadata.is_file() {
        fs::remove_file(path)?;
    } else if metadata.is_dir() {
        fs::remove_dir_all(path)?;
    }
    Ok(())
}

fn path_exists_or_symlink(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok()
}

pub(crate) fn kast_idea_plugin_installed() -> Result<bool> {
    let jetbrains_config_root = env::var_os("KAST_JETBRAINS_CONFIG_ROOT")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    kast_idea_plugin_installed_under(&jetbrains_config_root)
}

pub(crate) fn kast_idea_plugin_installed_under(jetbrains_config_root: &Path) -> Result<bool> {
    Ok(jetbrains_plugin_dirs(jetbrains_config_root)?
        .into_iter()
        .any(|plugin_dir| path_exists_or_symlink(&plugin_dir.join("kast"))))
}

fn should_update_cli_binary_path(path: &Path, current_exe: &Path) -> bool {
    if !path_exists_or_symlink(path) {
        return true;
    }
    if path == current_exe {
        return false;
    }
    let value = path.display().to_string();
    value.contains("/.kast/bin/kast") || value.contains("/Cellar/kast/")
}

fn managed_install_path(install_root: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        install_root.join(path)
    }
}

fn read_toml_document(path: &Path) -> Result<toml::Table> {
    if !path.is_file() {
        return Ok(toml::Table::new());
    }
    Ok(fs::read_to_string(path)?.parse::<toml::Table>()?)
}

fn affected_skill_target_roots() -> Result<Vec<PathBuf>> {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    let home = config::home_dir();
    let mut roots = vec![
        default_skill_target_dir(),
        home.join(".codex/skills"),
        home.join(".agents/skills"),
        home.join(".kast/lib/skills"),
        cwd.join(".agents/skills"),
        cwd.join(".github/skills"),
        cwd.join(".claude/skills"),
    ];
    let mut seen = BTreeSet::new();
    roots.retain(|path| seen.insert(config::normalize(path.clone()).display().to_string()));
    Ok(roots.into_iter().map(config::normalize).collect())
}

fn resolve_command_bin_dir(command_name: &str) -> Result<Option<PathBuf>> {
    let current_exe = env::current_exe()?;
    if current_exe
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == command_name)
    {
        return Ok(current_exe.parent().map(Path::to_path_buf));
    }
    let output = ProcessCommand::new("which").arg(command_name).output();
    let Ok(output) = output else {
        return Ok(None);
    };
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let command_path = stdout
        .lines()
        .next()
        .map(str::trim)
        .filter(|line| !line.is_empty());
    Ok(command_path
        .map(PathBuf::from)
        .and_then(|path| path.parent().map(Path::to_path_buf)))
}

fn expected_homebrew_plugin_target(result: &mut InstallAffectedResult) -> Result<Option<PathBuf>> {
    expected_homebrew_plugin_target_with_warnings(&mut result.warnings)
}

fn expected_homebrew_plugin_target_with_warnings(
    warnings: &mut Vec<String>,
) -> Result<Option<PathBuf>> {
    let brew_prefix = match homebrew_prefix(&["--prefix"]) {
        Ok(value) => value,
        Err(error) => {
            warnings.push(format!(
                "Could not resolve Homebrew prefix; skipping JetBrains plugin link repair: {}",
                error.message
            ));
            return Ok(None);
        }
    };
    let formula_tap = homebrew_formula_tap().unwrap_or_else(|error| {
        warnings.push(format!(
            "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
            error.message
        ));
        DEFAULT_KAST_TAP.to_string()
    });
    let cask_token = format!("{formula_tap}/{KAST_PLUGIN_CASK_NAME}");
    let cask_name = cask_name(&cask_token);
    expected_homebrew_plugin_target_for_cask(&cask_name, &brew_prefix, warnings)
}

fn expected_homebrew_plugin_target_for_cask(
    cask_name: &str,
    brew_prefix: &Path,
    warnings: &mut Vec<String>,
) -> Result<Option<PathBuf>> {
    let Some(version) = homebrew_cask_version(cask_name)? else {
        warnings.push(format!(
            "Homebrew cask {cask_name} is not installed; skipping JetBrains plugin link repair"
        ));
        return Ok(None);
    };
    Ok(Some(
        brew_prefix
            .join("Caskroom")
            .join(cask_name)
            .join(version)
            .join("backend-idea"),
    ))
}

#[cfg(unix)]
fn create_plugin_link(source: &Path, target: &Path, _warnings: &mut Vec<String>) -> Result<()> {
    std::os::unix::fs::symlink(source, target)?;
    Ok(())
}

#[cfg(not(unix))]
fn create_plugin_link(_source: &Path, target: &Path, warnings: &mut Vec<String>) -> Result<()> {
    warnings.push(format!(
        "Cannot create JetBrains plugin symlink on this platform; left {} unchanged",
        target.display()
    ));
    Ok(())
}

fn backup_timestamp() -> String {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs().to_string())
        .unwrap_or_else(|_| "0".to_string())
}

pub fn install_skill(args: ResourceInstallArgs) -> Result<InstallSkillResult> {
    let target_root = args
        .target_dir
        .map(config::normalize)
        .unwrap_or_else(default_skill_target_dir);
    let name = args
        .name
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string());
    let target = target_root.join(name);
    let skipped = install_dir(&KAST_SKILL, &target, ".kast-version", args.force)?;
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
    repair_global_config_for_running_cli(!args.dry_run)?;
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
    install_idea_plugin_into_jetbrains_profiles(args, homebrew, cask_token, warnings)
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
    let bin_dir = shell_integration_bin_dir(&command_name, &config.paths.bin_dir)?;
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
    let source_content = shell_source_content(shell, &command_name, &bin_dir, &config_home);

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
        bin_dir: bin_dir.display().to_string(),
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

fn shell_integration_bin_dir(command_name: &str, configured_bin_dir: &Path) -> Result<PathBuf> {
    let current_exe = env::current_exe()?;
    if current_exe
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == command_name)
        && let Some(parent) = current_exe.parent()
    {
        return Ok(parent.to_path_buf());
    }
    Ok(resolve_command_bin_dir(command_name)?.unwrap_or_else(|| configured_bin_dir.to_path_buf()))
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

fn install_idea_plugin_into_jetbrains_profiles(
    args: IdeaPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    mut warnings: Vec<String>,
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
        ensure_homebrew_plugin_profile_links(
            &homebrew,
            &cask_name,
            &plugin_directories,
            &mut warnings,
        )?;
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

fn ensure_homebrew_plugin_profile_links(
    homebrew: &HomebrewContext,
    cask_name: &str,
    plugin_directories: &[PathBuf],
    warnings: &mut Vec<String>,
) -> Result<()> {
    let Some(expected_plugin_target) =
        expected_homebrew_plugin_target_for_cask(cask_name, &homebrew.brew_prefix, warnings)?
    else {
        return Ok(());
    };
    for plugin_dir in plugin_directories {
        let plugin_link = plugin_dir.join("kast");
        ensure_homebrew_plugin_profile_link(&expected_plugin_target, &plugin_link, warnings)?;
    }
    Ok(())
}

fn ensure_homebrew_plugin_profile_link(
    expected_plugin_target: &Path,
    plugin_link: &Path,
    warnings: &mut Vec<String>,
) -> Result<()> {
    if fs::read_link(plugin_link)
        .ok()
        .is_some_and(|target| target == expected_plugin_target)
    {
        return Ok(());
    }
    if path_exists_or_symlink(plugin_link) {
        let Some(current_target) = fs::read_link(plugin_link).ok() else {
            warnings.push(format!(
                "Not replacing existing JetBrains plugin path {}; run `kast install affected --apply` for backed-up repair",
                plugin_link.display()
            ));
            return Ok(());
        };
        if !current_target
            .display()
            .to_string()
            .contains("/Caskroom/kast-plugin/")
            && !current_target
                .display()
                .to_string()
                .contains("/kast-plugin/")
        {
            warnings.push(format!(
                "Not replacing existing JetBrains plugin link {} -> {}; run `kast install affected --apply` for backed-up repair",
                plugin_link.display(),
                current_target.display()
            ));
            return Ok(());
        }
        remove_existing_path(plugin_link)?;
    }
    if let Some(parent) = plugin_link.parent() {
        fs::create_dir_all(parent)?;
    }
    create_plugin_link(expected_plugin_target, plugin_link, warnings)?;
    Ok(())
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
                "{} already exists. Pass --force to replace it.",
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
            force: false,
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
