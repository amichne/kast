pub fn rollback_local_development(
    request: LocalDevelopmentRollbackRequest,
) -> Result<LocalDevelopmentRollbackResult> {
    let prefix = canonical_directory(
        &absolute_path(request.prefix)?,
        "local-development prefix",
    )?;
    let requested_generation = request.to_generation;
    with_local_authority_lock(&prefix, || {
        let current_target = read_generation_link(&prefix.join("current"))?.ok_or_else(|| {
            CliError::new(
                "LOCAL_AUTHORITY_INACTIVE",
                format!("No active local generation exists at {}.", prefix.display()),
            )
        })?;
        let current_generation = prefix.join(&current_target);
        let current_receipt = read_local_development_receipt(
            &current_generation.join("authority.json"),
        )?;
        validate_receipt_identity(
            &current_receipt,
            &prefix,
            &current_generation,
            &current_receipt.workspace_root,
        )?;
        validate_receipt_components(&current_receipt)?;
        validate_stable_authority(
            &prefix.join("authority.json"),
            &current_receipt,
            &current_generation,
        )?;
        if current_receipt.generation_id == requested_generation {
            return Ok(LocalDevelopmentRollbackResult {
                receipt: current_receipt,
                replaced_generation: None,
                skipped: true,
                schema_version: crate::SCHEMA_VERSION,
            });
        }
        reject_live_local_runtimes(&prefix)?;

        let previous_target =
            read_generation_link(&prefix.join("previous"))?.ok_or_else(|| {
                CliError::new(
                    "LOCAL_ROLLBACK_UNAVAILABLE",
                    format!("No previous local generation exists at {}.", prefix.display()),
                )
            })?;
        if current_target == previous_target {
            return Err(CliError::new(
                "LOCAL_ROLLBACK_UNAVAILABLE",
                "Current and previous local generation targets are identical.",
            ));
        }
        let previous_generation = prefix.join(&previous_target);
        let previous_receipt = read_local_development_receipt(
            &previous_generation.join("authority.json"),
        )?;
        validate_receipt_identity(
            &previous_receipt,
            &prefix,
            &previous_generation,
            &current_receipt.workspace_root,
        )?;
        validate_receipt_physical_components(&previous_receipt)?;
        if previous_receipt.generation_id != requested_generation {
            return Err(CliError::new(
                "LOCAL_ROLLBACK_TARGET_MISMATCH",
                format!(
                    "Requested generation {} is neither current nor the validated previous generation {}.",
                    requested_generation.as_str(),
                    previous_receipt.generation_id.as_str(),
                ),
            ));
        }
        validate_workspace_guidance_target(&current_receipt.workspace_root, &prefix)?;

        let mut transaction = LocalRefreshTransaction::new(
            &prefix,
            &current_receipt.workspace_root,
            Some(current_target.clone()),
            Some(previous_target.clone()),
            None,
            Some(fs::read_link(prefix.join("bin/kast"))?),
        );
        let activation = (|| -> Result<LocalDevelopmentRollbackResult> {
            transaction.stable_entrypoints_installed = true;
            install_stable_entrypoints(&prefix, &previous_receipt)?;
            replace_relative_symlink(&prefix.join("previous"), &current_target)?;
            transaction.previous_changed = true;
            replace_relative_symlink(&prefix.join("current"), &previous_target)?;
            transaction.current_changed = true;
            let receipt = read_local_development_receipt(&prefix.join("authority.json"))?;
            validate_receipt_identity(
                &receipt,
                &prefix,
                &previous_generation,
                &current_receipt.workspace_root,
            )?;
            validate_receipt_components(&receipt)?;
            Ok(LocalDevelopmentRollbackResult {
                receipt,
                replaced_generation: Some(current_receipt.generation_id.clone()),
                skipped: false,
                schema_version: crate::SCHEMA_VERSION,
            })
        })();
        match activation {
            Ok(result) => Ok(result),
            Err(mut error) => {
                if let Err(cleanup) = transaction.rollback() {
                    error
                        .details
                        .insert("rollbackError".to_string(), cleanup.to_string());
                }
                Err(error)
            }
        }
    })
}

pub fn remove_local_development(
    request: LocalDevelopmentRemoveRequest,
) -> Result<LocalDevelopmentRemoveResult> {
    remove_local_development_with_observer(request, |_| Ok(()))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LocalRemovalPhase {
    BeforeMissingPrefixCleanup,
    AfterPrefixRenamed,
}

fn remove_local_development_with_observer(
    request: LocalDevelopmentRemoveRequest,
    mut observe: impl FnMut(LocalRemovalPhase) -> Result<()>,
) -> Result<LocalDevelopmentRemoveResult> {
    let workspace_root = canonical_directory(&request.workspace_root, "exact workspace root")?;
    let requested_prefix = absolute_path(request.prefix)?;
    let lock_prefix = canonicalize_missing_path(&requested_prefix)?;
    with_local_authority_lock(&lock_prefix, || {
        if !requested_prefix.exists() {
            observe(LocalRemovalPhase::BeforeMissingPrefixCleanup)?;
            if reconcile_receipt_owned_removal_tombstone(&lock_prefix, &workspace_root)? {
                return Ok(LocalDevelopmentRemoveResult {
                    prefix: lock_prefix.clone(),
                    workspace_root: workspace_root.clone(),
                    removed: true,
                    schema_version: crate::SCHEMA_VERSION,
                });
            }
            remove_owned_workspace_guidance_link(&workspace_root, &lock_prefix)?;
            return Ok(LocalDevelopmentRemoveResult {
                prefix: lock_prefix.clone(),
                workspace_root: workspace_root.clone(),
                removed: false,
                schema_version: crate::SCHEMA_VERSION,
            });
        }
        let prefix = canonical_directory(&requested_prefix, "local-development prefix")?;
        if fs::symlink_metadata(&requested_prefix)?.file_type().is_symlink() {
            return Err(CliError::new(
                "LOCAL_PREFIX_UNSAFE",
                format!(
                    "Refusing to remove a local prefix selected through a symlink: {}.",
                    requested_prefix.display(),
                ),
            ));
        }
        if prefix != lock_prefix {
            return Err(CliError::new(
                "LOCAL_PREFIX_CHANGED",
                format!(
                    "Local prefix identity changed while waiting for its authority lock: {}.",
                    requested_prefix.display(),
                ),
            ));
        }
        validate_removal_boundary(&prefix, &workspace_root)?;
        let result_prefix = prefix.clone();
        let current_target = read_generation_link(&prefix.join("current"))?.ok_or_else(|| {
            CliError::new(
                "LOCAL_AUTHORITY_INACTIVE",
                format!(
                    "Refusing to remove prefix without an active local receipt: {}.",
                    prefix.display(),
                ),
            )
        })?;
        let generation = prefix.join(current_target);
        let receipt = read_removal_authority(&generation.join("authority.json"))?;
        if receipt.authority != LocalDevelopmentAuthority::LocalDevelopment
            || receipt.prefix != prefix
            || receipt.workspace_root != workspace_root
            || generation != prefix.join(generation_target(&receipt.generation_id))
        {
            return Err(CliError::new(
                "LOCAL_AUTHORITY_RECEIPT_INVALID",
                "Removal authority does not match the exact prefix, generation, or workspace.",
            ));
        }
        let metadata = fs::symlink_metadata(&generation)?;
        if !metadata.is_dir()
            || metadata.file_type().is_symlink()
            || fs::canonicalize(&generation)? != generation
        {
            return Err(CliError::new(
                "LOCAL_AUTHORITY_RECEIPT_INVALID",
                format!(
                    "Removal generation is not an owned canonical directory: {}.",
                    generation.display(),
                ),
            ));
        }
        reject_live_local_runtimes(&prefix)?;

        let tombstone = removal_tombstone_path(&prefix)?;
        if fs::symlink_metadata(&tombstone).is_ok() {
            return Err(CliError::new(
                "LOCAL_REMOVAL_CONFLICT",
                format!(
                    "Refusing to overwrite an existing removal staging path: {}.",
                    tombstone.display(),
                ),
            ));
        }
        fs::rename(&prefix, &tombstone)?;

        let cleanup = (|| -> Result<()> {
            observe(LocalRemovalPhase::AfterPrefixRenamed)?;
            remove_owned_workspace_guidance_link(&workspace_root, &prefix)?;
            fs::remove_dir_all(&tombstone)?;
            Ok(())
        })();
        if let Err(mut error) = cleanup {
            if tombstone.exists() && !prefix.exists() {
                if let Err(restore) = fs::rename(&tombstone, &prefix) {
                    error
                        .details
                        .insert("restoreError".to_string(), restore.to_string());
                } else {
                    let _ = ensure_workspace_guidance_link(&workspace_root, &prefix);
                }
            }
            return Err(error);
        }
        Ok(LocalDevelopmentRemoveResult {
            prefix: result_prefix.clone(),
            workspace_root: workspace_root.clone(),
            removed: true,
            schema_version: crate::SCHEMA_VERSION,
        })
    })
}

fn removal_tombstone_path(prefix: &Path) -> Result<PathBuf> {
    let parent = prefix.parent().ok_or_else(|| {
        CliError::new(
            "LOCAL_PREFIX_INVALID",
            format!("Local prefix has no parent: {}", prefix.display()),
        )
    })?;
    let name = prefix.file_name().and_then(|name| name.to_str()).ok_or_else(|| {
        CliError::new(
            "LOCAL_PREFIX_INVALID",
            format!("Local prefix has no UTF-8 name: {}", prefix.display()),
        )
    })?;
    Ok(parent.join(format!(".{name}.removing")))
}

fn reconcile_receipt_owned_removal_tombstone(
    prefix: &Path,
    workspace_root: &Path,
) -> Result<bool> {
    let tombstone = removal_tombstone_path(prefix)?;
    let metadata = match fs::symlink_metadata(&tombstone) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error.into()),
    };
    if prefix.exists() {
        return Err(CliError::new(
            "LOCAL_REMOVAL_CONFLICT",
            format!(
                "Both the local prefix and its interrupted-removal tombstone exist; both were preserved unchanged: {} and {}.",
                prefix.display(),
                tombstone.display(),
            ),
        ));
    }
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(invalid_removal_tombstone(
            &tombstone,
            "tombstone is not an owned directory",
        ));
    }
    validate_removal_tombstone(&tombstone, prefix, workspace_root)?;
    remove_owned_workspace_guidance_link(workspace_root, prefix)?;
    fs::remove_dir_all(&tombstone)?;
    Ok(true)
}

fn validate_removal_tombstone(
    tombstone: &Path,
    prefix: &Path,
    workspace_root: &Path,
) -> Result<()> {
    validate_removal_boundary(prefix, workspace_root)?;
    if fs::canonicalize(tombstone).map_err(|error| {
        invalid_removal_tombstone(tombstone, format!("could not resolve tombstone: {error}"))
    })? != tombstone
    {
        return Err(invalid_removal_tombstone(
            tombstone,
            "tombstone is not its canonical path",
        ));
    }
    let current_target = read_generation_link(&tombstone.join("current"))
        .map_err(|error| invalid_removal_tombstone(tombstone, error.message))?
        .ok_or_else(|| invalid_removal_tombstone(tombstone, "active generation is missing"))?;
    let generation = tombstone.join(&current_target);
    let receipt = read_removal_authority(&generation.join("authority.json"))
        .map_err(|error| invalid_removal_tombstone(tombstone, error.message))?;
    if receipt.authority != LocalDevelopmentAuthority::LocalDevelopment
        || receipt.prefix != prefix
        || receipt.workspace_root != workspace_root
        || current_target != generation_target(&receipt.generation_id)
    {
        return Err(invalid_removal_tombstone(
            tombstone,
            "receipt does not bind the exact prefix, generation, and workspace",
        ));
    }
    let generation_metadata = fs::symlink_metadata(&generation).map_err(|error| {
        invalid_removal_tombstone(
            tombstone,
            format!("could not inspect receipt generation: {error}"),
        )
    })?;
    if !generation_metadata.is_dir()
        || generation_metadata.file_type().is_symlink()
        || fs::canonicalize(&generation).map_err(|error| {
            invalid_removal_tombstone(
                tombstone,
                format!("could not resolve receipt generation: {error}"),
            )
        })? != generation
    {
        return Err(invalid_removal_tombstone(
            tombstone,
            "receipt generation is not an owned canonical directory",
        ));
    }
    Ok(())
}

fn invalid_removal_tombstone(tombstone: &Path, reason: impl std::fmt::Display) -> CliError {
    CliError::new(
        "LOCAL_REMOVAL_TOMBSTONE_INVALID",
        format!(
            "Interrupted-removal tombstone at {} is not receipt-owned and was preserved unchanged: {reason}.",
            tombstone.display(),
        ),
    )
}

fn canonicalize_missing_path(path: &Path) -> Result<PathBuf> {
    let mut cursor = path;
    let mut suffix = Vec::new();
    loop {
        match fs::symlink_metadata(cursor) {
            Ok(_) => break,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        let name = cursor.file_name().ok_or_else(|| {
            CliError::new(
                "LOCAL_PREFIX_INVALID",
                format!("Local prefix has no existing ancestor: {}.", path.display()),
            )
        })?;
        suffix.push(name.to_os_string());
        cursor = cursor.parent().ok_or_else(|| {
            CliError::new(
                "LOCAL_PREFIX_INVALID",
                format!("Local prefix has no existing ancestor: {}.", path.display()),
            )
        })?;
    }
    let mut canonical = fs::canonicalize(cursor)?;
    for name in suffix.into_iter().rev() {
        canonical.push(name);
    }
    Ok(canonical)
}

fn reject_live_local_runtimes(prefix: &Path) -> Result<()> {
    let state_root = prefix.join("state");
    if !state_root.exists() {
        return Ok(());
    }
    let mut pending = vec![state_root];
    while let Some(directory) = pending.pop() {
        let metadata = fs::symlink_metadata(&directory)?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(CliError::new(
                "LOCAL_RUNTIME_STATE_INVALID",
                format!(
                    "Local runtime state must remain an owned directory tree: {}.",
                    directory.display(),
                ),
            ));
        }
        for entry in fs::read_dir(&directory)? {
            let entry = entry?;
            let path = entry.path();
            let file_type = entry.file_type()?;
            if file_type.is_symlink() {
                return Err(CliError::new(
                    "LOCAL_RUNTIME_STATE_INVALID",
                    format!(
                        "Local runtime state cannot traverse a symlink: {}.",
                        path.display(),
                    ),
                ));
            }
            if file_type.is_dir() {
                pending.push(path);
                continue;
            }
            if file_type.is_file() && entry.file_name() == "daemons.json" {
                let descriptors: Vec<crate::runtime::ServerInstanceDescriptor> =
                    serde_json::from_slice(&fs::read(&path)?).map_err(|error| {
                        CliError::new(
                            "LOCAL_RUNTIME_STATE_INVALID",
                            format!(
                                "Could not validate local runtime descriptors at {}: {error}",
                                path.display(),
                            ),
                        )
                    })?;
                if let Some(descriptor) = descriptors
                    .into_iter()
                    .find(|descriptor| local_process_is_alive(descriptor.pid))
                {
                    let mut error = CliError::new(
                        "LOCAL_RUNTIME_ACTIVE",
                        format!(
                            "Refusing to change local-development authority while PID {} is still registered and live for {}.",
                            descriptor.pid, descriptor.workspace_root,
                        ),
                    );
                    error
                        .details
                        .insert("pid".to_string(), descriptor.pid.to_string());
                    error.details.insert(
                        "workspaceRoot".to_string(),
                        descriptor.workspace_root.clone(),
                    );
                    error.details.insert(
                        "descriptorFile".to_string(),
                        path.display().to_string(),
                    );
                    if matches!(descriptor.backend_name.as_str(), "headless" | "idea") {
                        error.details.insert(
                            "stopCommand".to_string(),
                            format!(
                                "'{}' developer runtime stop --workspace-root '{}' --backend={}",
                                shell_single_quote(
                                    &prefix.join("bin/kast").display().to_string(),
                                ),
                                shell_single_quote(&descriptor.workspace_root),
                                descriptor.backend_name,
                            ),
                        );
                    }
                    return Err(error);
                }
            }
        }
    }
    Ok(())
}

fn local_process_is_alive(pid: u64) -> bool {
    if pid == 0 || pid > i32::MAX as u64 {
        return false;
    }
    let result = unsafe { libc::kill(pid as libc::pid_t, 0) };
    result == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalDevelopmentRemovalAuthority {
    schema_version: u32,
    authority: LocalDevelopmentAuthority,
    generation_id: LocalGenerationId,
    workspace_root: PathBuf,
    prefix: PathBuf,
}

fn read_removal_authority(path: &Path) -> Result<LocalDevelopmentRemovalAuthority> {
    let authority: LocalDevelopmentRemovalAuthority =
        serde_json::from_slice(&fs::read(path)?).map_err(|error| {
            CliError::new(
                "LOCAL_AUTHORITY_RECEIPT_INVALID",
                format!(
                    "Could not read core removal authority at {}: {error}",
                    path.display(),
                ),
            )
        })?;
    if authority.schema_version == 0
        || authority.schema_version > LOCAL_DEVELOPMENT_RECEIPT_SCHEMA_VERSION
    {
        return Err(CliError::new(
            "LOCAL_AUTHORITY_RECEIPT_UNSUPPORTED",
            format!(
                "Removal authority schema {} is unsupported; newest supported schema is {}.",
                authority.schema_version, LOCAL_DEVELOPMENT_RECEIPT_SCHEMA_VERSION,
            ),
        ));
    }
    Ok(authority)
}

fn validate_removal_boundary(prefix: &Path, workspace_root: &Path) -> Result<()> {
    if prefix == Path::new("/") || workspace_root.starts_with(prefix) {
        return Err(CliError::new(
            "LOCAL_PREFIX_UNSAFE",
            format!(
                "Refusing to remove local prefix {} because it contains the workspace {}.",
                prefix.display(),
                workspace_root.display(),
            ),
        ));
    }
    Ok(())
}
