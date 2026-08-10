use super::process::ManagedProcessIdentity;
use super::*;
use sha2::{Digest, Sha256};
use uuid::Uuid;

#[path = "registration/storage.rs"]
pub(super) mod storage;
use storage::*;
#[path = "registration/environment.rs"]
mod environment;
use environment::ServiceLaunchEnvironment;
#[path = "registration/socket.rs"]
mod socket;
use socket::ServiceSocketPath;
#[path = "registration/setup.rs"]
mod setup;
pub(crate) use setup::{RuntimeSetupAuthorization, RuntimeSetupIntent, preflight_runtime_setup};

include!("registration/model.rs");

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
    pub environment: ServiceLaunchEnvironment,
    pub log_file: String,
    pub descriptor_directory: String,
    pub socket_path: String,
    pub launcher_path: String,
    pub launcher_sha256: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub installed_release: Option<InstalledReleasePin>,
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
    hex::encode(Sha256::digest(workspace_root.to_string_lossy().as_bytes()))
}

pub(super) fn service_workspace_directory(config: &KastConfig, root: &Path) -> PathBuf {
    config
        .paths
        .runtime_dir
        .join("services")
        .join(workspace_key(root))
}

pub(super) fn prepare_service_registration<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
    mut daemon_args: DaemonStartArgs,
) -> Result<PreparedServiceRegistration> {
    let runtime_instance_id = Uuid::new_v4();
    daemon_args.runtime_instance_id = Some(runtime_instance_id);
    let workspace_key = workspace_key(&request.workspace_root);
    let workspace_directory = service_workspace_directory(&request.config, &request.workspace_root);
    let final_directory = workspace_directory.join(runtime_instance_id.to_string());
    let staging_directory = workspace_directory.join(format!(".staging-{runtime_instance_id}"));
    let services_directory = workspace_directory
        .parent()
        .ok_or_else(|| registration_invalid("Service workspace has no parent."))?;
    fs::create_dir_all(services_directory)?;
    set_owner_only_directory(services_directory)?;
    sync_parent(services_directory)?;
    fs::create_dir_all(&workspace_directory)?;
    set_owner_only_directory(&workspace_directory)?;
    sync_parent(&workspace_directory)?;
    fs::create_dir(&staging_directory)?;
    set_owner_only_directory(&staging_directory)?;
    sync_parent(&staging_directory)?;

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
    fs::File::open(&staging_directory)?.sync_all()?;
    fs::rename(&staging_directory, &final_directory)?;
    sync_parent(&final_directory)?;
    let validated = validate_service_registration(&final_directory, &request.workspace_root)?;
    Ok(PreparedServiceRegistration {
        validated,
        active_path: workspace_directory.join("active.json"),
    })
}

fn prepare_service_registration_in<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
    daemon_args: &DaemonStartArgs,
    workspace_key: &str,
    runtime_instance_id: Uuid,
    staging_directory: &Path,
    final_directory: &Path,
) -> Result<ValidatedServiceRegistration> {
    let runtime_config_path = final_directory.join("runtime-config.json");
    let (mut command, runtime_config_bytes) =
        daemon::service_java_command(daemon_args, &request.config, &runtime_config_path)?;
    let socket_path = ServiceSocketPath::from_command(&command)?;
    let executable = command.first_mut().ok_or_else(|| {
        CliError::new("RUNTIME_REGISTRATION_INVALID", "Indexer command is empty.")
    })?;
    *executable = canonical_executable(executable)?;
    write_durable_file(
        &staging_directory.join("runtime-config.json"),
        &runtime_config_bytes,
    )?;
    let test_manager =
        std::env::var("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER").as_deref() == Ok("1");
    let test_launcher = test_manager
        .then(|| std::env::var_os("KAST_TEST_RUNTIME_SERVICE_LAUNCHER"))
        .flatten();
    let launcher = if let Some(test_launcher) = test_launcher.as_ref() {
        fs::canonicalize(test_launcher)?
    } else if test_manager {
        fs::canonicalize(std::env::current_exe()?)?
    } else {
        let active_binary = crate::manifest::resolve_paths()?.active_binary;
        let launcher = fs::canonicalize(active_binary)?;
        if launcher.file_name().and_then(|value| value.to_str()) != Some("kastctl") {
            return Err(registration_invalid(
                "Installed runtime service launcher must be the receipt-backed kastctl binary.",
            ));
        }
        launcher
    };
    let log_file = daemon_log_file(
        &request.config,
        &request.workspace_root,
        BackendName::Indexer,
    );
    prepare_service_log_parent(&log_file)?;
    let launch = ServiceLaunchRegistration {
        schema_version: SERVICE_REGISTRATION_SCHEMA,
        workspace_root: request.workspace_root.display().to_string(),
        workspace_key: workspace_key.to_string(),
        runtime_instance_id,
        owner_uid: effective_uid(),
        working_directory: request.workspace_root.display().to_string(),
        command,
        environment: ServiceLaunchEnvironment::capture(&request.config)?,
        log_file: log_file.display().to_string(),
        descriptor_directory: request.config.paths.descriptor_dir.display().to_string(),
        socket_path: socket_path.into_string(),
        launcher_path: launcher.display().to_string(),
        launcher_sha256: sha256_stable_file(&launcher, false)?,
        installed_release: if test_manager && test_launcher.is_none() {
            None
        } else {
            Some(installed_release_pin(
                &launcher,
                &request.config.paths.install_root,
            )?)
        },
        runtime_config_path: runtime_config_path.display().to_string(),
        runtime_config_sha256: crate::manifest::sha256_bytes(&runtime_config_bytes),
    };
    let launch_bytes = serde_json::to_vec_pretty(&launch)?;
    let launch_sha256 = crate::manifest::sha256_bytes(&launch_bytes);
    write_durable_file(&staging_directory.join("launch.json"), &launch_bytes)?;
    let manager =
        super::service_manager::registration_for(&launch, final_directory, &launch_sha256)?;
    let definition = super::service_manager::render_definition(&launch, &manager, &launch_sha256)?;
    write_durable_file(
        &staging_directory.join(manager.definition_path().file_name().ok_or_else(|| {
            CliError::new(
                "RUNTIME_REGISTRATION_INVALID",
                "Service definition has no file name.",
            )
        })?),
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

fn prepare_service_log_parent(log_file: &Path) -> Result<()> {
    let parent = log_file
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .ok_or_else(|| registration_invalid("Runtime service log file has no parent directory."))?;
    fs::create_dir_all(parent)?;
    Ok(())
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
        Ok(_) => Err(registration_invalid(
            "Active service registration schema is unsupported.",
        )),
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
    match read_process_claim_file(&directory.join("process.json")) {
        Ok(claim) => Ok(Some(claim)),
        Err(error) if error.code == "RUNTIME_REGISTRATION_MISSING" => Ok(None),
        Err(error) => Err(error),
    }
}

pub(super) fn read_process_claim_file(path: &Path) -> Result<ServiceProcessClaim> {
    let (claim, _) = read_owned_json::<ServiceProcessClaim>(path)?;
    if claim.schema_version == SERVICE_REGISTRATION_SCHEMA && claim.launch_sha256.len() == 64 {
        Ok(claim)
    } else {
        Err(registration_invalid("Runtime process claim is invalid."))
    }
}

fn effective_uid() -> u64 {
    #[cfg(unix)]
    {
        u64::from(unsafe { libc::geteuid() })
    }
    #[cfg(not(unix))]
    {
        0
    }
}

fn registration_invalid(message: &str) -> CliError {
    CliError::new("RUNTIME_REGISTRATION_INVALID", message)
}

#[cfg(test)]
#[path = "registration/final_review_tests.rs"]
mod final_review_tests;
