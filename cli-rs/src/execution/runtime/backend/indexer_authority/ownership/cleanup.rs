pub(super) fn cleanup_dead_registration(
    _config: &KastConfig,
    dead: &DeadServiceRuntime,
) -> Result<()> {
    let registration = revalidate_registration(&dead.registration)?;
    verify_cleanup_metadata_snapshots(&registration, dead)?;
    ensure_registered_process_is_dead(&registration, dead.descriptor.as_ref())?;
    verify_socket_snapshot(&dead.socket, registration.launch.owner_uid)?;
    verify_registration_directory_entries(&registration)?;
    verify_descriptor_snapshot(dead.descriptor.as_ref(), &registration)?;
    let active_path = registration
        .directory
        .parent()
        .ok_or_else(runtime_identity_mismatch)?
        .join("active.json");
    verify_cleanup_metadata_snapshots(&registration, dead)?;
    match super::service_manager::inspect(&registration.receipt.manager)? {
        super::service_manager::ServiceManagerObservation::Running(_) => {
            return Err(ownership_changed(
                "A runtime became live while repair was executing.",
            ));
        }
        super::service_manager::ServiceManagerObservation::Registered
        | super::service_manager::ServiceManagerObservation::Absent => {
            super::service_manager::unregister(&registration.receipt.manager)?;
        }
    }
    if super::service_manager::inspect(&registration.receipt.manager)?
        != super::service_manager::ServiceManagerObservation::Absent
    {
        return Err(ownership_changed(
            "Runtime service manager registration remained after cleanup.",
        ));
    }
    let registration = revalidate_registration(&registration)?;
    ensure_registered_process_is_dead(&registration, dead.descriptor.as_ref())?;
    verify_socket_snapshot(&dead.socket, registration.launch.owner_uid)?;
    verify_registration_directory_entries(&registration)?;
    verify_descriptor_snapshot(dead.descriptor.as_ref(), &registration)?;
    verify_cleanup_metadata_snapshots(&registration, dead)?;
    if let Some(descriptor) = &dead.descriptor {
        delete_descriptor(
            super::ownership::service_descriptor_directory(&registration)?,
            &descriptor.descriptor,
        )?;
    }
    remove_exact_socket(&dead.socket, registration.launch.owner_uid)?;
    if let Some(active) = &dead.active
        && active.runtime_instance_id == registration.receipt.runtime_instance_id
    {
        fs::remove_file(&active_path)?;
    }
    remove_registration_directory(&registration, dead.process_claim.is_some())?;
    Ok(())
}

fn verify_cleanup_metadata_snapshots(
    registration: &ValidatedServiceRegistration,
    dead: &DeadServiceRuntime,
) -> Result<()> {
    let process_claim = super::registration::read_process_claim(&registration.directory)?;
    if process_claim != dead.process_claim {
        return Err(ownership_changed(
            "Runtime process claim changed before cleanup.",
        ));
    }
    let active_path = registration
        .directory
        .parent()
        .ok_or_else(runtime_identity_mismatch)?
        .join("active.json");
    if super::registration::read_active_registration(&active_path)? != dead.active {
        return Err(ownership_changed(
            "Active runtime pointer changed before cleanup.",
        ));
    }
    Ok(())
}

pub(super) fn cleanup_dead_legacy(config: &KastConfig, dead: &DeadLegacyRuntime) -> Result<()> {
    let root = fs::canonicalize(&dead.descriptor.descriptor.workspace_root)?;
    let descriptors = find_indexer_descriptors(&config.paths.descriptor_dir, &root)?;
    if !descriptors.iter().any(|current| {
        current.id == dead.descriptor.id && current.descriptor == dead.descriptor.descriptor
    }) {
        return Err(ownership_changed(
            "Dead legacy descriptor changed before cleanup.",
        ));
    }
    match super::ownership::observe_descriptor_process(&dead.descriptor.descriptor)? {
        super::ownership::DescriptorProcessObservation::Gone
        | super::ownership::DescriptorProcessObservation::Reused => {}
        super::ownership::DescriptorProcessObservation::Exact(_) => {
            return Err(ownership_changed(
                "The exact legacy runtime process became live before cleanup.",
            ));
        }
    }
    if dead.owner_uid != u64::from(unsafe { libc::geteuid() }) {
        return Err(ownership_changed(
            "Dead legacy descriptor belongs to another operating-system user.",
        ));
    }
    verify_socket_snapshot(&dead.socket, dead.owner_uid)?;
    delete_descriptor(&config.paths.descriptor_dir, &dead.descriptor.descriptor)?;
    remove_exact_socket(&dead.socket, dead.owner_uid)
}

fn revalidate_registration(
    expected: &ValidatedServiceRegistration,
) -> Result<ValidatedServiceRegistration> {
    let current = super::registration::validate_service_registration(
        &expected.directory,
        Path::new(&expected.launch.workspace_root),
    )?;
    if current.receipt_sha256 != expected.receipt_sha256
        || current.receipt != expected.receipt
        || current.launch != expected.launch
    {
        return Err(ownership_changed(
            "Runtime service registration changed before cleanup.",
        ));
    }
    Ok(current)
}

fn ensure_registered_process_is_dead(
    registration: &ValidatedServiceRegistration,
    descriptor: Option<&RegisteredDescriptor>,
) -> Result<()> {
    if let Some(claim) = super::registration::read_process_claim(&registration.directory)? {
        match super::ownership::observe_claimed_process(&claim.process)? {
            super::ownership::ClaimedProcessObservation::Gone
            | super::ownership::ClaimedProcessObservation::Reused(_) => {}
            super::ownership::ClaimedProcessObservation::Exact(_) => {
                return Err(ownership_changed(
                    "The exact registered runtime process became live before cleanup.",
                ));
            }
        }
    }
    if let Some(descriptor) = descriptor {
        match super::ownership::observe_descriptor_process(&descriptor.descriptor)? {
            super::ownership::DescriptorProcessObservation::Gone
            | super::ownership::DescriptorProcessObservation::Reused => {}
            super::ownership::DescriptorProcessObservation::Exact(_) => {
                return Err(ownership_changed(
                    "Registered descriptor process became live before cleanup.",
                ));
            }
        }
    }
    Ok(())
}

fn verify_descriptor_snapshot(
    descriptor: Option<&RegisteredDescriptor>,
    registration: &ValidatedServiceRegistration,
) -> Result<()> {
    let Some(expected) = descriptor else {
        return Ok(());
    };
    let descriptors = find_indexer_descriptors(
        super::ownership::service_descriptor_directory(registration)?,
        Path::new(&registration.launch.workspace_root),
    )?;
    if !descriptors.iter().any(|current| {
        current.id == expected.id && current.descriptor == expected.descriptor
    }) {
        return Err(ownership_changed(
            "Runtime descriptor changed before cleanup.",
        ));
    }
    Ok(())
}

fn verify_socket_snapshot(snapshot: &SocketObservation, owner_uid: u64) -> Result<()> {
    use std::os::unix::fs::{FileTypeExt as _, MetadataExt as _};
    let (path, expected) = match snapshot {
        SocketObservation::Absent { path } => match fs::symlink_metadata(path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            _ => return Err(ownership_changed("An absent runtime socket was replaced.")),
        },
        SocketObservation::Exact { path, identity }
        | SocketObservation::OwnedByOtherExact { path, identity } => (path, identity),
        SocketObservation::PresentUnproven { .. } => {
            return Err(ownership_changed("Runtime socket ownership is not proven."));
        }
    };
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_socket()
        || metadata.dev() != expected.device
        || metadata.ino() != expected.inode
        || u64::from(metadata.uid()) != owner_uid
    {
        return Err(ownership_changed(
            "Runtime socket device, inode, type, or owner changed.",
        ));
    }
    Ok(())
}

fn remove_exact_socket(snapshot: &SocketObservation, owner_uid: u64) -> Result<()> {
    verify_socket_snapshot(snapshot, owner_uid)?;
    match snapshot {
        SocketObservation::Absent { .. } => Ok(()),
        SocketObservation::Exact { path, .. } => fs::remove_file(path).map_err(Into::into),
        SocketObservation::OwnedByOtherExact { .. } => Ok(()),
        SocketObservation::PresentUnproven { .. } => {
            Err(ownership_changed("Runtime socket ownership is not proven."))
        }
    }
}

fn verify_registration_directory_entries(
    registration: &ValidatedServiceRegistration,
) -> Result<()> {
    let definition_name = registration
        .receipt
        .manager
        .definition_path()
        .file_name()
        .ok_or_else(|| ownership_changed("Service definition has no file name."))?;
    let allowed = [
        std::ffi::OsStr::new("launch.json"),
        std::ffi::OsStr::new("receipt.json"),
        std::ffi::OsStr::new("runtime-config.json"),
        std::ffi::OsStr::new("process.json"),
        definition_name,
    ];
    for entry in fs::read_dir(&registration.directory)? {
        let entry = entry?;
        if !entry.file_type()?.is_file() || !allowed.contains(&entry.file_name().as_os_str()) {
            return Err(ownership_changed(
                "Runtime registration directory contains an unexpected entry.",
            ));
        }
    }
    Ok(())
}

fn remove_registration_directory(
    registration: &ValidatedServiceRegistration,
    process_claim_exists: bool,
) -> Result<()> {
    if process_claim_exists {
        fs::remove_file(registration.directory.join("process.json"))?;
    }
    for path in [
        registration.receipt.manager.definition_path().to_path_buf(),
        PathBuf::from(&registration.launch.runtime_config_path),
        PathBuf::from(&registration.receipt.launch_path),
        registration.receipt_path.clone(),
    ] {
        fs::remove_file(path)?;
    }
    fs::remove_dir(&registration.directory)?;
    Ok(())
}

fn ownership_changed(message: &str) -> CliError {
    CliError::new("RUNTIME_OWNERSHIP_CHANGED", message)
}
