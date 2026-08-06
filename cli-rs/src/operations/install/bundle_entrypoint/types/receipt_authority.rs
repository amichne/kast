fn validated_receipt_owned_control_projection(
    receipt: Option<&manifest::KastInstallManifest>,
    targets: &ActivationTargetPaths,
    control_path: &Path,
) -> Option<ReceiptOwnedControlProjection> {
    let receipt = receipt?;
    if !receipt_is_current_install_authority(receipt, targets)
        || receipt.setup_profile != manifest::SetupProfile::Development
    {
        return None;
    }
    let mut projections = receipt
        .path_projections
        .iter()
        .filter(|projection| projection.command == manifest::PathProjectionCommand::Kastctl);
    let projection = projections.next()?;
    let recorded_target = config::normalize(PathBuf::from(&projection.target));
    if projections.next().is_some()
        || Path::new(&projection.path) != control_path
        || !recorded_target.starts_with(&targets.current_link)
        || recorded_target.file_name().and_then(|name| name.to_str()) != Some("kastctl")
        || !exact_projection_matches(control_path, &recorded_target)
    {
        return None;
    }
    let identity = projection_file_identity(control_path).ok()?;
    (identity.kind == ProjectionFileKind::Symlink).then_some(ReceiptOwnedControlProjection {
        target: recorded_target,
        identity,
        receipt_release_digest: receipt.release_digest.clone(),
    })
}

fn receipt_is_current_install_authority(
    receipt: &manifest::KastInstallManifest,
    targets: &ActivationTargetPaths,
) -> bool {
    receipt.schema_version == crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION
        && receipt.tool == "kast"
        && !receipt.release_digest.is_empty()
        && config::normalize(PathBuf::from(&receipt.roots.install))
            == config::normalize(targets.resolved.install_root.clone())
}

fn receipt_explicitly_relinquishes_control(
    receipt: &manifest::KastInstallManifest,
    targets: &ActivationTargetPaths,
    control_path: &Path,
) -> bool {
    receipt_is_current_install_authority(receipt, targets)
        && receipt.setup_profile == manifest::SetupProfile::Standard
        && !receipt
            .path_projections
            .iter()
            .any(|projection| projection.command == manifest::PathProjectionCommand::Kastctl)
        && !receipt
            .owned_paths
            .iter()
            .any(|path| Path::new(path) == control_path)
}

fn load_force_reset_path_authority(
    targets: &ActivationTargetPaths,
    control_path: &Path,
) -> Result<Option<ForceResetPathAuthorityJournal>> {
    let path = targets
        .resolved
        .install_root
        .join(FORCE_RESET_PATH_AUTHORITY_FILE);
    let identity = match fs::symlink_metadata(&path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
        Ok(_) => projection_file_identity(&path)?,
    };
    if identity.kind != ProjectionFileKind::File {
        return Err(force_reset_path_authority_invalid(
            &path,
            "the authority snapshot is not a regular file",
        ));
    }
    let contents = fs::read_to_string(&path)?;
    require_identity(&path, identity, "force-reset PATH authority snapshot")?;
    let durable: DurableForceResetPathAuthority =
        serde_json::from_str(&contents).map_err(|error| {
            force_reset_path_authority_invalid(&path, format!("invalid JSON: {error}"))
        })?;
    let target = config::normalize(PathBuf::from(&durable.prior_target));
    let expected_target = config::normalize(targets.resolved.active_binary.clone());
    if durable.schema_version != FORCE_RESET_PATH_AUTHORITY_SCHEMA_VERSION
        || config::normalize(PathBuf::from(&durable.install_root))
            != config::normalize(targets.resolved.install_root.clone())
        || Path::new(&durable.control_path) != control_path
        || target != expected_target
        || durable.prior_identity.kind != ProjectionFileKind::Symlink
        || durable.receipt_release_digest.is_empty()
    {
        return Err(force_reset_path_authority_invalid(
            &path,
            "the authority snapshot does not match this Kast installation",
        ));
    }
    Ok(Some(ForceResetPathAuthorityJournal {
        path,
        identity,
        owned: ReceiptOwnedControlProjection {
            target,
            identity: durable.prior_identity,
            receipt_release_digest: durable.receipt_release_digest,
        },
    }))
}

fn write_force_reset_path_authority_create_new(
    install_root: &Path,
    control_path: &Path,
    owned: &ReceiptOwnedControlProjection,
) -> Result<ForceResetPathAuthorityJournal> {
    use std::io::Write;
    fs::create_dir_all(install_root)?;
    let path = install_root.join(FORCE_RESET_PATH_AUTHORITY_FILE);
    let durable = DurableForceResetPathAuthority {
        schema_version: FORCE_RESET_PATH_AUTHORITY_SCHEMA_VERSION,
        install_root: install_root.display().to_string(),
        control_path: control_path.display().to_string(),
        prior_target: owned.target.display().to_string(),
        prior_identity: owned.identity,
        receipt_release_digest: owned.receipt_release_digest.clone(),
    };
    let encoded = serde_json::to_vec_pretty(&durable)?;
    let staging_directory = install_root.join("staging/force-authority");
    fs::create_dir_all(&staging_directory)?;
    let temporary_template = staging_directory.join(FORCE_RESET_PATH_AUTHORITY_FILE);
    let (temporary_path, mut file) =
        manifest::create_unique_temporary_file(&temporary_template, "write")?;
    let temporary_identity = projection_file_identity(&temporary_path)?;
    if temporary_identity.kind != ProjectionFileKind::File {
        return Err(force_reset_path_authority_invalid(
            &temporary_path,
            "the authority snapshot temporary path is not a regular file",
        ));
    }
    let split = encoded.len().div_ceil(2);
    let write_result = (|| -> Result<()> {
        file.write_all(&encoded[..split])?;
        test_path_projection_crash("during-force-authority-write");
        file.write_all(&encoded[split..])?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        manifest::test_install_durability_failure(
            "after-force-authority-temporary-sync-before-publish",
        )
    })();
    drop(file);
    if let Err(error) = write_result {
        return Err(with_force_authority_temporary_cleanup(
            error,
            &temporary_path,
            temporary_identity,
        ));
    }
    let publish_result = (|| -> Result<()> {
        IdentityTransactionalMove::new(
            &temporary_path,
            &path,
            temporary_identity,
            "force-reset PATH authority temporary snapshot",
        )
        .execute()?;
        manifest::test_install_durability_failure(
            "after-force-authority-publish-before-parent-sync",
        )?;
        sync_projection_move_parents(&temporary_path, &path)?;
        require_identity(
            &path,
            temporary_identity,
            "published force-reset PATH authority snapshot",
        )
    })();
    if let Err(error) = publish_result {
        return Err(with_force_authority_temporary_cleanup(
            error,
            &temporary_path,
            temporary_identity,
        ));
    }
    Ok(ForceResetPathAuthorityJournal {
        path,
        identity: temporary_identity,
        owned: owned.clone(),
    })
}

fn with_force_authority_temporary_cleanup(
    mut error: CliError,
    temporary_path: &Path,
    temporary_identity: ProjectionFileIdentity,
) -> CliError {
    if let Err(cleanup_error) = remove_internal_projection_path(
        temporary_path,
        Some(temporary_identity),
        "after-force-authority-temporary-cleanup-before-parent-sync",
    ) {
        error.details.insert(
            "temporaryCleanupError".to_string(),
            cleanup_error.to_string(),
        );
        error.details.insert(
            "temporaryPath".to_string(),
            temporary_path.display().to_string(),
        );
    }
    error
}

fn force_reset_path_authority_invalid(path: &Path, reason: impl Into<String>) -> CliError {
    let mut error = CliError::new(
        "FORCE_RESET_PATH_AUTHORITY_INVALID",
        format!(
            "Force-reset PATH authority at {} is invalid: {}.",
            path.display(),
            reason.into(),
        ),
    );
    error
        .details
        .insert("authorityPath".to_string(), path.display().to_string());
    error
}

fn force_reset_path_authority_changed(
    control_path: &Path,
    authority_path: &Path,
    validation_error: &str,
) -> CliError {
    let mut error = CliError::new(
        "FORCE_RESET_PATH_AUTHORITY_CHANGED",
        format!(
            "Force-reset recovery cannot authorize changed command {}.",
            control_path.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), control_path.display().to_string());
    error.details.insert(
        "authorityPath".to_string(),
        authority_path.display().to_string(),
    );
    error
        .details
        .insert("validationError".to_string(), validation_error.to_string());
    error
}

fn authority_manifest_from_file(path: &Path) -> Result<manifest::KastInstallManifest> {
    let contents = fs::read_to_string(path)?;
    let raw: serde_json::Value = serde_json::from_str(&contents).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!("Invalid install manifest at {}: {error}", path.display()),
        )
    })?;
    let has_explicit_authority = raw.as_object().is_some_and(|receipt| {
        receipt.contains_key("schemaVersion") && receipt.contains_key("tool")
    });
    if !has_explicit_authority {
        return Err(CliError::new(
            "INSTALL_MANIFEST_AUTHORITY_MISSING",
            format!(
                "Install manifest at {} has no explicit schemaVersion or tool authority.",
                path.display(),
            ),
        ));
    }
    serde_json::from_value(raw).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!("Invalid install manifest at {}: {error}", path.display()),
        )
    })
}

fn require_owned_projection_unchanged(
    path: &Path,
    expected_target: &Path,
    expected_identity: ProjectionFileIdentity,
) -> Result<()> {
    if projection_file_identity(path).ok() == Some(expected_identity)
        && exact_projection_matches(path, expected_target)
    {
        return Ok(());
    }
    Err(projection_changed_error(
        path,
        "the receipt-owned identity or target changed after setup inspection",
    ))
}

fn exact_projection_matches(path: &Path, recorded_target: &Path) -> bool {
    let recorded_target = config::normalize(recorded_target.to_path_buf());
    fs::read_link(path).is_ok_and(|actual_target| {
        let actual_target = if actual_target.is_absolute() {
            actual_target
        } else {
            path.parent()
                .unwrap_or_else(|| Path::new("."))
                .join(actual_target)
        };
        config::normalize(actual_target) == recorded_target
    })
}

fn projection_changed_error(path: &Path, reason: impl Into<String>) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_CHANGED",
        format!(
            "PATH projection {} changed: {}.",
            path.display(),
            reason.into()
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error
}

fn projection_recovery_conflict(path: &Path, quarantine: &Path) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_RECOVERY_CONFLICT",
        format!(
            "Cannot restore receipt-owned projection {} because another path exists; preserved the prior projection at {}.",
            path.display(),
            quarantine.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error.details.insert(
        "quarantinePath".to_string(),
        quarantine.display().to_string(),
    );
    error
}
