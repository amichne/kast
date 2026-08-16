enum RegisteredServiceObservation {
    Live(ServiceOwnedRuntime),
    Dead(DeadServiceRuntime),
}

fn observe_registered_service(
    registration: ValidatedServiceRegistration,
    descriptors: &[RegisteredDescriptor],
    root: &Path,
) -> Result<RegisteredServiceObservation> {
    let manager = super::service_manager::inspect(&registration.receipt.manager)?;
    let claim = super::registration::read_process_claim(&registration.directory)?;
    let descriptor = matching_descriptor(descriptors, &registration, None)?;
    let manager_pid = match manager {
        ServiceManagerObservation::Running(pid) => Some(pid),
        ServiceManagerObservation::Registered | ServiceManagerObservation::Absent => None,
    };
    let mut claimed_pids = [
        claim.as_ref().map(|claim| claim.process.pid),
        manager_pid,
        descriptor.as_ref().map(|value| value.descriptor.pid),
    ]
    .into_iter()
    .flatten()
    .collect::<Vec<_>>();
    claimed_pids.sort_unstable();
    claimed_pids.dedup();
    if claimed_pids.len() > 1 {
        return Err(ownership_error(
            "Service manager, process claim, and descriptor disagree about the runtime PID.",
        ));
    }
    let pid = claimed_pids.into_iter().next();
    let Some(pid) = pid else {
        return dead_registration(registration, descriptor);
    };
    if manager_pid.is_some_and(|manager_pid| manager_pid != pid) {
        return Err(ownership_error(
            "Service manager PID does not match the process claim.",
        ));
    }
    let Some(process) = observe_process(pid)? else {
        return dead_registration(registration, descriptor);
    };
    if process.identity.owner_uid != registration.launch.owner_uid
        || claim
            .as_ref()
            .is_some_and(|claim| claim.launch_sha256 != registration.receipt.launch_sha256)
        || claim
            .as_ref()
            .is_some_and(|claim| claim.process != process.identity)
        || process.command != registration.launch.command
    {
        return Err(ownership_error(
            "Runtime process UID, start identity, or command does not match its service registration.",
        ));
    }
    let descriptor = matching_descriptor(descriptors, &registration, Some(&process))?;
    let socket = socket_for_registration(&registration, descriptor.as_ref())?;
    if Path::new(&registration.launch.workspace_root) != root {
        return Err(ownership_error("Service registration changed workspace root."));
    }
    Ok(RegisteredServiceObservation::Live(ServiceOwnedRuntime {
        workspace_root: root.to_path_buf(),
        registration,
        manager,
        process,
        descriptor,
        socket,
    }))
}

fn dead_registration(
    registration: ValidatedServiceRegistration,
    descriptor: Option<RegisteredDescriptor>,
) -> Result<RegisteredServiceObservation> {
    let socket = socket_for_registration(&registration, descriptor.as_ref())?;
    if matches!(socket, SocketObservation::PresentUnproven { .. }) {
        return Err(ownership_error(
            "A dead registration has an unproven socket path.",
        ));
    }
    Ok(RegisteredServiceObservation::Dead(DeadServiceRuntime {
        registration,
        descriptor,
        socket,
    }))
}

fn matching_descriptor(
    descriptors: &[RegisteredDescriptor],
    registration: &ValidatedServiceRegistration,
    process: Option<&ObservedProcess>,
) -> Result<Option<RegisteredDescriptor>> {
    let id = registration.receipt.runtime_instance_id.to_string();
    let matches = descriptors
        .iter()
        .filter(|descriptor| descriptor.descriptor.runtime_instance_id.as_deref() == Some(&id))
        .cloned()
        .collect::<Vec<_>>();
    if matches.len() > 1 {
        return Err(ownership_error(
            "More than one descriptor claims one runtime instance.",
        ));
    }
    let descriptor = matches.into_iter().next();
    if let (Some(descriptor), Some(process)) = (&descriptor, process)
        && (descriptor.descriptor.pid != process.identity.pid
            || descriptor.descriptor.owner_uid != Some(process.identity.owner_uid)
            || descriptor
                .descriptor
                .process_start_epoch_millis
                .map(|value| value / 1_000)
                != Some(process.identity.start_epoch_millis / 1_000)
            || descriptor.descriptor.socket_path != registration.launch.socket_path)
    {
        return Err(ownership_error(
            "Runtime descriptor does not match its service process.",
        ));
    }
    Ok(descriptor)
}

fn observe_legacy_runtime(
    descriptor: RegisteredDescriptor,
    root: &Path,
) -> Result<Option<LegacyOwnedRuntime>> {
    let Some(process) = observe_process(descriptor.descriptor.pid)? else {
        return Ok(None);
    };
    validate_descriptor_process(&descriptor.descriptor, &process)?;
    let socket = socket_for_descriptor(&descriptor.descriptor)?;
    Ok(Some(LegacyOwnedRuntime {
        workspace_root: root.to_path_buf(),
        process,
        descriptor,
        socket,
    }))
}

fn validate_descriptor_process(
    descriptor: &ServerInstanceDescriptor,
    process: &ObservedProcess,
) -> Result<()> {
    if descriptor.owner_uid != Some(process.identity.owner_uid)
        || descriptor.process_start_epoch_millis.map(|value| value / 1_000)
            != Some(process.identity.start_epoch_millis / 1_000)
    {
        return Err(ownership_error(
            "Legacy runtime process identity does not match its descriptor.",
        ));
    }
    let workspace_argument = format!("--workspace-root={}", descriptor.workspace_root);
    let socket_argument = format!("--socket-path={}", descriptor.socket_path);
    let is_indexer = process.command.iter().any(|argument| {
        argument == "io.github.amichne.kast.indexer.KastIndexerMainKt"
            || Path::new(argument)
                .file_name()
                .is_some_and(|name| name == "kast-indexer")
    });
    if descriptor.backend_name != BackendName::Indexer.canonical()
        || descriptor.transport != "uds"
        || !is_indexer
        || !process.command.contains(&workspace_argument)
        || !process.command.contains(&socket_argument)
    {
        return Err(ownership_error(
            "Legacy descriptor is not associated with an exact Kast indexer command.",
        ));
    }
    Ok(())
}

fn socket_for_registration(
    registration: &ValidatedServiceRegistration,
    descriptor: Option<&RegisteredDescriptor>,
) -> Result<SocketObservation> {
    match descriptor {
        Some(descriptor) => socket_for_descriptor(&descriptor.descriptor),
        None => match socket_observation(Path::new(&registration.launch.socket_path), None)? {
            SocketObservation::Exact { path, identity } => {
                Ok(SocketObservation::PresentUnproven { path, identity })
            }
            observation => Ok(observation),
        },
    }
}

fn socket_for_descriptor(descriptor: &ServerInstanceDescriptor) -> Result<SocketObservation> {
    socket_observation(
        Path::new(&descriptor.socket_path),
        descriptor.socket_file_identity.as_ref(),
    )
}

fn socket_observation(
    path: &Path,
    expected: Option<&RuntimeSocketFileIdentity>,
) -> Result<SocketObservation> {
    use std::os::unix::fs::{FileTypeExt as _, MetadataExt as _};
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(SocketObservation::Absent {
                path: path.to_path_buf(),
            });
        }
        Err(error) => return Err(error.into()),
    };
    if !metadata.file_type().is_socket() {
        return Err(ownership_error(
            "Runtime socket path is not a Unix-domain socket.",
        ));
    }
    let identity = RuntimeSocketFileIdentity {
        device: metadata.dev(),
        inode: metadata.ino(),
    };
    if expected.is_some_and(|expected| expected != &identity) {
        return Err(ownership_error("Runtime socket device or inode changed."));
    }
    Ok(SocketObservation::Exact {
        path: path.to_path_buf(),
        identity,
    })
}
