use super::ownership::{
    DeadLegacyRuntime, DeadServiceRuntime, LegacyOwnedRuntime, RuntimeOwnershipSnapshot,
    ServiceOwnedRuntime, SocketObservation, reconcile_runtime_ownership,
};
use super::process::{observe_process, signal_process, wait_until_gone};
use super::registration::{
    ValidatedServiceRegistration, validate_entrypoint_registration, write_process_claim,
};
use super::*;

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
    let _install_use_lock = super::registration::storage::InstallUseLock::acquire()?;
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

pub(super) fn stop_workspace_runtime(args: RuntimeArgs) -> Result<DaemonStopResult> {
    let _install_use_lock = super::registration::storage::InstallUseLock::acquire()?;
    let workspace_root = config::resolve_workspace_root(args.workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    let _lock = WorkspaceLaunchLock::acquire(&config, &workspace_root)?;
    loop {
        match reconcile_runtime_ownership(&config, &workspace_root)? {
            RuntimeOwnershipSnapshot::Absent(_) => {
                return Ok(empty_stop_result(&workspace_root));
            }
            RuntimeOwnershipSnapshot::ServiceOwned(owned) => {
                if owned.proven_dead.is_empty() {
                    return stop_service_runtime(&config, *owned);
                }
                cleanup_proven_dead(&config, &owned.proven_dead)?;
            }
            RuntimeOwnershipSnapshot::LegacyOwned(owned) => {
                if owned.proven_dead.is_empty() {
                    return stop_legacy_runtime(&config, *owned);
                }
                cleanup_proven_dead(&config, &owned.proven_dead)?;
            }
            RuntimeOwnershipSnapshot::ProvenDead(dead) => {
                cleanup_proven_dead(&config, &dead)?;
            }
            RuntimeOwnershipSnapshot::Conflict(conflict) => {
                return Err(CliError::new(
                    "RUNTIME_OWNERSHIP_CONFLICT",
                    format!(
                        "More than one live runtime owns {}: {}.",
                        conflict.workspace_root.display(),
                        conflict.runtime_instance_ids.join(", ")
                    ),
                ));
            }
            RuntimeOwnershipSnapshot::Ambiguous(ambiguity) => {
                return Err(CliError::new(
                    "RUNTIME_OWNERSHIP_AMBIGUOUS",
                    format!(
                        "{}: {}",
                        ambiguity.workspace_root.display(),
                        ambiguity.reason
                    ),
                ));
            }
        }
    }
}

include!("ownership/lease_stop.rs");

fn empty_stop_result(workspace_root: &Path) -> DaemonStopResult {
    DaemonStopResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: BackendName::Indexer.canonical().to_string(),
        stopped: false,
        stopped_count: 0,
        descriptor_path: None,
        pid: None,
        forced: false,
        candidates: vec![],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    }
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
        RuntimeOwnershipSnapshot::ServiceOwned(owned) => {
            let durable = owned.manager
                == super::service_manager::ServiceManagerObservation::Running(
                    owned.process.identity.pid,
                );
            let runtime_instance_id = owned.registration.receipt.runtime_instance_id;
            let actions = repair_dead_artifacts(config, owned.proven_dead, execute)?;
            let state = if !durable {
                RuntimeRepairState::Blocked
            } else if execute || actions.is_empty() {
                RuntimeRepairState::Healthy
            } else {
                RuntimeRepairState::Repairable
            };
            let blockers = if durable {
                vec![]
            } else {
                vec![format!(
                    "Live runtime {runtime_instance_id} is not attached to its registered service manager. Stop it before starting a replacement."
                )]
            };
            (state, actions, blockers)
        }
        RuntimeOwnershipSnapshot::LegacyOwned(legacy) => {
            let pid = legacy.process.identity.pid;
            let actions = repair_dead_artifacts(config, legacy.proven_dead, execute)?;
            (
                RuntimeRepairState::Blocked,
                actions,
                vec![format!(
                    "Legacy runtime {} requires lifecycle migration.",
                    pid
                )],
            )
        }
        RuntimeOwnershipSnapshot::ProvenDead(dead) => (
            if execute {
                RuntimeRepairState::Clean
            } else {
                RuntimeRepairState::Repairable
            },
            repair_dead_artifacts(config, dead, execute)?,
            vec![],
        ),
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

fn repair_dead_artifacts(
    config: &KastConfig,
    mut dead: super::ownership::ProvenDeadRuntimeOwnership,
    execute: bool,
) -> Result<Vec<RuntimeRepairAction>> {
    sort_dead_services_for_cleanup(&mut dead.services);
    let mut actions = Vec::with_capacity(dead.services.len() + dead.legacy.len());
    for runtime in dead.services {
        let runtime_instance_id = runtime.registration.receipt.runtime_instance_id.to_string();
        if execute {
            cleanup_dead_registration(config, &runtime)?;
        }
        actions.push(RuntimeRepairAction {
            action: "REMOVE_PROVEN_DEAD_RUNTIME",
            runtime_instance_id,
            executed: execute,
        });
    }
    for runtime in dead.legacy {
        let runtime_instance_id = runtime
            .descriptor
            .descriptor
            .runtime_instance_id
            .clone()
            .unwrap_or_else(|| runtime.descriptor.id.clone());
        if execute {
            cleanup_dead_legacy(config, &runtime)?;
        }
        actions.push(RuntimeRepairAction {
            action: "REMOVE_PROVEN_DEAD_LEGACY_RUNTIME",
            runtime_instance_id,
            executed: execute,
        });
    }
    Ok(actions)
}

pub(super) fn cleanup_dead_services(
    config: &KastConfig,
    services: &[DeadServiceRuntime],
) -> Result<()> {
    let mut ordered = services.iter().collect::<Vec<_>>();
    ordered.sort_by_key(|runtime| dead_service_is_active(runtime));
    for runtime in ordered {
        cleanup_dead_registration(config, runtime)?;
    }
    Ok(())
}

pub(super) fn cleanup_proven_dead(
    config: &KastConfig,
    dead: &super::ownership::ProvenDeadRuntimeOwnership,
) -> Result<()> {
    cleanup_dead_services(config, &dead.services)?;
    for runtime in &dead.legacy {
        cleanup_dead_legacy(config, runtime)?;
    }
    Ok(())
}

fn sort_dead_services_for_cleanup(services: &mut [DeadServiceRuntime]) {
    services.sort_by_key(dead_service_is_active);
}

fn dead_service_is_active(runtime: &DeadServiceRuntime) -> bool {
    runtime.active.as_ref().is_some_and(|active| {
        active.runtime_instance_id == runtime.registration.receipt.runtime_instance_id
    })
}

pub(super) fn stop_service_runtime(
    config: &KastConfig,
    owned: ServiceOwnedRuntime,
) -> Result<DaemonStopResult> {
    let owned = revalidate_service_runtime(config, &owned)?;
    signal_exact_observed_process(&owned.process, &owned.socket, false)?;
    let mut forced = false;
    if !wait_until_gone(&owned.process.identity, Duration::from_secs(10))? {
        signal_exact_observed_process(&owned.process, &owned.socket, true)?;
        forced = true;
        if !wait_until_gone(&owned.process.identity, Duration::from_secs(5))? {
            return Err(CliError::new(
                "RUNTIME_STOP_FAILED",
                "The exact registered runtime did not stop.",
            ));
        }
    }
    let descriptor = owned.descriptor.clone();
    let process_claim = super::registration::read_process_claim(&owned.registration.directory)?;
    let active = super::registration::read_active_registration(
        &owned
            .registration
            .directory
            .parent()
            .ok_or_else(runtime_identity_mismatch)?
            .join("active.json"),
    )?;
    cleanup_dead_registration(
        config,
        &DeadServiceRuntime {
            registration: owned.registration,
            process_claim,
            active,
            descriptor: descriptor.clone(),
            socket: owned.socket,
        },
    )?;
    Ok(stop_result(
        &owned.workspace_root,
        descriptor.as_ref(),
        owned.process.identity.pid,
        forced,
    ))
}

pub(super) fn stop_legacy_runtime(
    config: &KastConfig,
    owned: LegacyOwnedRuntime,
) -> Result<DaemonStopResult> {
    let owned = revalidate_legacy_runtime(config, &owned)?;
    signal_exact_observed_process(&owned.process, &owned.socket, false)?;
    let mut forced = false;
    if !wait_until_gone(&owned.process.identity, Duration::from_secs(5))? {
        signal_exact_observed_process(&owned.process, &owned.socket, true)?;
        forced = true;
        if !wait_until_gone(&owned.process.identity, Duration::from_secs(5))? {
            return Err(CliError::new(
                "RUNTIME_STOP_FAILED",
                "The exact legacy Kast runtime did not stop.",
            ));
        }
    }
    delete_descriptor(&config.paths.descriptor_dir, &owned.descriptor.descriptor)?;
    remove_exact_socket(&owned.socket, owned.process.identity.owner_uid)?;
    Ok(stop_result(
        &owned.workspace_root,
        Some(&owned.descriptor),
        owned.process.identity.pid,
        forced,
    ))
}

include!("ownership/stop_evidence.rs");

fn stop_result(
    workspace_root: &Path,
    descriptor: Option<&RegisteredDescriptor>,
    pid: u64,
    forced: bool,
) -> DaemonStopResult {
    let descriptor_path = descriptor.map(|value| value.id.clone());
    let action = descriptor.map(|value| RuntimeStopAction {
        backend_name: BackendName::Indexer.canonical().to_string(),
        descriptor_path: value.id.clone(),
        pid,
        pid_alive: true,
        reachable: false,
        lifecycle_accepted: true,
        lifecycle_method: Some("OWNERSHIP_SNAPSHOT".to_string()),
        lifecycle_action: Some("STOP".to_string()),
        terminated: true,
        descriptor_deleted: true,
        forced,
        skipped_reason: None,
        schema_version: SCHEMA_VERSION,
    });
    DaemonStopResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: BackendName::Indexer.canonical().to_string(),
        stopped: true,
        stopped_count: 1,
        descriptor_path,
        pid: Some(pid),
        forced,
        candidates: action.into_iter().collect(),
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    }
}

include!("ownership/cleanup.rs");
include!("ownership/entrypoint.rs");
