pub(super) fn validate_service_registration(
    directory: &Path,
    expected_root: &Path,
) -> Result<ValidatedServiceRegistration> {
    require_owned_directory(directory)?;
    let canonical_directory = fs::canonicalize(directory)?;
    let receipt_path = canonical_directory.join("receipt.json");
    let (receipt, receipt_bytes) = read_owned_json::<ServiceRegistrationReceipt>(&receipt_path)?;
    let launch_path = canonical_directory.join("launch.json");
    let (launch, launch_bytes) = read_owned_json::<ServiceLaunchRegistration>(&launch_path)?;
    let expected_manager = super::service_manager::registration_for(
        &launch,
        &canonical_directory,
        &receipt.launch_sha256,
    )?;
    let test_manager = matches!(receipt.manager, ServiceManagerRegistration::Test { .. });
    let expected_release = if test_manager && launch.installed_release.is_none() {
        None
    } else {
        let install_root = match &launch.installed_release {
            Some(pin) => fs::canonicalize(&pin.install_root)?,
            None => fs::canonicalize(crate::manifest::resolve_paths()?.install_root)?,
        };
        Some(installed_release_pin(
            Path::new(&launch.launcher_path),
            &install_root,
        )?)
    };
    let expected_id = receipt.runtime_instance_id.to_string();
    let directory_id = canonical_directory.file_name().and_then(|value| value.to_str());
    let directory_workspace_key = canonical_directory
        .parent()
        .and_then(Path::file_name)
        .and_then(|value| value.to_str());
    require_registration(receipt.schema_version == SERVICE_REGISTRATION_SCHEMA, "receipt schema")?;
    require_registration(launch.schema_version == SERVICE_REGISTRATION_SCHEMA, "launch schema")?;
    require_registration(directory_id == Some(expected_id.as_str()), "directory instance")?;
    require_registration(
        directory_workspace_key == Some(receipt.workspace_key.as_str()),
        "directory workspace key",
    )?;
    require_registration(Path::new(&receipt.launch_path) == launch_path, "launch path")?;
    require_registration(
        Path::new(&launch.runtime_config_path) == canonical_directory.join("runtime-config.json"),
        "runtime configuration path",
    )?;
    require_registration(
        receipt.manager.definition_path().parent() == Some(canonical_directory.as_path()),
        "service definition directory",
    )?;
    require_registration(receipt.manager == expected_manager, "service manager")?;
    require_registration(
        receipt.launch_sha256 == crate::manifest::sha256_bytes(&launch_bytes),
        "launch digest",
    )?;
    require_registration(
        receipt.definition_sha256
            == sha256_stable_file(receipt.manager.definition_path(), true)?,
        "service definition digest",
    )?;
    require_registration(
        receipt.runtime_instance_id == launch.runtime_instance_id,
        "runtime instance",
    )?;
    require_registration(receipt.workspace_key == launch.workspace_key, "workspace key")?;
    require_registration(receipt.workspace_root == launch.workspace_root, "workspace root")?;
    require_registration(Path::new(&launch.workspace_root) == expected_root, "expected root")?;
    require_registration(Path::new(&launch.working_directory) == expected_root, "working directory")?;
    require_registration(workspace_key(expected_root) == launch.workspace_key, "root digest")?;
    require_registration(launch.owner_uid == effective_uid(), "owner UID")?;
    ServiceSocketPath::from_command(&launch.command)?.require_matches(&launch.socket_path)?;
    require_registration(
        launch.runtime_config_sha256
            == sha256_stable_file(Path::new(&launch.runtime_config_path), true)?,
        "runtime configuration digest",
    )?;
    require_registration(
        launch.launcher_sha256
            == sha256_stable_file(Path::new(&launch.launcher_path), false)?,
        "launcher digest",
    )?;
    require_registration(launch.installed_release == expected_release, "installed release")?;
    Ok(ValidatedServiceRegistration {
        directory: canonical_directory,
        receipt_path,
        receipt_sha256: crate::manifest::sha256_bytes(&receipt_bytes),
        receipt,
        launch,
    })
}

fn require_registration(condition: bool, identity: &str) -> Result<()> {
    if condition {
        Ok(())
    } else {
        Err(registration_invalid(&format!(
            "Service registration {identity} does not match its durable files."
        )))
    }
}

fn installed_release_pin(path: &Path, install_root: &Path) -> Result<InstalledReleasePin> {
    let install_root = fs::canonicalize(install_root)?;
    let launcher = fs::canonicalize(path)?;
    let release_root = launcher
        .parent()
        .filter(|parent| parent.file_name().is_some_and(|name| name == "libexec"))
        .and_then(Path::parent)
        .ok_or_else(|| registration_invalid("Runtime launcher is outside an installed release."))?;
    let digest = release_root
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| registration_invalid("Installed release has no digest name."))?;
    let release_digest = ReleaseDigest::parse(digest.to_string()).map_err(|message| {
        registration_invalid(&format!("Installed release identity is invalid: {message}."))
    })?;
    let expected_root = install_root.join("releases").join(release_digest.as_str());
    if release_root != expected_root || launcher != expected_root.join("libexec/kastctl") {
        return Err(registration_invalid(
            "Runtime launcher does not match the canonical installed release root.",
        ));
    }
    let receipt_path = release_root.join("receipt.json");
    let receipt_bytes = read_stable_file(&receipt_path, false)?;
    let receipt = serde_json::from_slice::<crate::manifest::KastInstallManifest>(&receipt_bytes)?;
    if receipt.tool != "kast"
        || receipt.release_digest != release_digest.as_str()
        || fs::canonicalize(&receipt.roots.install)? != install_root
    {
        return Err(registration_invalid(
            "Installed release receipt does not match the runtime launcher pin.",
        ));
    }
    Ok(InstalledReleasePin {
        install_root: install_root.display().to_string(),
        release_root: release_root.display().to_string(),
        release_digest,
        receipt_path: receipt_path.display().to_string(),
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
