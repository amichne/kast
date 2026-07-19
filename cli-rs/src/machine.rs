use crate::cli::MachineActivateArgs;
use crate::error::{CliError, Result};
use serde::Serialize;
use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};

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
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct MachineManifest {
    #[serde(rename = "type")]
    manifest_type: &'static str,
    cli_sha256: String,
    idea_plugin_sha256: String,
    skill_sha256: String,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MachineActivation {
    #[serde(rename = "type")]
    activation_type: &'static str,
    state: &'static str,
    pub(crate) cli: String,
    idea_plugin: String,
    skill: String,
    schema_version: u32,
}

pub(crate) fn status() -> MachineStatus {
    let root = machine_root();
    let installed = root.join("machine.json").is_file();
    MachineStatus {
        status_type: "KAST_MACHINE_STATUS",
        state: if installed {
            MachineState::Installed
        } else {
            MachineState::NotInstalled
        },
        schema_version: 1,
    }
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
    fs::create_dir_all(staging.join("resources/kast-skill"))?;

    let installed_cli = staging.join("bin/kast");
    fs::copy(&source_cli, &installed_cli)?;
    fs::set_permissions(&installed_cli, fs::metadata(&source_cli)?.permissions())?;
    let installed_plugin = staging.join("idea/kast.zip");
    fs::copy(&args.idea_plugin, &installed_plugin)?;
    let installed_skill = staging.join("resources/kast-skill/SKILL.md");
    fs::write(
        &installed_skill,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/resources/kast-skill/SKILL.md"
        )),
    )?;
    let manifest = MachineManifest {
        manifest_type: "KAST_MACHINE_MANIFEST",
        cli_sha256: crate::manifest::sha256_file(&installed_cli)?,
        idea_plugin_sha256: crate::manifest::sha256_file(&installed_plugin)?,
        skill_sha256: crate::manifest::sha256_file(&installed_skill)?,
        schema_version: 1,
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
    replace_stable_command(&root.join("bin/kast"))?;

    Ok(MachineActivation {
        activation_type: "KAST_MACHINE_ACTIVATION",
        state: "ACTIVATED",
        cli: root.join("bin/kast").display().to_string(),
        idea_plugin: root.join("idea/kast.zip").display().to_string(),
        skill: root
            .join("resources/kast-skill/SKILL.md")
            .display()
            .to_string(),
        schema_version: 1,
    })
}

fn machine_root() -> PathBuf {
    crate::config::home_dir().join("Library/Application Support/Kast/machine")
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
fn replace_stable_command(target: &Path) -> Result<()> {
    use std::os::unix::fs::symlink;
    let command = crate::config::home_dir().join(".local/bin/kast");
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
fn replace_stable_command(_target: &Path) -> Result<()> {
    Err(CliError::new(
        "MACHINE_PLATFORM_UNSUPPORTED",
        "Machine activation currently requires macOS or another Unix host.",
    ))
}
