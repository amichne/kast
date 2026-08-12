use super::super::ownership::{
    RegisteredWorkspaceRoot, RuntimeOwnershipSnapshot, WorkspaceRootCandidate,
    reconcile_registered_runtime_ownership,
};
use super::*;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RuntimeSetupIntent<'a> {
    ReconcileCurrent,
    ReplaceCandidate { candidate_release_root: &'a Path },
    ForceReset,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RuntimeSetupAuthorization {
    pinned_release_roots: BTreeSet<PathBuf>,
}

#[derive(Debug)]
struct RuntimeSetupDescriptors {
    registry_has_entries: bool,
    registry_has_unclassified_entries: bool,
    indexers: Vec<ServerInstanceDescriptor>,
}

#[derive(Debug)]
struct RuntimeSetupObservation {
    config: KastConfig,
    root: RegisteredWorkspaceRoot,
    ownership: RuntimeOwnershipSnapshot,
}

include!("setup/force_reset.rs");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RegistrationPublicationTemporary {
    Staging(Uuid),
    ActivePointer,
}

include!("setup/registry_normalization.rs");

impl RuntimeSetupAuthorization {
    pub(crate) fn pinned_release_roots(&self) -> &BTreeSet<PathBuf> {
        &self.pinned_release_roots
    }

    pub(crate) fn permits_release_removal(&self, release_root: &Path) -> bool {
        !self
            .pinned_release_roots
            .contains(&canonical_release_identity(release_root))
    }
}

fn canonical_release_identity(path: &Path) -> PathBuf {
    fs::canonicalize(path)
        .or_else(|_| {
            let parent = path.parent().ok_or(std::io::ErrorKind::NotFound)?;
            let name = path.file_name().ok_or(std::io::ErrorKind::NotFound)?;
            Ok::<_, std::io::Error>(fs::canonicalize(parent)?.join(name))
        })
        .unwrap_or_else(|_| config::normalize(path.to_path_buf()))
}

/// The caller must hold the exclusive `setup.lock` for the complete preflight
/// and every setup mutation authorized by the returned value.
pub(crate) fn preflight_runtime_setup(
    paths: &crate::manifest::ResolvedKastPaths,
    intent: RuntimeSetupIntent<'_>,
) -> Result<RuntimeSetupAuthorization> {
    let service_roots = registered_service_roots(paths)?;
    let descriptors = runtime_setup_descriptors(paths)?;
    let descriptor_roots = descriptors
        .indexers
        .iter()
        .map(|descriptor| descriptor_setup_root(&descriptor.workspace_root, &service_roots))
        .collect::<Result<Vec<_>>>()?;
    let mut roots = service_roots;
    for candidate in descriptor_roots {
        roots
            .entry(candidate.path().to_path_buf())
            .or_insert(candidate);
    }
    let mut observations = Vec::new();
    for (_, candidate) in roots {
        let config = KastConfig::load(candidate.path())?;
        let root = RegisteredWorkspaceRoot::admit(&config, candidate)?;
        let ownership =
            reconcile_registered_runtime_ownership(&config, &root).map_err(|error| {
                if intent == RuntimeSetupIntent::ForceReset {
                    force_runtime_reconciliation_blocked(error)
                } else {
                    error
                }
            })?;
        observations.push(RuntimeSetupObservation {
            config,
            root,
            ownership,
        });
    }
    if intent == RuntimeSetupIntent::ForceReset {
        return ForceResetCleanup::admit(
            observations,
            descriptors.registry_has_unclassified_entries,
        )?
        .execute(paths);
    }
    let mut pinned_release_roots = BTreeSet::new();
    for observation in observations {
        collect_runtime_pins(observation.ownership, &mut pinned_release_roots)?;
    }

    let authorization = RuntimeSetupAuthorization {
        pinned_release_roots,
    };
    if let RuntimeSetupIntent::ReplaceCandidate {
        candidate_release_root,
    } = intent
        && !authorization.permits_release_removal(candidate_release_root)
    {
        return Err(CliError::new(
            "SETUP_RUNTIME_RELEASE_PINNED",
            format!(
                "Setup cannot replace {} because a registered runtime pins that release.",
                candidate_release_root.display()
            ),
        ));
    }
    Ok(authorization)
}

fn registered_service_roots(
    paths: &crate::manifest::ResolvedKastPaths,
) -> Result<BTreeMap<PathBuf, WorkspaceRootCandidate>> {
    let services = paths.runtime_dir.join("services");
    let workspace_entries = match fs::read_dir(&services) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(BTreeMap::new()),
        Err(error) => return Err(error.into()),
    };
    let mut roots = BTreeMap::new();
    for workspace_entry in workspace_entries {
        let workspace = match classify_runtime_services_entry(workspace_entry?)? {
            RuntimeServicesEntry::Workspace(workspace) => workspace,
            RuntimeServicesEntry::Noise(path) => {
                remove_runtime_registry_noise(&path)?;
                continue;
            }
        };
        let RuntimeWorkspaceRegistryDirectory {
            path: workspace_path,
            workspace_key: directory_workspace_key,
        } = workspace;
        let mut registration_found = false;
        for registration_entry in fs::read_dir(&workspace_path)? {
            let registration_path = match classify_runtime_workspace_entry(registration_entry?)? {
                RuntimeWorkspaceEntry::ActivePointer => continue,
                RuntimeWorkspaceEntry::PublicationTemporary { path, temporary } => {
                    recover_registration_publication_temporary(&workspace_path, &path, temporary)?;
                    continue;
                }
                RuntimeWorkspaceEntry::Registration(path) => path,
                RuntimeWorkspaceEntry::Noise(path) => {
                    remove_runtime_registry_noise(&path)?;
                    continue;
                }
            };
            let (launch, _) = read_owned_json::<ServiceLaunchRegistration>(
                &registration_path.join("launch.json"),
            )?;
            let candidate = registered_setup_root(&launch.workspace_root)?;
            let root = candidate.path();
            if workspace_key(root) != directory_workspace_key {
                return Err(setup_preflight_error(
                    "Runtime service workspace key does not match its canonical root.",
                ));
            }
            validate_service_registration(&registration_path, root)?;
            registration_found = true;
            roots.entry(root.to_path_buf()).or_insert(candidate);
        }
        if !registration_found && workspace_path.join("active.json").exists() {
            return Err(setup_preflight_error(
                "Runtime services contain an active pointer without a registration.",
            ));
        }
    }
    Ok(roots)
}

fn registration_publication_temporary(
    name: &std::ffi::OsStr,
) -> Option<RegistrationPublicationTemporary> {
    let name = name.to_str()?;
    if let Some(value) = name.strip_prefix(".staging-") {
        return canonical_uuid(value).map(RegistrationPublicationTemporary::Staging);
    }
    name.strip_prefix(".runtime-")
        .and_then(|value| value.strip_suffix(".tmp"))
        .and_then(canonical_uuid)
        .map(|_| RegistrationPublicationTemporary::ActivePointer)
}

fn canonical_uuid(value: &str) -> Option<Uuid> {
    Uuid::parse_str(value).ok().filter(|uuid| {
        uuid.hyphenated().to_string() == value
            && uuid.get_version_num() == 4
            && uuid.get_variant() == uuid::Variant::RFC4122
    })
}

fn recover_registration_publication_temporary(
    workspace_directory: &Path,
    path: &Path,
    temporary: RegistrationPublicationTemporary,
) -> Result<()> {
    let recovered = (|| -> Result<()> {
        match temporary {
            RegistrationPublicationTemporary::Staging(runtime_instance_id) => {
                require_owned_directory(path)?;
                let final_directory = workspace_directory.join(runtime_instance_id.to_string());
                match fs::symlink_metadata(&final_directory) {
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                    Err(error) => return Err(error.into()),
                    Ok(_) => {
                        return Err(setup_preflight_error(
                            "Runtime services contain both staged and final copies of one registration.",
                        ));
                    }
                }
                fs::remove_dir_all(path)?;
            }
            RegistrationPublicationTemporary::ActivePointer => {
                read_stable_file(path, true)?;
                fs::remove_file(path)?;
            }
        }
        sync_parent(path)
    })();
    recovered.map_err(|error| {
        setup_preflight_error(&format!(
            "Runtime registration publication temporary is not safe to recover: {}",
            error.message
        ))
    })
}

fn runtime_setup_descriptors(
    paths: &crate::manifest::ResolvedKastPaths,
) -> Result<RuntimeSetupDescriptors> {
    let entries =
        super::super::super::read_descriptor_elements(&paths.descriptor_dir.join("daemons.json"))?;
    let registry_has_entries = !entries.is_empty();
    let mut registry_has_unclassified_entries = false;
    let mut indexers = Vec::new();
    for value in entries {
        if value.get("backendName").and_then(Value::as_str)
            == Some(BackendName::Indexer.canonical())
        {
            indexers.push(serde_json::from_value(value).map_err(|error| {
                setup_preflight_error(&format!("Runtime descriptor is invalid: {error}"))
            })?);
        } else {
            registry_has_unclassified_entries = true;
        }
    }
    Ok(RuntimeSetupDescriptors {
        registry_has_entries,
        registry_has_unclassified_entries,
        indexers,
    })
}

fn collect_runtime_pins(
    snapshot: RuntimeOwnershipSnapshot,
    pins: &mut BTreeSet<PathBuf>,
) -> Result<()> {
    match snapshot {
        RuntimeOwnershipSnapshot::ServiceOwned(runtime) => {
            insert_registration_pin(&runtime.registration, pins);
            insert_dead_pins(&runtime.proven_dead, pins);
        }
        RuntimeOwnershipSnapshot::ProvenDead(dead) => {
            insert_dead_pins(&dead, pins);
        }
        RuntimeOwnershipSnapshot::LegacyOwned(runtime) => {
            insert_dead_pins(&runtime.proven_dead, pins);
        }
        RuntimeOwnershipSnapshot::Absent(_) => {}
        RuntimeOwnershipSnapshot::Conflict(conflict) => {
            return Err(setup_preflight_error(&format!(
                "Workspace {} has duplicate live runtimes: {}.",
                conflict.workspace_root.display(),
                conflict.runtime_instance_ids.join(", ")
            )));
        }
        RuntimeOwnershipSnapshot::Ambiguous(ambiguity) => {
            return Err(setup_preflight_error(&format!(
                "Workspace {} has ambiguous runtime ownership: {}",
                ambiguity.workspace_root.display(),
                ambiguity.reason
            )));
        }
    }
    Ok(())
}

fn insert_dead_pins(
    dead: &super::super::ownership::ProvenDeadRuntimeOwnership,
    pins: &mut BTreeSet<PathBuf>,
) {
    for runtime in &dead.services {
        insert_registration_pin(&runtime.registration, pins);
    }
}

fn insert_registration_pin(
    registration: &ValidatedServiceRegistration,
    pins: &mut BTreeSet<PathBuf>,
) {
    if let Some(pin) = &registration.launch.installed_release {
        pins.insert(config::normalize(PathBuf::from(&pin.release_root)));
    }
}

fn registered_setup_root(value: &str) -> Result<WorkspaceRootCandidate> {
    WorkspaceRootCandidate::resolve(Path::new(value)).map_err(|error| {
        setup_preflight_error(&format!(
            "Registered workspace root {value} cannot be reconciled: {}",
            error.message
        ))
    })
}

fn descriptor_setup_root(
    value: &str,
    registered_roots: &BTreeMap<PathBuf, WorkspaceRootCandidate>,
) -> Result<WorkspaceRootCandidate> {
    let candidate = WorkspaceRootCandidate::resolve(Path::new(value)).map_err(|error| {
        setup_preflight_error(&format!(
            "Runtime descriptor workspace root {value} cannot be reconciled: {}",
            error.message
        ))
    })?;
    match &candidate {
        WorkspaceRootCandidate::ExistingCanonical(_) => Ok(candidate),
        WorkspaceRootCandidate::MissingNormalized(root) => {
            registered_roots.get(root).cloned().ok_or_else(|| {
                setup_preflight_error(
                    "A missing descriptor root has no registered service evidence.",
                )
            })
        }
    }
}

fn is_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn setup_preflight_error(message: &str) -> CliError {
    CliError::new("SETUP_RUNTIME_PREFLIGHT_BLOCKED", message)
}

#[cfg(test)]
#[path = "setup/tests.rs"]
mod tests;
