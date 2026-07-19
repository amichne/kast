use crate::cli::{MachineActivateArgs, MachineReconcileArgs};
use crate::error::{CliError, Result};
use serde::{Deserialize, Serialize};
use std::fmt;
use std::fs;
use std::io;
use std::path::Component;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum MachineState {
    NotInstalled,
    Installed,
}

impl fmt::Display for MachineState {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::NotInstalled => "not installed",
            Self::Installed => "installed",
        })
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MachineStatus {
    #[serde(rename = "type")]
    status_type: &'static str,
    pub(crate) state: MachineState,
    active: bool,
    schema_version: u32,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
#[serde(rename_all = "camelCase")]
struct MachineManifest {
    #[serde(rename = "type")]
    manifest_type: String,
    cli_sha256: String,
    task_launcher_sha256: String,
    idea_plugin_sha256: String,
    resources_sha256: String,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MachineActivation {
    #[serde(rename = "type")]
    activation_type: &'static str,
    state: &'static str,
    pub(crate) cli: String,
    task_launcher: String,
    idea_plugin: String,
    skill: String,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MachineReconciliation {
    #[serde(rename = "type")]
    reconciliation_type: &'static str,
    state: &'static str,
    pub(crate) idea_plugin: String,
    pub(crate) skill: String,
    pub(crate) codex: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    hook_trust: Option<CodexHookTrustRequirement>,
    quarantined_plugin: Option<String>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CodexHookTrustRequirement {
    code: &'static str,
    trusted: bool,
    action: &'static str,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct CodexHookState {
    #[serde(rename = "type")]
    state_type: String,
    machine_identity: String,
    trusted: bool,
    schema_version: u32,
}

pub(crate) fn status() -> Result<MachineStatus> {
    let root = machine_root();
    let installed = root.join("machine.json").is_file();
    let active = active_machine_identity()?.is_some();
    Ok(MachineStatus {
        status_type: "KAST_MACHINE_STATUS",
        state: if installed {
            MachineState::Installed
        } else {
            MachineState::NotInstalled
        },
        active,
        schema_version: 1,
    })
}

pub(crate) fn active_machine_identity() -> Result<Option<String>> {
    let root = machine_root();
    let manifest_path = root.join("machine.json");
    if !manifest_path.is_file() {
        return Ok(None);
    }
    validate_machine_install(&root)?;
    let running = fs::canonicalize(std::env::current_exe()?)?;
    let installed = fs::canonicalize(root.join("bin/kast"))?;
    if running != installed {
        return Ok(None);
    }
    Ok(Some(crate::manifest::sha256_file(&manifest_path)?))
}

pub(crate) fn activate(args: MachineActivateArgs) -> Result<MachineActivation> {
    let source_cli = std::env::current_exe()?;
    require_regular_file(&source_cli, "running Kast CLI")?;
    require_regular_file(&args.idea_plugin, "Kast IDEA plugin ZIP")?;

    let root = machine_root();
    if root.exists() && !root.join("machine.json").is_file() {
        return Err(CliError::new(
            "MACHINE_INSTALL_BLOCKED",
            format!(
                "Refusing to replace unrecognized machine state at {}.",
                root.display()
            ),
        ));
    }
    let parent = root.parent().ok_or_else(|| {
        CliError::new(
            "MACHINE_INSTALL_PATH_INVALID",
            "Machine root has no parent.",
        )
    })?;
    fs::create_dir_all(parent)?;
    let transaction = uuid::Uuid::new_v4();
    let staging = parent.join(format!(".machine-staging-{transaction}"));
    let backup = parent.join(format!(".machine-backup-{transaction}"));
    fs::create_dir_all(staging.join("bin"))?;
    fs::create_dir_all(staging.join("idea"))?;
    fs::create_dir_all(staging.join("resources"))?;

    let installed_cli = staging.join("bin/kast");
    fs::copy(&source_cli, &installed_cli)?;
    fs::set_permissions(&installed_cli, fs::metadata(&source_cli)?.permissions())?;
    let installed_task_launcher = staging.join("bin/kast-agent-task");
    fs::write(
        &installed_task_launcher,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/resources/agent-task/kast-agent-task"
        )),
    )?;
    make_executable(&installed_task_launcher)?;
    let installed_plugin = staging.join("idea/kast.zip");
    fs::copy(&args.idea_plugin, &installed_plugin)?;
    let resources = staging.join("resources");
    let installed_skill = resources.join("kast-skill/SKILL.md");
    fs::create_dir_all(installed_skill.parent().expect("Kast skill parent"))?;
    fs::write(
        &installed_skill,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/resources/kast-skill/SKILL.md"
        )),
    )?;
    write_agent_task_resources(&resources.join("agent-task"))?;
    let installed_codex = resources.join("codex-marketplace");
    write_codex_marketplace(&installed_codex)?;
    write_copilot_plugin(&resources.join("copilot-plugin"))?;
    let manifest = MachineManifest {
        manifest_type: "KAST_MACHINE_MANIFEST".to_string(),
        cli_sha256: crate::manifest::sha256_file(&installed_cli)?,
        task_launcher_sha256: crate::manifest::sha256_file(&installed_task_launcher)?,
        idea_plugin_sha256: crate::manifest::sha256_file(&installed_plugin)?,
        resources_sha256: directory_sha256(&resources)?,
        schema_version: 2,
    };
    fs::write(
        staging.join("machine.json"),
        serde_json::to_vec_pretty(&manifest)?,
    )?;

    if root.exists() {
        fs::rename(&root, &backup)?;
    }
    if let Err(error) = fs::rename(&staging, &root) {
        if backup.exists() {
            let _ = fs::rename(&backup, &root);
        }
        return Err(error.into());
    }
    if backup.exists() {
        fs::remove_dir_all(&backup)?;
    }
    replace_stable_command("kast", &root.join("bin/kast"))?;
    replace_stable_command("kast-agent-task", &root.join("bin/kast-agent-task"))?;

    Ok(MachineActivation {
        activation_type: "KAST_MACHINE_ACTIVATION",
        state: "ACTIVATED",
        cli: root.join("bin/kast").display().to_string(),
        task_launcher: root.join("bin/kast-agent-task").display().to_string(),
        idea_plugin: root.join("idea/kast.zip").display().to_string(),
        skill: root
            .join("resources/kast-skill/SKILL.md")
            .display()
            .to_string(),
        schema_version: 2,
    })
}

pub(crate) fn reconcile(args: MachineReconcileArgs) -> Result<MachineReconciliation> {
    let root = machine_root();
    validate_machine_install(&root)?;
    require_jetbrains_ides_closed()?;
    let plugins = match args.idea_plugins_dir {
        Some(path) => path,
        None => default_idea_plugins_dir()?,
    };
    if !plugins.is_absolute() {
        return Err(CliError::new(
            "IDE_PROFILE_INVALID",
            format!(
                "IDE plugins directory must be absolute: {}",
                plugins.display()
            ),
        ));
    }
    fs::create_dir_all(&plugins)?;
    let transaction = uuid::Uuid::new_v4();
    let staging = plugins.join(format!(".kast-staging-{transaction}"));
    let installed_plugin = plugins.join("kast");
    extract_plugin_zip(&root.join("idea/kast.zip"), &staging)?;

    let quarantined_plugin = if fs::symlink_metadata(&installed_plugin).is_ok() {
        let quarantine = root.join("quarantine").join(format!("{transaction}-kast"));
        fs::create_dir_all(quarantine.parent().expect("quarantine parent"))?;
        fs::rename(&installed_plugin, &quarantine)?;
        Some(quarantine)
    } else {
        None
    };
    if let Err(error) = fs::rename(&staging, &installed_plugin) {
        if let Some(quarantine) = &quarantined_plugin {
            let _ = fs::rename(quarantine, &installed_plugin);
        }
        return Err(error.into());
    }
    let skill = reconcile_global_skill(&root, transaction)?;
    let codex = reconcile_codex(&root)?;
    let hook_trust = if codex.is_some() {
        write_codex_hook_state(&root, false)?;
        Some(CodexHookTrustRequirement {
            code: "HOOK_TRUST_REQUIRED",
            trusted: false,
            action: "Review and trust the native kast@kast hooks when Codex prompts; Kast does not bypass provider trust.",
        })
    } else {
        remove_codex_hook_state(&root)?;
        None
    };
    Ok(MachineReconciliation {
        reconciliation_type: "KAST_MACHINE_RECONCILIATION",
        state: "RECONCILED",
        idea_plugin: installed_plugin.display().to_string(),
        skill: skill.display().to_string(),
        codex: codex.map(|path| path.display().to_string()),
        hook_trust,
        quarantined_plugin: quarantined_plugin.map(|path| path.display().to_string()),
        schema_version: 2,
    })
}

pub(crate) fn mark_codex_hook_trusted() -> Result<()> {
    let root = machine_root();
    if active_machine_identity()?.is_some() {
        write_codex_hook_state(&root, true)?;
    }
    Ok(())
}

pub(crate) fn codex_hook_trust_proven() -> Result<Option<bool>> {
    let root = machine_root();
    let Some(machine_identity) = active_machine_identity()? else {
        return Ok(None);
    };
    let path = codex_hook_state_path(&root);
    if !path.is_file() {
        return Ok(Some(false));
    }
    let state: CodexHookState = serde_json::from_slice(&fs::read(&path)?).map_err(|error| {
        CliError::new(
            "MACHINE_CODEX_HOOK_STATE_INVALID",
            format!(
                "Codex hook trust state is invalid at {}: {error}",
                path.display()
            ),
        )
    })?;
    if state.state_type != "KAST_CODEX_HOOK_STATE"
        || state.schema_version != 1
        || state.machine_identity != machine_identity
    {
        return Err(CliError::new(
            "MACHINE_CODEX_HOOK_STATE_INVALID",
            format!(
                "Codex hook trust state does not match the active machine generation at {}.",
                path.display()
            ),
        ));
    }
    Ok(Some(state.trusted))
}

fn write_codex_hook_state(root: &Path, trusted: bool) -> Result<()> {
    let path = codex_hook_state_path(root);
    let parent = path.parent().expect("Codex hook state parent");
    fs::create_dir_all(parent)?;
    let state = CodexHookState {
        state_type: "KAST_CODEX_HOOK_STATE".to_string(),
        machine_identity: crate::manifest::sha256_file(&root.join("machine.json"))?,
        trusted,
        schema_version: 1,
    };
    let temporary = parent.join(format!(".codex-hook-{}.tmp", std::process::id()));
    fs::write(&temporary, serde_json::to_vec_pretty(&state)?)?;
    fs::rename(temporary, path)?;
    Ok(())
}

fn remove_codex_hook_state(root: &Path) -> Result<()> {
    let path = codex_hook_state_path(root);
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn codex_hook_state_path(root: &Path) -> PathBuf {
    root.join("state/codex-hook.json")
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CodexPluginList {
    #[serde(default)]
    installed: Vec<CodexInstalledPlugin>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CodexInstalledPlugin {
    plugin_id: String,
}

#[derive(Debug, Deserialize)]
struct CodexMarketplaceList {
    #[serde(default)]
    marketplaces: Vec<CodexMarketplace>,
}

#[derive(Debug, Deserialize)]
struct CodexMarketplace {
    name: String,
}

fn reconcile_codex(root: &Path) -> Result<Option<PathBuf>> {
    let marketplace = root.join("resources/codex-marketplace");
    let Some(installed_output) = run_optional_codex(&["plugin", "list", "--json"])? else {
        return Ok(None);
    };
    let installed: CodexPluginList =
        serde_json::from_slice(&installed_output.stdout).map_err(|error| {
            CliError::new(
                "CODEX_PLUGIN_STATE_INVALID",
                format!("Codex plugin list is not valid JSON: {error}"),
            )
        })?;
    if installed
        .installed
        .iter()
        .any(|plugin| plugin.plugin_id == "kast@kast")
    {
        run_codex(&["plugin", "remove", "kast@kast", "--json"])?;
    }
    let marketplaces_output = run_codex(&["plugin", "marketplace", "list", "--json"])?;
    let marketplaces: CodexMarketplaceList = serde_json::from_slice(&marketplaces_output.stdout)
        .map_err(|error| {
            CliError::new(
                "CODEX_MARKETPLACE_STATE_INVALID",
                format!("Codex marketplace list is not valid JSON: {error}"),
            )
        })?;
    if marketplaces
        .marketplaces
        .iter()
        .any(|candidate| candidate.name == "kast")
    {
        run_codex(&["plugin", "marketplace", "remove", "kast", "--json"])?;
    }
    run_codex(&[
        "plugin",
        "marketplace",
        "add",
        marketplace.to_str().ok_or_else(|| {
            CliError::new(
                "CODEX_MARKETPLACE_PATH_INVALID",
                format!(
                    "Codex marketplace path is not UTF-8: {}",
                    marketplace.display()
                ),
            )
        })?,
        "--json",
    ])?;
    run_codex(&["plugin", "add", "kast@kast", "--json"])?;
    Ok(Some(marketplace))
}

fn run_optional_codex(args: &[&str]) -> Result<Option<std::process::Output>> {
    match Command::new("codex").args(args).output() {
        Ok(output) if output.status.success() => Ok(Some(output)),
        Ok(output) => Err(codex_command_error(args, &output)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn run_codex(args: &[&str]) -> Result<std::process::Output> {
    let output = Command::new("codex").args(args).output()?;
    if output.status.success() {
        Ok(output)
    } else {
        Err(codex_command_error(args, &output))
    }
}

fn codex_command_error(args: &[&str], output: &std::process::Output) -> CliError {
    CliError::new(
        "CODEX_RECONCILIATION_FAILED",
        format!(
            "`codex {}` failed: {}",
            args.join(" "),
            String::from_utf8_lossy(&output.stderr).trim()
        ),
    )
}

fn machine_root() -> PathBuf {
    crate::config::home_dir().join("Library/Application Support/Kast/machine")
}

fn validate_machine_install(root: &Path) -> Result<MachineManifest> {
    let path = root.join("machine.json");
    let bytes = fs::read(&path).map_err(|error| {
        CliError::new(
            "MACHINE_NOT_INSTALLED",
            format!("Cannot read {}: {error}", path.display()),
        )
    })?;
    let manifest: MachineManifest = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "MACHINE_INSTALL_INVALID",
            format!("Machine manifest is invalid at {}: {error}", path.display()),
        )
    })?;
    let executable_components = [
        root.join("bin/kast"),
        root.join("bin/kast-agent-task"),
        root.join("resources/agent-task/kast-agent-task"),
        root.join("resources/codex-marketplace/plugins/kast/scripts/kast-codex-hook"),
    ];
    if manifest.schema_version != 2
        || manifest.manifest_type != "KAST_MACHINE_MANIFEST"
        || executable_components
            .iter()
            .any(|component| !is_executable_file(component))
        || crate::manifest::sha256_file(&root.join("bin/kast"))? != manifest.cli_sha256
        || crate::manifest::sha256_file(&root.join("bin/kast-agent-task"))?
            != manifest.task_launcher_sha256
        || crate::manifest::sha256_file(&root.join("idea/kast.zip"))? != manifest.idea_plugin_sha256
        || directory_sha256(&root.join("resources"))? != manifest.resources_sha256
    {
        return Err(CliError::new(
            "MACHINE_INSTALL_INVALID",
            format!(
                "Machine installation is incomplete or modified at {}.",
                root.display()
            ),
        ));
    }
    Ok(manifest)
}

#[cfg(unix)]
fn is_executable_file(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;

    fs::metadata(path)
        .is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn is_executable_file(path: &Path) -> bool {
    path.is_file()
}

fn write_agent_task_resources(target: &Path) -> Result<()> {
    const FILES: &[(&str, &[u8])] = &[
        (
            "kast-agent-task",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/agent-task/kast-agent-task"
            )),
        ),
        (
            "gradle-receipt.init.gradle",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/agent-task/gradle-receipt.init.gradle"
            )),
        ),
        (
            "workflow.schema.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/agent-task/workflow.schema.json"
            )),
        ),
        (
            "guidance.md",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/agent-task/guidance.md"
            )),
        ),
    ];
    write_embedded_tree(target, FILES)?;
    make_executable(&target.join("kast-agent-task"))
}

fn write_copilot_plugin(target: &Path) -> Result<()> {
    const FILES: &[(&str, &[u8])] = &[
        (
            "plugin.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/plugin/plugin.json"
            )),
        ),
        (
            "primitive-manifest.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/plugin/primitive-manifest.json"
            )),
        ),
        (
            "primitive-manifest.schema.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/plugin/primitive-manifest.schema.json"
            )),
        ),
        (
            "lsp.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/plugin/lsp.json"
            )),
        ),
        (
            "extensions/kast/extension.mjs",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/plugin/extensions/kast/extension.mjs"
            )),
        ),
        (
            "extensions/kast/_shared/kast-trace.mjs",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/plugin/extensions/kast/_shared/kast-trace.mjs"
            )),
        ),
    ];
    write_embedded_tree(target, FILES)
}

fn write_embedded_tree(target: &Path, files: &[(&str, &[u8])]) -> Result<()> {
    for (relative, contents) in files {
        let path = target.join(relative);
        fs::create_dir_all(path.parent().expect("embedded resource parent"))?;
        fs::write(path, contents)?;
    }
    Ok(())
}

fn write_codex_marketplace(target: &Path) -> Result<()> {
    const FILES: &[(&str, &[u8])] = &[
        (
            "marketplace.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/marketplace.json"
            )),
        ),
        (
            ".agents/plugins/marketplace.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/.agents/plugins/marketplace.json"
            )),
        ),
        (
            "plugins/kast/.codex-plugin/plugin.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/.codex-plugin/plugin.json"
            )),
        ),
        (
            "plugins/kast/assets/codex-exposure.toon",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/assets/codex-exposure.toon"
            )),
        ),
        (
            "plugins/kast/assets/hook-recovery-messages.toon",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/assets/hook-recovery-messages.toon"
            )),
        ),
        (
            "plugins/kast/assets/kast.svg",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/assets/kast.svg"
            )),
        ),
        (
            "plugins/kast/hooks/hooks.json",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/hooks/hooks.json"
            )),
        ),
        (
            "plugins/kast/scripts/kast-codex-hook",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/scripts/kast-codex-hook"
            )),
        ),
        (
            "plugins/kast/skills/kast-codex/SKILL.md",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/skills/kast-codex/SKILL.md"
            )),
        ),
        (
            "plugins/kast/skills/kast-codex/agents/openai.yaml",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/resources/codex-plugin/plugins/kast/skills/kast-codex/agents/openai.yaml"
            )),
        ),
    ];
    for (relative, contents) in FILES {
        let path = target.join(relative);
        fs::create_dir_all(path.parent().expect("Codex resource parent"))?;
        fs::write(&path, contents)?;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(
            target.join("plugins/kast/scripts/kast-codex-hook"),
            fs::Permissions::from_mode(0o755),
        )?;
    }
    Ok(())
}

fn directory_sha256(root: &Path) -> Result<String> {
    fn collect(root: &Path, directory: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            let metadata = fs::symlink_metadata(entry.path())?;
            if metadata.file_type().is_symlink() {
                return Err(CliError::new(
                    "MACHINE_COMPONENT_INVALID",
                    format!(
                        "Machine resource contains a symlink: {}",
                        entry.path().display()
                    ),
                ));
            }
            if metadata.is_dir() {
                collect(root, &entry.path(), files)?;
            } else if metadata.is_file() {
                files.push(
                    entry
                        .path()
                        .strip_prefix(root)
                        .expect("directory child")
                        .to_path_buf(),
                );
            } else {
                return Err(CliError::new(
                    "MACHINE_COMPONENT_INVALID",
                    format!(
                        "Machine resource is not a regular file: {}",
                        entry.path().display()
                    ),
                ));
            }
        }
        Ok(())
    }
    let mut files = Vec::new();
    collect(root, root, &mut files)?;
    files.sort();
    let mut identity = Vec::new();
    for relative in files {
        identity.extend_from_slice(relative.to_string_lossy().as_bytes());
        identity.push(b'\n');
        identity.extend_from_slice(crate::manifest::sha256_file(&root.join(&relative))?.as_bytes());
        identity.push(b'\n');
    }
    Ok(crate::manifest::sha256_bytes(&identity))
}

fn extract_plugin_zip(source: &Path, target: &Path) -> Result<()> {
    let file = fs::File::open(source)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        CliError::new(
            "IDE_PLUGIN_ARCHIVE_INVALID",
            format!("Cannot read IDEA plugin ZIP {}: {error}", source.display()),
        )
    })?;
    let mut root_name = None;
    let mut file_count = 0usize;
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|error| CliError::new("IDE_PLUGIN_ARCHIVE_INVALID", error.to_string()))?;
        let enclosed = entry.enclosed_name().ok_or_else(|| {
            CliError::new(
                "IDE_PLUGIN_ARCHIVE_UNSAFE",
                format!("IDE plugin ZIP contains an unsafe path: {}", entry.name()),
            )
        })?;
        if entry
            .unix_mode()
            .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            return Err(CliError::new(
                "IDE_PLUGIN_ARCHIVE_UNSAFE",
                format!("IDE plugin ZIP contains a symlink: {}", entry.name()),
            ));
        }
        let mut components = enclosed.components();
        let Some(Component::Normal(first)) = components.next() else {
            continue;
        };
        match &root_name {
            Some(expected) if expected != first => {
                return Err(CliError::new(
                    "IDE_PLUGIN_ARCHIVE_INVALID",
                    "IDE plugin ZIP must contain exactly one top-level directory.",
                ));
            }
            None => root_name = Some(first.to_os_string()),
            _ => {}
        }
        let relative = components.collect::<PathBuf>();
        if relative.as_os_str().is_empty() {
            continue;
        }
        let output = target.join(relative);
        if entry.is_dir() {
            fs::create_dir_all(&output)?;
        } else {
            if let Some(parent) = output.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut file = fs::File::create(&output)?;
            io::copy(&mut entry, &mut file)?;
            file_count += 1;
        }
    }
    if root_name.is_none() || file_count == 0 {
        return Err(CliError::new(
            "IDE_PLUGIN_ARCHIVE_INVALID",
            "IDE plugin ZIP must contain one nonempty top-level plugin directory.",
        ));
    }
    Ok(())
}

fn reconcile_global_skill(root: &Path, transaction: uuid::Uuid) -> Result<PathBuf> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::symlink;
        let skill = crate::config::home_dir().join(".agents/skills/kast");
        if let Ok(metadata) = fs::symlink_metadata(&skill) {
            if metadata.file_type().is_symlink() {
                fs::remove_file(&skill)?;
            } else {
                let quarantine = root.join("quarantine").join(format!("{transaction}-skill"));
                fs::create_dir_all(quarantine.parent().expect("quarantine parent"))?;
                fs::rename(&skill, quarantine)?;
            }
        }
        fs::create_dir_all(skill.parent().expect("skill parent"))?;
        symlink(root.join("resources/kast-skill"), &skill)?;
        Ok(skill)
    }
    #[cfg(not(unix))]
    {
        let _ = (root, transaction);
        Err(CliError::new(
            "MACHINE_PLATFORM_UNSUPPORTED",
            "Machine resource reconciliation requires macOS or another Unix host.",
        ))
    }
}

fn default_idea_plugins_dir() -> Result<PathBuf> {
    let profiles = crate::config::home_dir().join("Library/Application Support/JetBrains");
    let mut candidates = fs::read_dir(&profiles)
        .map_err(|error| {
            CliError::new(
                "IDE_PROFILE_NOT_FOUND",
                format!("Cannot inspect {}: {error}", profiles.display()),
            )
        })?
        .filter_map(std::result::Result::ok)
        .filter(|entry| {
            entry.file_type().is_ok_and(|kind| kind.is_dir())
                && entry.file_name().to_str().is_some_and(|name| {
                    ["IntelliJIdea", "IdeaIC", "AndroidStudio"]
                        .iter()
                        .any(|prefix| name.starts_with(prefix))
                })
        })
        .map(|entry| entry.path().join("plugins"))
        .collect::<Vec<_>>();
    candidates.sort();
    candidates.pop().ok_or_else(|| {
        CliError::new(
            "IDE_PROFILE_NOT_FOUND",
            "No IntelliJ IDEA or Android Studio profile was found; pass --idea-plugins-dir.",
        )
    })
}

fn require_jetbrains_ides_closed() -> Result<()> {
    if let Ok(state) = std::env::var("KAST_MACHINE_IDE_STATE") {
        return match state.as_str() {
            "closed" => Ok(()),
            "open" => Err(CliError::new(
                "IDE_RESTART_REQUIRED",
                "Close IntelliJ IDEA or Android Studio, then rerun `kast machine reconcile`.",
            )),
            _ => Err(CliError::new(
                "IDE_STATE_INVALID",
                "KAST_MACHINE_IDE_STATE must be `open` or `closed` when set.",
            )),
        };
    }
    #[cfg(target_os = "macos")]
    {
        let output = Command::new("pgrep")
            .args([
                "-f",
                "/(IntelliJ IDEA|Android Studio)[^/]*\\.app/Contents/MacOS/",
            ])
            .output()?;
        match output.status.code() {
            Some(1) => Ok(()),
            Some(0) => Err(CliError::new(
                "IDE_RESTART_REQUIRED",
                "Close IntelliJ IDEA or Android Studio, then rerun `kast machine reconcile`.",
            )),
            status => Err(CliError::new(
                "IDE_STATE_UNAVAILABLE",
                format!("Could not determine IDE process state: {status:?}."),
            )),
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        Ok(())
    }
}

fn require_regular_file(path: &Path, label: &str) -> Result<()> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        CliError::new(
            "MACHINE_COMPONENT_MISSING",
            format!("Cannot read {label} at {}: {error}", path.display()),
        )
    })?;
    if metadata.is_file() && !metadata.file_type().is_symlink() {
        Ok(())
    } else {
        Err(CliError::new(
            "MACHINE_COMPONENT_INVALID",
            format!("{label} must be a regular file: {}", path.display()),
        ))
    }
}

#[cfg(unix)]
fn replace_stable_command(name: &str, target: &Path) -> Result<()> {
    use std::os::unix::fs::symlink;
    let command = crate::config::home_dir().join(".local/bin").join(name);
    let parent = command.parent().ok_or_else(|| {
        CliError::new(
            "MACHINE_INSTALL_PATH_INVALID",
            "Stable command has no parent.",
        )
    })?;
    fs::create_dir_all(parent)?;
    if let Ok(metadata) = fs::symlink_metadata(&command) {
        if !metadata.file_type().is_symlink() {
            return Err(CliError::new(
                "MACHINE_COMMAND_BLOCKED",
                format!(
                    "Refusing to replace non-symlink command at {}.",
                    command.display()
                ),
            ));
        }
        fs::remove_file(&command)?;
    }
    symlink(target, command)?;
    Ok(())
}

#[cfg(not(unix))]
fn replace_stable_command(_name: &str, _target: &Path) -> Result<()> {
    Err(CliError::new(
        "MACHINE_PLATFORM_UNSUPPORTED",
        "Machine activation currently requires macOS or another Unix host.",
    ))
}

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))?;
    Ok(())
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}
