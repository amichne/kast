enum RegisteredServiceObservation {
    Live(ServiceOwnedRuntime),
    Dead(DeadServiceRuntime),
}

fn observe_registered_service(
    registration: ValidatedServiceRegistration,
    descriptors: &[RegisteredDescriptor],
    root: &Path,
    active: Option<&super::registration::ActiveServiceRegistration>,
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
        return dead_registration(
            registration,
            claim,
            active.cloned(),
            descriptor,
            descriptors,
        );
    };
    if manager_pid.is_some_and(|manager_pid| manager_pid != pid) {
        return Err(ownership_error(
            "Service manager PID does not match the process claim.",
        ));
    }
    let process = match claim.as_ref() {
        Some(expected) => {
            let launch_matches =
                expected.launch_sha256 == registration.receipt.launch_sha256;
            match observe_claimed_process(&expected.process)? {
                ClaimedProcessObservation::Gone => {
                    return dead_registration(
                        registration,
                        claim,
                        active.cloned(),
                        descriptor,
                        descriptors,
                    );
                }
                ClaimedProcessObservation::Reused(process)
                    if descriptor.as_ref().is_some_and(|descriptor| {
                        descriptor_matches_process(
                            &descriptor.descriptor,
                            &registration,
                            &process,
                        )
                    }) =>
                {
                    return Err(ownership_error(
                        "Runtime process claim is stale but its descriptor matches the live process.",
                    ));
                }
                ClaimedProcessObservation::Reused(_)
                    if launch_matches
                        && matches!(
                            manager,
                            ServiceManagerObservation::Registered
                                | ServiceManagerObservation::Absent
                        ) =>
                {
                    return dead_registration(
                        registration,
                        claim,
                        active.cloned(),
                        descriptor,
                        descriptors,
                    );
                }
                ClaimedProcessObservation::Reused(_) => {
                    return Err(ownership_error(
                        "Runtime process claim does not match the live service-manager process.",
                    ));
                }
                ClaimedProcessObservation::Exact(process) => process,
            }
        }
        None => {
            let Some(process) = observe_process(pid)? else {
                return dead_registration(
                    registration,
                    claim,
                    active.cloned(),
                    descriptor,
                    descriptors,
                );
            };
            process
        }
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
    let socket = socket_for_registration(&registration, descriptor.as_ref(), descriptors)?;
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
        proven_dead: ProvenDeadRuntimeOwnership::default(),
    }))
}

fn dead_registration(
    registration: ValidatedServiceRegistration,
    process_claim: Option<super::registration::ServiceProcessClaim>,
    active: Option<super::registration::ActiveServiceRegistration>,
    descriptor: Option<RegisteredDescriptor>,
    descriptors: &[RegisteredDescriptor],
) -> Result<RegisteredServiceObservation> {
    let socket = socket_for_registration(&registration, descriptor.as_ref(), descriptors)?;
    if matches!(socket, SocketObservation::PresentUnproven { .. }) {
        return Err(ownership_error(
            "A dead registration has an unproven socket path.",
        ));
    }
    Ok(RegisteredServiceObservation::Dead(DeadServiceRuntime {
        registration,
        process_claim,
        active,
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
        && !descriptor_matches_process(&descriptor.descriptor, registration, process)
    {
        return Err(ownership_error(
            "Runtime descriptor does not match its service process.",
        ));
    }
    Ok(descriptor)
}

fn descriptor_matches_process(
    descriptor: &ServerInstanceDescriptor,
    registration: &ValidatedServiceRegistration,
    process: &ObservedProcess,
) -> bool {
    descriptor.pid == process.identity.pid
        && descriptor.owner_uid == Some(process.identity.owner_uid)
        && descriptor
            .process_start_epoch_millis
            .map(|value| value / 1_000)
            == Some(process.identity.start_epoch_millis / 1_000)
        && descriptor.socket_path == registration.launch.socket_path
}

enum LegacyRuntimeObservation {
    Live(LegacyOwnedRuntime),
    Dead(DeadLegacyRuntime),
}

fn observe_legacy_runtime(
    descriptor: RegisteredDescriptor,
    root: &Path,
) -> Result<LegacyRuntimeObservation> {
    let process = observe_descriptor_process(&descriptor.descriptor)?;
    let socket = socket_for_descriptor(&descriptor.descriptor)?;
    if matches!(socket, SocketObservation::PresentUnproven { .. }) {
        return Err(ownership_error(
            "Legacy runtime socket ownership is not proven by its descriptor.",
        ));
    }
    match process {
        DescriptorProcessObservation::Gone | DescriptorProcessObservation::Reused => {
            let owner_uid = descriptor.descriptor.owner_uid.ok_or_else(|| {
                ownership_error("Dead legacy descriptor has no operating-system owner identity.")
            })?;
            Ok(LegacyRuntimeObservation::Dead(DeadLegacyRuntime {
                descriptor,
                socket,
                owner_uid,
            }))
        }
        DescriptorProcessObservation::Exact(process) => {
            validate_descriptor_process(&descriptor.descriptor, &process)?;
            Ok(LegacyRuntimeObservation::Live(LegacyOwnedRuntime {
                workspace_root: root.to_path_buf(),
                process,
                descriptor,
                socket,
                proven_dead: ProvenDeadRuntimeOwnership::default(),
            }))
        }
    }
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
    descriptors: &[RegisteredDescriptor],
) -> Result<SocketObservation> {
    match descriptor {
        Some(descriptor) => socket_for_descriptor(&descriptor.descriptor),
        None => {
            match socket_observation(Path::new(&registration.launch.socket_path), None)? {
                SocketObservation::Exact { path, identity }
                    if descriptors.iter().any(|descriptor| {
                        descriptor.descriptor.socket_path == registration.launch.socket_path
                            && descriptor.descriptor.socket_file_identity.as_ref()
                                == Some(&identity)
                    }) =>
                {
                    Ok(SocketObservation::OwnedByOtherExact { path, identity })
                }
                SocketObservation::Exact { path, identity } => {
                    Ok(SocketObservation::PresentUnproven { path, identity })
                }
                observation => Ok(observation),
            }
        }
    }
}

fn socket_for_descriptor(descriptor: &ServerInstanceDescriptor) -> Result<SocketObservation> {
    let observation = socket_observation(
        Path::new(&descriptor.socket_path),
        descriptor.socket_file_identity.as_ref(),
    )?;
    if descriptor.socket_file_identity.is_none()
        && let SocketObservation::Exact { path, identity } = observation
    {
        Ok(SocketObservation::PresentUnproven { path, identity })
    } else {
        Ok(observation)
    }
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
