use super::process::{ManagedProcessIdentity, ObservedProcess, observe_process};
use super::registration::{
    ValidatedServiceRegistration, read_active_registration, service_workspace_directory,
    validate_service_registration,
};
use super::service_manager::ServiceManagerObservation;
use super::*;

#[path = "ownership/missing_workspace.rs"]
mod missing_workspace;
pub(super) use missing_workspace::{
    RegisteredWorkspaceRoot, WorkspaceRootCandidate, require_existing_workspace_root,
};

#[derive(Debug, Clone)]
pub(super) enum RuntimeOwnershipSnapshot {
    Absent(AbsentRuntimeOwnership),
    ServiceOwned(Box<ServiceOwnedRuntime>),
    LegacyOwned(Box<LegacyOwnedRuntime>),
    ProvenDead(ProvenDeadRuntimeOwnership),
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
    pub proven_dead: ProvenDeadRuntimeOwnership,
}

#[derive(Debug, Clone)]
pub(super) struct LegacyOwnedRuntime {
    pub workspace_root: PathBuf,
    pub process: ObservedProcess,
    pub descriptor: RegisteredDescriptor,
    pub socket: SocketObservation,
    pub proven_dead: ProvenDeadRuntimeOwnership,
}

#[derive(Debug, Clone)]
pub(super) struct DeadServiceRuntime {
    pub registration: ValidatedServiceRegistration,
    pub process_claim: Option<super::registration::ServiceProcessClaim>,
    pub active: Option<super::registration::ActiveServiceRegistration>,
    pub descriptor: Option<RegisteredDescriptor>,
    pub socket: SocketObservation,
}

#[derive(Debug, Clone)]
pub(super) struct DeadLegacyRuntime {
    pub descriptor: RegisteredDescriptor,
    pub socket: SocketObservation,
    pub owner_uid: u64,
}

#[derive(Debug, Clone, Default)]
pub(super) struct ProvenDeadRuntimeOwnership {
    pub services: Vec<DeadServiceRuntime>,
    pub legacy: Vec<DeadLegacyRuntime>,
}

impl ProvenDeadRuntimeOwnership {
    pub(super) fn is_empty(&self) -> bool {
        self.services.is_empty() && self.legacy.is_empty()
    }
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
    Absent {
        path: PathBuf,
    },
    Exact {
        path: PathBuf,
        identity: RuntimeSocketFileIdentity,
    },
    OwnedByOtherExact {
        path: PathBuf,
        identity: RuntimeSocketFileIdentity,
    },
    PresentUnproven {
        path: PathBuf,
        identity: RuntimeSocketFileIdentity,
    },
}

#[derive(Debug, Clone)]
pub(super) enum ClaimedProcessObservation {
    Gone,
    Exact(ObservedProcess),
    Reused(ObservedProcess),
}

#[derive(Debug, Clone)]
pub(super) enum DescriptorProcessObservation {
    Gone,
    Exact(ObservedProcess),
    Reused,
}

pub(super) fn observe_claimed_process(
    expected: &ManagedProcessIdentity,
) -> Result<ClaimedProcessObservation> {
    match observe_process(expected.pid)? {
        None => Ok(ClaimedProcessObservation::Gone),
        Some(process) if process.identity == *expected => {
            Ok(ClaimedProcessObservation::Exact(process))
        }
        Some(process) => Ok(ClaimedProcessObservation::Reused(process)),
    }
}

pub(super) fn observe_descriptor_process(
    descriptor: &ServerInstanceDescriptor,
) -> Result<DescriptorProcessObservation> {
    let Some(process) = observe_process(descriptor.pid)? else {
        return Ok(DescriptorProcessObservation::Gone);
    };
    let owner_uid = descriptor.owner_uid.ok_or_else(|| {
        ownership_error("Runtime descriptor has no operating-system owner identity.")
    })?;
    let start_epoch_millis = descriptor
        .process_start_epoch_millis
        .ok_or_else(|| ownership_error("Runtime descriptor has no process-start identity."))?;
    if descriptor.pid == process.identity.pid
        && owner_uid == process.identity.owner_uid
        && start_epoch_millis / 1_000 == process.identity.start_epoch_millis / 1_000
    {
        Ok(DescriptorProcessObservation::Exact(process))
    } else {
        Ok(DescriptorProcessObservation::Reused)
    }
}

pub(super) fn service_descriptor_directory(
    registration: &ValidatedServiceRegistration,
) -> Result<&Path> {
    let path = Path::new(&registration.launch.descriptor_directory);
    if path.is_absolute() {
        Ok(path)
    } else {
        Err(ownership_error(
            "Registered runtime descriptor directory is not absolute.",
        ))
    }
}

pub(super) fn reconcile_runtime_ownership(
    config: &KastConfig,
    workspace_root: &Path,
) -> Result<RuntimeOwnershipSnapshot> {
    let canonical_root = require_existing_workspace_root(workspace_root)?;
    let registrations = read_workspace_registrations(config, &canonical_root)?;
    reconcile_validated_runtime_ownership(config, canonical_root, registrations)
}

pub(super) fn reconcile_registered_runtime_ownership(
    config: &KastConfig,
    workspace_root: &RegisteredWorkspaceRoot,
) -> Result<RuntimeOwnershipSnapshot> {
    let (root, registrations) = workspace_root.revalidate(config)?;
    reconcile_validated_runtime_ownership(config, root, registrations)
}

fn reconcile_validated_runtime_ownership(
    config: &KastConfig,
    canonical_root: PathBuf,
    registrations: Vec<ValidatedServiceRegistration>,
) -> Result<RuntimeOwnershipSnapshot> {
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
    let legacy_descriptors =
        find_indexer_descriptors(&config.paths.descriptor_dir, &canonical_root)?;
    let registered_ids = registrations
        .iter()
        .map(|registration| registration.receipt.runtime_instance_id.to_string())
        .collect::<Vec<_>>();
    let caller_registered_ids = registrations
        .iter()
        .map(|registration| {
            service_descriptor_directory(registration).map(|directory| {
                (directory == config.paths.descriptor_dir.as_path())
                    .then(|| registration.receipt.runtime_instance_id.to_string())
            })
        })
        .collect::<Result<Vec<_>>>()?
        .into_iter()
        .flatten()
        .collect::<Vec<_>>();
    let mut live = Vec::new();
    let mut dead = Vec::new();

    for registration in registrations {
        let descriptors = find_indexer_descriptors(
            service_descriptor_directory(&registration)?,
            &canonical_root,
        )?;
        match observe_registered_service(
            registration,
            &descriptors,
            &canonical_root,
            active.as_ref(),
        ) {
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

    if legacy_descriptors.iter().any(|descriptor| {
        descriptor
            .descriptor
            .runtime_instance_id
            .as_ref()
            .is_some_and(|id| registered_ids.contains(id) && !caller_registered_ids.contains(id))
    }) {
        return Ok(RuntimeOwnershipSnapshot::Ambiguous(
            RuntimeOwnershipAmbiguity {
                workspace_root: canonical_root,
                reason: "A service descriptor exists outside its persisted descriptor directory."
                    .to_string(),
            },
        ));
    }
    let legacy = legacy_descriptors
        .into_iter()
        .filter(|descriptor| {
            !descriptor
                .descriptor
                .runtime_instance_id
                .as_ref()
                .is_some_and(|id| caller_registered_ids.contains(id))
        })
        .map(|descriptor| observe_legacy_runtime(descriptor, &canonical_root))
        .collect::<Result<Vec<_>>>()?;
    let mut live_legacy = Vec::new();
    let mut dead_legacy = Vec::new();
    for runtime in legacy {
        match runtime {
            LegacyRuntimeObservation::Live(runtime) => live_legacy.push(runtime),
            LegacyRuntimeObservation::Dead(runtime) => dead_legacy.push(runtime),
        }
    }
    if live.len() + live_legacy.len() > 1 {
        let mut claimants = live
            .iter()
            .map(|runtime| runtime.registration.receipt.runtime_instance_id.to_string())
            .collect::<Vec<_>>();
        claimants.extend(live_legacy.iter().map(legacy_runtime_id));
        return Ok(RuntimeOwnershipSnapshot::Conflict(
            RuntimeOwnershipConflict {
                workspace_root: canonical_root,
                runtime_instance_ids: claimants,
            },
        ));
    }
    let proven_dead = ProvenDeadRuntimeOwnership {
        services: dead,
        legacy: dead_legacy,
    };
    if let Some(mut owned) = live.pop() {
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
        owned.proven_dead = proven_dead;
        return Ok(RuntimeOwnershipSnapshot::ServiceOwned(Box::new(owned)));
    }
    if let Some(mut legacy) = live_legacy.pop() {
        legacy.proven_dead = proven_dead;
        return Ok(RuntimeOwnershipSnapshot::LegacyOwned(Box::new(legacy)));
    }
    if proven_dead.services.is_empty() && proven_dead.legacy.is_empty() {
        let unregistered =
            super::service_manager::discover_unregistered_runtime_processes(&canonical_root)?;
        if !unregistered.is_empty() {
            return Ok(RuntimeOwnershipSnapshot::Ambiguous(
                RuntimeOwnershipAmbiguity {
                    workspace_root: canonical_root,
                    reason: format!(
                        "Live indexer process has no persisted ownership evidence: {}. Stop it before starting a replacement.",
                        unregistered
                            .iter()
                            .map(|process| process.evidence())
                            .collect::<Vec<_>>()
                            .join(", ")
                    ),
                },
            ));
        }
        Ok(RuntimeOwnershipSnapshot::Absent(AbsentRuntimeOwnership {
            workspace_root: canonical_root,
        }))
    } else {
        Ok(RuntimeOwnershipSnapshot::ProvenDead(proven_dead))
    }
}

fn legacy_runtime_id(runtime: &LegacyOwnedRuntime) -> String {
    let descriptor = &runtime.descriptor.descriptor;
    descriptor
        .runtime_instance_id
        .clone()
        .unwrap_or_else(|| runtime.descriptor.id.clone())
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
        let file_type = match entry.file_type() {
            Ok(file_type) => file_type,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        if !file_type.is_dir() {
            return Err(ownership_error(
                "Runtime service workspace contains an unexpected non-directory entry.",
            ));
        }
        match validate_service_registration(&entry.path(), root) {
            Ok(registration) => registrations.push(registration),
            Err(error) if error.code == "RUNTIME_REGISTRATION_MISSING" => {
                return Err(ownership_error(&error.message));
            }
            Err(error) => return Err(error),
        }
    }
    Ok(registrations)
}

fn ownership_error(message: &str) -> CliError {
    CliError::new("RUNTIME_OWNERSHIP_AMBIGUOUS", message)
}
