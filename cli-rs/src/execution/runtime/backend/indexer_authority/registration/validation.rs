pub(super) fn validate_service_registration(
    directory: &Path,
    expected_root: &Path,
) -> Result<ValidatedServiceRegistration> {
    require_owned_directory(directory)?;
    let canonical_directory = fs::canonicalize(directory)?;
    let receipt_path = directory.join("receipt.json");
    let (receipt, receipt_bytes) = read_owned_json::<ServiceRegistrationReceipt>(&receipt_path)?;
    let launch_path = directory.join("launch.json");
    let (launch, launch_bytes) = read_owned_json::<ServiceLaunchRegistration>(&launch_path)?;
    let expected_manager = super::service_manager::registration_for(
        &launch,
        &canonical_directory,
        &receipt.launch_sha256,
    )?;
    let expected_id = receipt.runtime_instance_id.to_string();
    let directory_id = canonical_directory.file_name().and_then(|value| value.to_str());
    let directory_workspace_key = canonical_directory
        .parent()
        .and_then(Path::file_name)
        .and_then(|value| value.to_str());
    if receipt.schema_version != SERVICE_REGISTRATION_SCHEMA
        || launch.schema_version != SERVICE_REGISTRATION_SCHEMA
        || directory_id != Some(expected_id.as_str())
        || directory_workspace_key != Some(receipt.workspace_key.as_str())
        || Path::new(&receipt.launch_path) != launch_path
        || Path::new(&launch.runtime_config_path) != directory.join("runtime-config.json")
        || receipt.manager.definition_path().parent() != Some(canonical_directory.as_path())
        || receipt.manager != expected_manager
        || receipt.launch_sha256 != crate::manifest::sha256_bytes(&launch_bytes)
        || receipt.definition_sha256
            != crate::manifest::sha256_file(receipt.manager.definition_path())?
        || receipt.runtime_instance_id != launch.runtime_instance_id
        || receipt.workspace_key != launch.workspace_key
        || receipt.workspace_root != launch.workspace_root
        || Path::new(&launch.workspace_root) != expected_root
        || Path::new(&launch.working_directory) != expected_root
        || workspace_key(expected_root) != launch.workspace_key
        || launch.owner_uid != effective_uid()
        || launch.runtime_config_sha256
            != crate::manifest::sha256_file(Path::new(&launch.runtime_config_path))?
        || launch.launcher_sha256
            != crate::manifest::sha256_file(Path::new(&launch.launcher_path))?
    {
        return Err(registration_invalid(
            "Service registration identity does not match its durable files.",
        ));
    }
    Ok(ValidatedServiceRegistration {
        directory: canonical_directory,
        receipt_path,
        receipt_sha256: crate::manifest::sha256_bytes(&receipt_bytes),
        receipt,
        launch,
    })
}

pub(super) fn validate_entrypoint_registration(
    path: &Path,
    expected_sha256: &str,
) -> Result<ValidatedServiceRegistration> {
    if expected_sha256.len() != 64
        || !expected_sha256
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(registration_invalid(
            "Service entrypoint digest must be 64 lowercase hexadecimal characters.",
        ));
    }
    let canonical_path = fs::canonicalize(path)?;
    let (launch, bytes) = read_owned_json::<ServiceLaunchRegistration>(&canonical_path)?;
    if crate::manifest::sha256_bytes(&bytes) != expected_sha256 {
        return Err(registration_invalid(
            "Service entrypoint digest does not match launch.json.",
        ));
    }
    let canonical_root = fs::canonicalize(&launch.workspace_root)?;
    let directory = canonical_path
        .parent()
        .ok_or_else(|| registration_invalid("Service launch path has no parent."))?;
    let registration = validate_service_registration(directory, &canonical_root)?;
    if fs::canonicalize(&registration.receipt.launch_path)? != canonical_path
        || registration.receipt.launch_sha256 != expected_sha256
    {
        return Err(registration_invalid(
            "Service entrypoint path does not match its receipt.",
        ));
    }
    let active_path = registration
        .directory
        .parent()
        .ok_or_else(|| registration_invalid("Service registration has no workspace directory."))?
        .join("active.json");
    let active = read_active_registration(&active_path)?.ok_or_else(|| {
        registration_invalid("Service registration is not the active workspace runtime.")
    })?;
    if active.runtime_instance_id != registration.receipt.runtime_instance_id
        || active.receipt_sha256 != registration.receipt_sha256
    {
        return Err(registration_invalid(
            "Active workspace runtime does not match the service receipt.",
        ));
    }
    Ok(registration)
}
