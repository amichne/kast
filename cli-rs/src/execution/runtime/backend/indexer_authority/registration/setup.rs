use super::super::ownership::{RuntimeOwnershipSnapshot, reconcile_runtime_ownership};
use super::*;
use std::collections::BTreeSet;

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
    indexers: Vec<ServerInstanceDescriptor>,
}

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
    let has_runtime_artifact = !service_roots.is_empty() || descriptors.registry_has_entries;
    if intent == RuntimeSetupIntent::ForceReset && has_runtime_artifact {
        return Err(CliError::new(
            "SETUP_RUNTIME_NOT_QUIESCENT",
            "Forced setup cannot delete Kast state while a runtime registration or descriptor exists. Run `kastctl developer runtime repair --workspace-root <root> --execute`, then stop the runtime.",
        ));
    }

    let mut roots = service_roots;
    roots.extend(
        descriptors
            .indexers
            .iter()
            .map(|descriptor| canonical_setup_root(&descriptor.workspace_root))
            .collect::<Result<BTreeSet<_>>>()?,
    );
    let mut pinned_release_roots = BTreeSet::new();
    for root in roots {
        let config = KastConfig::load(&root)?;
        collect_runtime_pins(
            reconcile_runtime_ownership(&config, &root)?,
            &mut pinned_release_roots,
        )?;
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
) -> Result<BTreeSet<PathBuf>> {
    let services = paths.runtime_dir.join("services");
    let workspace_entries = match fs::read_dir(&services) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(BTreeSet::new()),
        Err(error) => return Err(error.into()),
    };
    let mut roots = BTreeSet::new();
    for workspace_entry in workspace_entries {
        let workspace_entry = workspace_entry?;
        if !workspace_entry.file_type()?.is_dir() {
            return Err(setup_preflight_error(
                "Runtime services contain an unexpected workspace entry.",
            ));
        }
        let directory_workspace_key = workspace_entry.file_name().to_string_lossy().into_owned();
        if !is_sha256(&directory_workspace_key) {
            return Err(setup_preflight_error(
                "Runtime services contain an invalid workspace key.",
            ));
        }
        let mut registration_found = false;
        for registration_entry in fs::read_dir(workspace_entry.path())? {
            let registration_entry = registration_entry?;
            let name = registration_entry.file_name();
            if name == "active.json" {
                continue;
            }
            if name.to_string_lossy().starts_with('.') || !registration_entry.file_type()?.is_dir()
            {
                return Err(setup_preflight_error(
                    "Runtime services contain an incomplete or unexpected registration.",
                ));
            }
            let (launch, _) = read_owned_json::<ServiceLaunchRegistration>(
                &registration_entry.path().join("launch.json"),
            )?;
            let root = canonical_setup_root(&launch.workspace_root)?;
            if workspace_key(&root) != directory_workspace_key {
                return Err(setup_preflight_error(
                    "Runtime service workspace key does not match its canonical root.",
                ));
            }
            validate_service_registration(&registration_entry.path(), &root)?;
            registration_found = true;
            roots.insert(root);
        }
        if !registration_found && workspace_entry.path().join("active.json").exists() {
            return Err(setup_preflight_error(
                "Runtime services contain an active pointer without a registration.",
            ));
        }
    }
    Ok(roots)
}

fn runtime_setup_descriptors(
    paths: &crate::manifest::ResolvedKastPaths,
) -> Result<RuntimeSetupDescriptors> {
    let entries =
        super::super::super::read_descriptor_elements(&paths.descriptor_dir.join("daemons.json"))?;
    let registry_has_entries = !entries.is_empty();
    let indexers = entries
        .into_iter()
        .filter(|value| {
            value.get("backendName").and_then(Value::as_str)
                == Some(BackendName::Indexer.canonical())
        })
        .map(|value| {
            serde_json::from_value(value).map_err(|error| {
                setup_preflight_error(&format!("Runtime descriptor is invalid: {error}"))
            })
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(RuntimeSetupDescriptors {
        registry_has_entries,
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

fn canonical_setup_root(value: &str) -> Result<PathBuf> {
    fs::canonicalize(value).map_err(|error| {
        setup_preflight_error(&format!(
            "Registered workspace root {value} is unavailable: {error}"
        ))
    })
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
