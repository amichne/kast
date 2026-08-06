fn write_projection_transaction_create_new(
    path: &Path,
    transaction: &DurablePathProjectionTransaction,
) -> Result<()> {
    use std::io::Write;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)?;
    file.write_all(serde_json::to_vec_pretty(transaction)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    sync_projection_parent(path)
}

fn write_projection_transaction_atomic(
    path: &Path,
    transaction: &DurablePathProjectionTransaction,
) -> Result<()> {
    use std::io::Write;
    let (temporary, mut file) = manifest::create_unique_temporary_file(path, "journal")?;
    file.write_all(serde_json::to_vec_pretty(transaction)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temporary, path)?;
    sync_projection_parent(path)
}

fn remove_projection_transaction(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Ok(()) => sync_projection_parent(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn sync_projection_parent(path: &Path) -> Result<()> {
    manifest::sync_parent_directory(path)
}

fn remove_internal_projection_path(
    path: &Path,
    expected: Option<ProjectionFileIdentity>,
    durability_failure_point: &str,
) -> Result<()> {
    let identity = match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return sync_projection_parent_after(path, durability_failure_point);
        }
        Err(error) => return Err(error.into()),
        Ok(_) => projection_file_identity(path)?,
    };
    if expected.is_some_and(|expected| expected != identity) {
        return Err(internal_projection_cleanup_conflict(path, None));
    }
    test_path_projection_barrier("before-control-internal-cleanup")?;
    let private_path = unique_internal_projection_path(path, "cleanup");
    rename_no_replace(path, &private_path)?;
    if projection_file_identity(&private_path).ok() != Some(identity) {
        let restore_error = rename_no_replace(&private_path, path)
            .and_then(|()| sync_projection_parent(path))
            .err();
        let mut error = internal_projection_cleanup_conflict(path, Some(&private_path));
        if let Some(restore_error) = restore_error {
            error
                .details
                .insert("restoreError".to_string(), restore_error.to_string());
        }
        return Err(error);
    }
    fs::remove_file(&private_path)?;
    sync_projection_parent_after(path, durability_failure_point)
}

fn internal_projection_cleanup_conflict(path: &Path, private_path: Option<&Path>) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_RECOVERY_CONFLICT",
        format!(
            "Preserved changed PATH projection transaction artifact {}.",
            path.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    if let Some(private_path) = private_path {
        error.details.insert(
            "privatePath".to_string(),
            private_path.display().to_string(),
        );
    }
    error
}

fn unique_internal_projection_path(path: &Path, purpose: &str) -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static UNIQUE_PATH_COUNTER: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let sequence = UNIQUE_PATH_COUNTER.fetch_add(1, Ordering::Relaxed);
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kastctl");
    path.with_file_name(format!(
        ".{file_name}.kast-{purpose}-{}-{nonce}-{sequence}",
        std::process::id(),
    ))
}

fn new_projection_transaction_nonce() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static TRANSACTION_NONCE_COUNTER: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let sequence = TRANSACTION_NONCE_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{}-{nonce}-{sequence}", std::process::id())
}

fn valid_projection_transaction_nonce(nonce: &str) -> bool {
    let mut components = nonce.split('-');
    let valid = components
        .next()
        .is_some_and(|value| value.parse::<u32>().is_ok())
        && components
            .next()
            .is_some_and(|value| value.parse::<u128>().is_ok())
        && components
            .next()
            .is_some_and(|value| value.parse::<u64>().is_ok());
    valid && components.next().is_none()
}

fn internal_projection_path(control_path: &Path, operation: &str, nonce: &str) -> PathBuf {
    let file_name = control_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kastctl");
    control_path.with_file_name(format!("{file_name}.kast-{operation}-{nonce}"))
}

fn sync_projection_parent_after(path: &Path, durability_failure_point: &str) -> Result<()> {
    manifest::test_install_durability_failure(durability_failure_point)?;
    sync_projection_parent(path)
}

fn recover_path_projection_transaction(targets: &ActivationTargetPaths) -> Result<()> {
    let journal_path = targets
        .resolved
        .install_root
        .join(PATH_PROJECTION_TRANSACTION_FILE);
    let contents = match fs::read_to_string(&journal_path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let durable: DurablePathProjectionTransaction =
        serde_json::from_str(&contents).map_err(|error| {
            CliError::new(
                "PATH_PROJECTION_TRANSACTION_INVALID",
                format!(
                    "Invalid PATH projection transaction at {}: {error}",
                    journal_path.display(),
                ),
            )
        })?;
    validate_durable_projection_transaction(&durable, targets)?;
    let transaction = PathProjectionTransaction {
        journal_path,
        durable,
    };
    if projection_transaction_receipt_committed(&transaction.durable, targets) {
        transaction.commit()
    } else {
        transaction.rollback()
    }
}

fn validate_durable_projection_transaction(
    transaction: &DurablePathProjectionTransaction,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    let expected_control = manifest::home_dir().join(".local/bin/kastctl");
    let expected_target = targets.resolved.active_binary.clone();
    let expected_receipt = targets.current_link.join(manifest::INSTALL_MANIFEST_FILE);
    let mutation_is_valid = valid_projection_transaction_nonce(&transaction.transaction_nonce)
        && match (&transaction.intended_profile, &transaction.mutation) {
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::CreatePrepared { temporary_path },
            ) => {
                Path::new(temporary_path)
                    == internal_projection_path(
                        &expected_control,
                        "create",
                        &transaction.transaction_nonce,
                    )
            }
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::CreateMaterialized {
                    temporary_path,
                    projected_identity,
                },
            ) => {
                projected_identity.kind == ProjectionFileKind::Symlink
                    && Path::new(temporary_path)
                        == internal_projection_path(
                            &expected_control,
                            "create",
                            &transaction.transaction_nonce,
                        )
            }
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::ReplacePrepared {
                    temporary_path,
                    prior_target,
                    prior_identity,
                },
            ) => {
                prior_identity.kind == ProjectionFileKind::Symlink
                    && !prior_target.is_empty()
                    && config::normalize(PathBuf::from(prior_target))
                        != config::normalize(PathBuf::from(&transaction.control_target))
                    && Path::new(temporary_path)
                        == internal_projection_path(
                            &expected_control,
                            "replace",
                            &transaction.transaction_nonce,
                        )
            }
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::ReplaceMaterialized {
                    temporary_path,
                    projected_identity,
                    prior_target,
                    prior_identity,
                },
            ) => {
                projected_identity.kind == ProjectionFileKind::Symlink
                    && prior_identity.kind == ProjectionFileKind::Symlink
                    && !prior_target.is_empty()
                    && config::normalize(PathBuf::from(prior_target))
                        != config::normalize(PathBuf::from(&transaction.control_target))
                    && Path::new(temporary_path)
                        == internal_projection_path(
                            &expected_control,
                            "replace",
                            &transaction.transaction_nonce,
                        )
            }
            (
                manifest::SetupProfile::Standard,
                DurablePathProjectionMutation::Remove {
                    quarantine_path,
                    prior_target,
                    prior_identity,
                },
            ) => {
                prior_identity.kind == ProjectionFileKind::Symlink
                    && !prior_target.is_empty()
                    && Path::new(quarantine_path)
                        == internal_projection_path(
                            &expected_control,
                            "remove",
                            &transaction.transaction_nonce,
                        )
            }
            _ => false,
        };
    if transaction.schema_version != PATH_PROJECTION_TRANSACTION_SCHEMA_VERSION
        || Path::new(&transaction.control_path) != expected_control
        || config::normalize(PathBuf::from(&transaction.control_target))
            != config::normalize(expected_target)
        || Path::new(&transaction.receipt_path) != expected_receipt
        || transaction.release_digest.is_empty()
        || !mutation_is_valid
    {
        return Err(CliError::new(
            "PATH_PROJECTION_TRANSACTION_INVALID",
            "PATH projection transaction does not match this Kast installation.",
        ));
    }
    Ok(())
}

fn projection_transaction_receipt_committed(
    transaction: &DurablePathProjectionTransaction,
    targets: &ActivationTargetPaths,
) -> bool {
    let Ok(receipt) =
        authority_manifest_from_file(&targets.current_link.join(manifest::INSTALL_MANIFEST_FILE))
    else {
        return false;
    };
    if receipt.schema_version != crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION
        || receipt.tool != "kast"
        || receipt.release_digest != transaction.release_digest
        || receipt.setup_profile != transaction.intended_profile
    {
        return false;
    }
    let matching = receipt
        .path_projections
        .iter()
        .filter(|projection| projection.command == manifest::PathProjectionCommand::Kastctl)
        .collect::<Vec<_>>();
    if transaction.intended_profile.projects_control_command() {
        matching.len() == 1
            && Path::new(&matching[0].path) == Path::new(&transaction.control_path)
            && config::normalize(PathBuf::from(&matching[0].target))
                == config::normalize(PathBuf::from(&transaction.control_target))
    } else {
        matching.is_empty()
    }
}
