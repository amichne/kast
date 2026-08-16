use super::*;
use super::process::{ObservedProcess, observe_process};
use super::registration::{
    ValidatedServiceRegistration, read_active_registration, service_workspace_directory,
    validate_service_registration,
};
use super::service_manager::ServiceManagerObservation;

#[derive(Debug, Clone)]
pub(super) enum RuntimeOwnershipSnapshot {
    Absent(AbsentRuntimeOwnership),
    ServiceOwned(Box<ServiceOwnedRuntime>),
    LegacyOwned(Box<LegacyOwnedRuntime>),
    ProvenDead(Vec<DeadServiceRuntime>),
    Conflict(RuntimeOwnershipConflict),
    Ambiguous(RuntimeOwnershipAmbiguity),
}

#[derive(Debug, Clone)]
pub(super) struct AbsentRuntimeOwnership {
    pub workspace_root: PathBuf,
}

#[derive(Debug, Clone)]
pub(super) struct ServiceOwnedRuntime {
    pub workspace_root: PathBuf,
    pub registration: ValidatedServiceRegistration,
    pub manager: ServiceManagerObservation,
    pub process: ObservedProcess,
    pub descriptor: Option<RegisteredDescriptor>,
    pub socket: SocketObservation,
}

#[derive(Debug, Clone)]
pub(super) struct LegacyOwnedRuntime {
    pub workspace_root: PathBuf,
    pub process: ObservedProcess,
    pub descriptor: RegisteredDescriptor,
    pub socket: SocketObservation,
}

#[derive(Debug, Clone)]
pub(super) struct DeadServiceRuntime {
    pub registration: ValidatedServiceRegistration,
    pub descriptor: Option<RegisteredDescriptor>,
    pub socket: SocketObservation,
}

#[derive(Debug, Clone)]
pub(super) struct RuntimeOwnershipConflict {
    pub workspace_root: PathBuf,
    pub runtime_instance_ids: Vec<String>,
}

#[derive(Debug, Clone)]
pub(super) struct RuntimeOwnershipAmbiguity {
    pub workspace_root: PathBuf,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) enum SocketObservation {
    Absent { path: PathBuf },
    Exact {
        path: PathBuf,
        identity: RuntimeSocketFileIdentity,
    },
    PresentUnproven {
        path: PathBuf,
        identity: RuntimeSocketFileIdentity,
    },
}

pub(super) fn reconcile_runtime_ownership(
    config: &KastConfig,
    workspace_root: &Path,
) -> Result<RuntimeOwnershipSnapshot> {
    let canonical_root = fs::canonicalize(workspace_root).map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_INVALID",
            format!(
                "Workspace root {} could not be canonicalized: {error}",
                workspace_root.display()
            ),
        )
    })?;
    let registrations = read_workspace_registrations(config, &canonical_root)?;
    let active = read_active_registration(
        &service_workspace_directory(config, &canonical_root).join("active.json"),
    )?;
    if let Some(active) = &active {
        let matches = registrations.iter().filter(|registration| {
            registration.receipt.runtime_instance_id == active.runtime_instance_id
                && registration.receipt_sha256 == active.receipt_sha256
        });
        if matches.count() != 1 {
            return Ok(RuntimeOwnershipSnapshot::Ambiguous(
                RuntimeOwnershipAmbiguity {
                    workspace_root: canonical_root,
                    reason: "Active runtime pointer does not match exactly one service receipt."
                        .to_string(),
                },
            ));
        }
    }
    let descriptors = find_indexer_descriptors(&config.paths.descriptor_dir, &canonical_root)?;
    let mut live = Vec::new();
    let mut dead = Vec::new();

    for registration in registrations {
        match observe_registered_service(registration, &descriptors, &canonical_root) {
            Ok(RegisteredServiceObservation::Live(owned)) => live.push(owned),
            Ok(RegisteredServiceObservation::Dead(owned)) => dead.push(owned),
            Err(error) => {
                return Ok(RuntimeOwnershipSnapshot::Ambiguous(
                    RuntimeOwnershipAmbiguity {
                        workspace_root: canonical_root,
                        reason: error.message,
                    },
                ));
            }
        }
    }

    if live.len() > 1 {
        return Ok(RuntimeOwnershipSnapshot::Conflict(
            RuntimeOwnershipConflict {
                workspace_root: canonical_root,
                runtime_instance_ids: live
                    .iter()
                    .map(|runtime| runtime.registration.receipt.runtime_instance_id.to_string())
                    .collect(),
            },
        ));
    }
    if let Some(owned) = live.pop() {
        if active.as_ref().is_none_or(|active| {
            active.runtime_instance_id != owned.registration.receipt.runtime_instance_id
        }) {
            return Ok(RuntimeOwnershipSnapshot::Ambiguous(
                RuntimeOwnershipAmbiguity {
                    workspace_root: canonical_root,
                    reason: "A live service is not the active workspace runtime.".to_string(),
                },
            ));
        }
        let owned_id = owned.registration.receipt.runtime_instance_id.to_string();
        let mut unrelated_live_descriptors = 0;
        for descriptor in &descriptors {
            if descriptor.descriptor.runtime_instance_id.as_deref() != Some(owned_id.as_str())
                && observe_process(descriptor.descriptor.pid)?.is_some()
            {
                unrelated_live_descriptors += 1;
            }
        }
        if unrelated_live_descriptors > 0 {
            return Ok(RuntimeOwnershipSnapshot::Conflict(
                RuntimeOwnershipConflict {
                    workspace_root: canonical_root,
                    runtime_instance_ids: descriptors
                        .iter()
                        .filter_map(|descriptor| descriptor.descriptor.runtime_instance_id.clone())
                        .collect(),
                },
            ));
        }
        return Ok(RuntimeOwnershipSnapshot::ServiceOwned(Box::new(owned)));
    }

    let registered_ids = dead
        .iter()
        .map(|runtime| runtime.registration.receipt.runtime_instance_id.to_string())
        .collect::<Vec<_>>();
    let legacy = descriptors
        .into_iter()
        .filter(|descriptor| {
            !descriptor
                .descriptor
                .runtime_instance_id
                .as_ref()
                .is_some_and(|id| registered_ids.contains(id))
        })
        .map(|descriptor| observe_legacy_runtime(descriptor, &canonical_root))
        .collect::<Result<Vec<_>>>()?;
    let live_legacy = legacy
        .into_iter()
        .filter_map(|runtime| runtime)
        .collect::<Vec<_>>();
    if live_legacy.len() > 1 {
        return Ok(RuntimeOwnershipSnapshot::Conflict(
            RuntimeOwnershipConflict {
                workspace_root: canonical_root,
                runtime_instance_ids: live_legacy
                    .iter()
                    .filter_map(|runtime| runtime.descriptor.descriptor.runtime_instance_id.clone())
                    .collect(),
            },
        ));
    }
    if let Some(legacy) = live_legacy.into_iter().next() {
        return Ok(RuntimeOwnershipSnapshot::LegacyOwned(Box::new(legacy)));
    }
    if dead.is_empty() {
        Ok(RuntimeOwnershipSnapshot::Absent(AbsentRuntimeOwnership {
            workspace_root: canonical_root,
        }))
    } else {
        Ok(RuntimeOwnershipSnapshot::ProvenDead(dead))
    }
}

include!("ownership/observation.rs");

fn read_workspace_registrations(
    config: &KastConfig,
    root: &Path,
) -> Result<Vec<ValidatedServiceRegistration>> {
    let directory = service_workspace_directory(config, root);
    let entries = match fs::read_dir(&directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(error) => return Err(error.into()),
    };
    let mut registrations = Vec::new();
    for entry in entries {
        let entry = entry?;
        let name = entry.file_name();
        if name.to_string_lossy().starts_with('.') || name == "active.json" {
            continue;
        }
        if !entry.file_type()?.is_dir() {
            return Err(ownership_error(
                "Runtime service workspace contains an unexpected non-directory entry.",
            ));
        }
        registrations.push(validate_service_registration(&entry.path(), root)?);
    }
    Ok(registrations)
}

fn ownership_error(message: &str) -> CliError {
    CliError::new("RUNTIME_OWNERSHIP_AMBIGUOUS", message)
}
