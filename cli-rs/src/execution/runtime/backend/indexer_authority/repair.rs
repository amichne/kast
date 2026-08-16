use super::*;
use super::ownership::{RuntimeOwnershipSnapshot, reconcile_runtime_ownership};
use super::process::observe_process;
use super::registration::{
    ValidatedServiceRegistration, validate_entrypoint_registration, write_process_claim,
};
use std::os::unix::process::CommandExt as _;
use std::process::Stdio;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RuntimeRepairResult {
    workspace_root: String,
    mode: RuntimeRepairMode,
    state: RuntimeRepairState,
    actions: Vec<RuntimeRepairAction>,
    blockers: Vec<String>,
    schema_version: u32,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RuntimeRepairMode {
    DryRun,
    Execute,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RuntimeRepairState {
    Clean,
    Healthy,
    Repairable,
    Blocked,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeRepairAction {
    action: &'static str,
    runtime_instance_id: String,
    executed: bool,
}

pub(crate) fn workspace_repair(args: RuntimeRepairArgs) -> Result<RuntimeRepairResult> {
    let workspace_root = fs::canonicalize(&args.workspace_root).map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_INVALID",
            format!(
                "Workspace root {} could not be canonicalized: {error}",
                args.workspace_root.display()
            ),
        )
    })?;
    let config = KastConfig::load(&workspace_root)?;
    let _lock = args
        .execute
        .then(|| WorkspaceLaunchLock::acquire(&config, &workspace_root))
        .transpose()?;
    let snapshot = reconcile_runtime_ownership(&config, &workspace_root)?;
    repair_snapshot(&config, &workspace_root, snapshot, args.execute)
}

fn repair_snapshot(
    config: &KastConfig,
    workspace_root: &Path,
    snapshot: RuntimeOwnershipSnapshot,
    execute: bool,
) -> Result<RuntimeRepairResult> {
    let mode = if execute {
        RuntimeRepairMode::Execute
    } else {
        RuntimeRepairMode::DryRun
    };
    let (state, actions, blockers) = match snapshot {
        RuntimeOwnershipSnapshot::Absent(_) => (RuntimeRepairState::Clean, vec![], vec![]),
        RuntimeOwnershipSnapshot::ServiceOwned(_) | RuntimeOwnershipSnapshot::LegacyOwned(_) => {
            (RuntimeRepairState::Healthy, vec![], vec![])
        }
        RuntimeOwnershipSnapshot::ProvenDead(dead) => {
            let mut actions = Vec::with_capacity(dead.len());
            for runtime in dead {
                let runtime_instance_id = runtime
                    .registration
                    .receipt
                    .runtime_instance_id
                    .to_string();
                if execute {
                    cleanup_dead_registration(config, &runtime.registration, runtime.descriptor)?;
                }
                actions.push(RuntimeRepairAction {
                    action: "REMOVE_PROVEN_DEAD_RUNTIME",
                    runtime_instance_id,
                    executed: execute,
                });
            }
            (RuntimeRepairState::Repairable, actions, vec![])
        }
        RuntimeOwnershipSnapshot::Conflict(conflict) => (
            RuntimeRepairState::Blocked,
            vec![],
            vec![format!(
                "Duplicate live runtime ownership: {}",
                conflict.runtime_instance_ids.join(", ")
            )],
        ),
        RuntimeOwnershipSnapshot::Ambiguous(ambiguous) => {
            (RuntimeRepairState::Blocked, vec![], vec![ambiguous.reason])
        }
    };
    Ok(RuntimeRepairResult {
        workspace_root: workspace_root.display().to_string(),
        mode,
        state,
        actions,
        blockers,
        schema_version: SCHEMA_VERSION,
    })
}

fn cleanup_dead_registration(
    config: &KastConfig,
    registration: &ValidatedServiceRegistration,
    descriptor: Option<RegisteredDescriptor>,
) -> Result<()> {
    match super::service_manager::inspect(&registration.receipt.manager)? {
        super::service_manager::ServiceManagerObservation::Running(_) => {
            return Err(CliError::new(
                "RUNTIME_OWNERSHIP_CHANGED",
                "A runtime became live while repair was executing.",
            ));
        }
        super::service_manager::ServiceManagerObservation::Registered
        | super::service_manager::ServiceManagerObservation::Absent => {
            super::service_manager::unregister(&registration.receipt.manager)?;
        }
    }
    if let Some(descriptor) = descriptor {
        delete_descriptor(&config.paths.descriptor_dir, &descriptor.descriptor)?;
    }
    remove_exact_socket(&registration.launch.socket_path)?;
    fs::remove_dir_all(&registration.directory)?;
    let active_path = registration
        .directory
        .parent()
        .ok_or_else(runtime_identity_mismatch)?
        .join("active.json");
    if super::registration::read_active_registration(&active_path)?
        .is_some_and(|active| active.runtime_instance_id == registration.receipt.runtime_instance_id)
    {
        fs::remove_file(active_path)?;
    }
    Ok(())
}

fn remove_exact_socket(value: &str) -> Result<()> {
    use std::os::unix::fs::FileTypeExt as _;
    let path = Path::new(value);
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_socket() => fs::remove_file(path).map_err(Into::into),
        Ok(_) => Err(CliError::new(
            "RUNTIME_OWNERSHIP_CHANGED",
            "Runtime socket path changed to a non-socket object.",
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

pub(crate) fn service_entrypoint(args: RuntimeServiceEntrypointArgs) -> Result<()> {
    let registration =
        validate_entrypoint_registration(&args.registration, &args.registration_sha256)?;
    let current_executable = fs::canonicalize(std::env::current_exe()?)?;
    if current_executable != fs::canonicalize(&registration.launch.launcher_path)?
        || crate::manifest::sha256_file(&current_executable)?
            != registration.launch.launcher_sha256
    {
        return Err(CliError::new(
            "RUNTIME_REGISTRATION_INVALID",
            "Service entrypoint executable does not match the immutable registration.",
        ));
    }
    let current = observe_process(u64::from(std::process::id()))?.ok_or_else(|| {
        CliError::new(
            "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE",
            "Service entrypoint cannot observe its own process identity.",
        )
    })?;
    write_process_claim(
        &registration.directory,
        &registration.receipt.launch_sha256,
        current.identity,
    )?;
    let executable = registration
        .launch
        .command
        .first()
        .ok_or_else(|| CliError::new("RUNTIME_REGISTRATION_INVALID", "Indexer command is empty."))?;
    let error = Command::new(executable)
        .args(&registration.launch.command[1..])
        .current_dir(&registration.launch.working_directory)
        .env_clear()
        .envs(&registration.launch.environment)
        .stdin(Stdio::null())
        .exec();
    Err(CliError::new(
        "RUNTIME_SERVICE_EXEC_FAILED",
        format!("Runtime service could not execute the registered indexer: {error}"),
    ))
}
