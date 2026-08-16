use super::*;
use super::process::ManagedProcessIdentity;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use uuid::Uuid;

#[path = "registration/storage.rs"]
mod storage;
use storage::*;

const SERVICE_REGISTRATION_SCHEMA: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ServiceProcessClaim {
    pub schema_version: u32,
    pub launch_sha256: String,
    pub process: ManagedProcessIdentity,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ServiceLaunchRegistration {
    pub schema_version: u32,
    pub workspace_root: String,
    pub workspace_key: String,
    pub runtime_instance_id: Uuid,
    pub owner_uid: u64,
    pub working_directory: String,
    pub command: Vec<String>,
    pub environment: BTreeMap<String, String>,
    pub log_file: String,
    pub descriptor_directory: String,
    pub socket_path: String,
    pub launcher_path: String,
    pub launcher_sha256: String,
    pub runtime_config_path: String,
    pub runtime_config_sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE", deny_unknown_fields)]
pub(super) enum ServiceManagerRegistration {
    Launchd {
        domain: String,
        label: String,
        definition_path: String,
    },
    SystemdUser {
        unit: String,
        definition_path: String,
    },
    Test {
        state_path: String,
        definition_path: String,
    },
}

impl ServiceManagerRegistration {
    pub(super) fn definition_path(&self) -> &Path {
        let value = match self {
            Self::Launchd {
                definition_path, ..
            }
            | Self::SystemdUser {
                definition_path, ..
            }
            | Self::Test {
                definition_path, ..
            } => definition_path,
        };
        Path::new(value)
    }

    pub(super) fn identifier(&self) -> &str {
        match self {
            Self::Launchd { label, .. } => label,
            Self::SystemdUser { unit, .. } => unit,
            Self::Test { state_path, .. } => state_path,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ServiceRegistrationReceipt {
    pub schema_version: u32,
    pub workspace_root: String,
    pub workspace_key: String,
    pub runtime_instance_id: Uuid,
    pub launch_path: String,
    pub launch_sha256: String,
    pub definition_sha256: String,
    pub manager: ServiceManagerRegistration,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ActiveServiceRegistration {
    pub schema_version: u32,
    pub runtime_instance_id: Uuid,
    pub receipt_sha256: String,
}

#[derive(Debug, Clone)]
pub(super) struct ValidatedServiceRegistration {
    pub directory: PathBuf,
    pub receipt_path: PathBuf,
    pub receipt_sha256: String,
    pub receipt: ServiceRegistrationReceipt,
    pub launch: ServiceLaunchRegistration,
}

pub(super) struct PreparedServiceRegistration {
    pub validated: ValidatedServiceRegistration,
    pub active_path: PathBuf,
}

pub(super) fn workspace_key(workspace_root: &Path) -> String {
    hex::encode(Sha256::digest(
        workspace_root.to_string_lossy().as_bytes(),
    ))
}

pub(super) fn service_workspace_directory(config: &KastConfig, root: &Path) -> PathBuf {
    config
        .paths
        .runtime_dir
        .join("services")
        .join(workspace_key(root))
}

pub(super) fn prepare_service_registration(
    request: &SemanticRuntimeRequest,
    mut daemon_args: DaemonStartArgs,
) -> Result<PreparedServiceRegistration> {
    let runtime_instance_id = Uuid::new_v4();
    daemon_args.runtime_instance_id = Some(runtime_instance_id);
    let workspace_key = workspace_key(&request.workspace_root);
    let workspace_directory = service_workspace_directory(&request.config, &request.workspace_root);
    let final_directory = workspace_directory.join(runtime_instance_id.to_string());
    let staging_directory = workspace_directory.join(format!(".staging-{runtime_instance_id}"));
    fs::create_dir_all(&workspace_directory)?;
    fs::create_dir(&staging_directory)?;
    set_owner_only_directory(&staging_directory)?;

    let result = prepare_service_registration_in(
        request,
        &daemon_args,
        &workspace_key,
        runtime_instance_id,
        &staging_directory,
        &final_directory,
    );
    match result {
        Ok(_) => {}
        Err(error) => {
            let _ = fs::remove_dir_all(&staging_directory);
            return Err(error);
        }
    }
    fs::rename(&staging_directory, &final_directory)?;
    sync_parent(&final_directory)?;
    let validated = validate_service_registration(&final_directory, &request.workspace_root)?;
    Ok(PreparedServiceRegistration {
        validated,
        active_path: workspace_directory.join("active.json"),
    })
}

fn prepare_service_registration_in(
    request: &SemanticRuntimeRequest,
    daemon_args: &DaemonStartArgs,
    workspace_key: &str,
    runtime_instance_id: Uuid,
    staging_directory: &Path,
    final_directory: &Path,
) -> Result<ValidatedServiceRegistration> {
    let runtime_config_path = final_directory.join("runtime-config.json");
    let (mut command, runtime_config_bytes) = daemon::service_java_command(
        daemon_args,
        &request.config,
        &runtime_config_path,
    )?;
    let executable = command.first_mut().ok_or_else(|| {
        CliError::new("RUNTIME_REGISTRATION_INVALID", "Indexer command is empty.")
    })?;
    *executable = canonical_executable(executable)?;
    write_durable_file(
        &staging_directory.join("runtime-config.json"),
        &runtime_config_bytes,
    )?;
    let launcher = fs::canonicalize(std::env::current_exe()?)?;
    let log_file = daemon_log_file(&request.config, &request.workspace_root, BackendName::Indexer);
    let launch = ServiceLaunchRegistration {
        schema_version: SERVICE_REGISTRATION_SCHEMA,
        workspace_root: request.workspace_root.display().to_string(),
        workspace_key: workspace_key.to_string(),
        runtime_instance_id,
        owner_uid: effective_uid(),
        working_directory: request.workspace_root.display().to_string(),
        command,
        environment: service_environment(&request.config),
        log_file: log_file.display().to_string(),
        descriptor_directory: request.config.paths.descriptor_dir.display().to_string(),
        socket_path: config::default_socket_path(&request.config, &request.workspace_root)
            .display()
            .to_string(),
        launcher_path: launcher.display().to_string(),
        launcher_sha256: crate::manifest::sha256_file(&launcher)?,
        runtime_config_path: runtime_config_path.display().to_string(),
        runtime_config_sha256: crate::manifest::sha256_bytes(&runtime_config_bytes),
    };
    let launch_bytes = serde_json::to_vec_pretty(&launch)?;
    let launch_sha256 = crate::manifest::sha256_bytes(&launch_bytes);
    write_durable_file(&staging_directory.join("launch.json"), &launch_bytes)?;
    let manager = super::service_manager::registration_for(
        &launch,
        final_directory,
        &launch_sha256,
    )?;
    let definition = super::service_manager::render_definition(&launch, &manager, &launch_sha256)?;
    write_durable_file(
        &staging_directory.join(
            manager
                .definition_path()
                .file_name()
                .ok_or_else(|| CliError::new("RUNTIME_REGISTRATION_INVALID", "Service definition has no file name."))?,
        ),
        definition.as_bytes(),
    )?;
    let receipt = ServiceRegistrationReceipt {
        schema_version: SERVICE_REGISTRATION_SCHEMA,
        workspace_root: launch.workspace_root.clone(),
        workspace_key: launch.workspace_key.clone(),
        runtime_instance_id,
        launch_path: final_directory.join("launch.json").display().to_string(),
        launch_sha256,
        definition_sha256: crate::manifest::sha256_bytes(definition.as_bytes()),
        manager,
    };
    let receipt_bytes = serde_json::to_vec_pretty(&receipt)?;
    write_durable_file(&staging_directory.join("receipt.json"), &receipt_bytes)?;
    Ok(ValidatedServiceRegistration {
        directory: final_directory.to_path_buf(),
        receipt_path: final_directory.join("receipt.json"),
        receipt_sha256: crate::manifest::sha256_bytes(&receipt_bytes),
        receipt,
        launch,
    })
}

include!("registration/validation.rs");

pub(super) fn publish_active_registration(prepared: &PreparedServiceRegistration) -> Result<()> {
    let active = ActiveServiceRegistration {
        schema_version: SERVICE_REGISTRATION_SCHEMA,
        runtime_instance_id: prepared.validated.receipt.runtime_instance_id,
        receipt_sha256: prepared.validated.receipt_sha256.clone(),
    };
    write_atomic_json(&prepared.active_path, &active)
}

pub(super) fn read_active_registration(path: &Path) -> Result<Option<ActiveServiceRegistration>> {
    match read_owned_json::<ActiveServiceRegistration>(path) {
        Ok((active, _)) if active.schema_version == SERVICE_REGISTRATION_SCHEMA => Ok(Some(active)),
        Ok(_) => Err(registration_invalid("Active service registration schema is unsupported.")),
        Err(error) if error.code == "RUNTIME_REGISTRATION_MISSING" => Ok(None),
        Err(error) => Err(error),
    }
}

pub(super) fn write_process_claim(
    directory: &Path,
    launch_sha256: &str,
    process: ManagedProcessIdentity,
) -> Result<()> {
    write_atomic_json(
        &directory.join("process.json"),
        &ServiceProcessClaim {
            schema_version: SERVICE_REGISTRATION_SCHEMA,
            launch_sha256: launch_sha256.to_string(),
            process,
        },
    )
}

pub(super) fn read_process_claim(directory: &Path) -> Result<Option<ServiceProcessClaim>> {
    match read_owned_json::<ServiceProcessClaim>(&directory.join("process.json")) {
        Ok((claim, _))
            if claim.schema_version == SERVICE_REGISTRATION_SCHEMA
                && claim.launch_sha256.len() == 64 =>
        {
            Ok(Some(claim))
        }
        Ok(_) => Err(registration_invalid("Runtime process claim is invalid.")),
        Err(error) if error.code == "RUNTIME_REGISTRATION_MISSING" => Ok(None),
        Err(error) => Err(error),
    }
}

fn service_environment(config: &KastConfig) -> BTreeMap<String, String> {
    let mut environment = BTreeMap::from([
        ("KAST_HOME".to_string(), config.paths.install_root.display().to_string()),
        ("KAST_CONFIG_HOME".to_string(), config::kast_config_home().display().to_string()),
        ("KAST_INDEXER".to_string(), "true".to_string()),
    ]);
    for name in ["KAST_CACHE_HOME", "KAST_WORKSPACE_ID", "HOME", "TMPDIR"] {
        if let Ok(value) = std::env::var(name)
            && !value.is_empty()
        {
            environment.insert(name.to_string(), value);
        }
    }
    environment
}

fn effective_uid() -> u64 {
    #[cfg(unix)]
    { u64::from(unsafe { libc::geteuid() }) }
    #[cfg(not(unix))]
    { 0 }
}

fn registration_invalid(message: &str) -> CliError {
    CliError::new("RUNTIME_REGISTRATION_INVALID", message)
}
