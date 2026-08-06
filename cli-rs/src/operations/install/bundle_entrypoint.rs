pub fn setup(args: SetupArgs) -> Result<SetupResult> {
    let mode = SetupMode::from_force_flag(args.force);
    setup_bundle(args.source, mode, args.profile)
}

fn setup_bundle(
    source: PathBuf,
    mode: SetupMode,
    profile: manifest::SetupProfile,
) -> Result<SetupResult> {
    let kast_home = env_path("KAST_HOME")
        .unwrap_or_else(|| manifest::home_dir().join(".local/share/kast"));
    let source = config::normalize(source);
    let scratch = ScratchDir::new("kast-setup")?;
    let bundle_root = bundle_source_root(&source, scratch.path())?;
    let bundle = validate_bundle(&bundle_root)?;
    let targets = activation_target_paths(kast_home, &bundle)?;
    require_force_source_outside_install_root(
        mode,
        &bundle.root,
        &targets.resolved.install_root,
    )?;

    manifest::with_install_lock(&targets.resolved, || {
        recover_path_projection_transaction(&targets)?;
        let mut path_projection_authority = PathProjectionAuthority::capture(&targets)?;
        path_projection_authority.require_profile(profile)?;
        crate::runtime::retire_registered_legacy_headless_daemons(
            &targets.resolved.descriptor_dir,
        )?;
        if mode.is_force() {
            path_projection_authority
                .preserve_for_force_reset(&targets.resolved.install_root)?;
            ForceResetPlan::build(&targets)?.execute()?;
            test_path_projection_crash("after-force-reset");
            test_path_projection_failure("after-force-reset")?;
        }
        let migrated_config = plan_existing_config_migration(&targets)?;
        let retired_plugin_removal = remove_retired_public_plugins()?;
        let legacy_archive = archive_legacy_installations(&targets)?;
        manifest::remove_path(&targets.resolved.install_root.join("staging"))?;
        fs::create_dir_all(targets.resolved.install_root.join("staging"))?;

        if current_release_matches(&targets) {
            test_path_projection_failure("before-current-migration")
                .map_err(|error| at_setup_phase(error, "MIGRATION"))?;
            if let Some(contents) = migrated_config.as_deref() {
                write_setup_config_atomic(
                    &targets.current_link.join("config/config.toml"),
                    contents,
                )?;
            }
            test_path_projection_failure("before-current-verify")
                .map_err(|error| at_setup_phase(error, "VERIFY"))?;
            let current_agent_projection = match project_agent_command(&targets) {
                Ok(projection) => projection,
                Err(error) => {
                    return Err(with_legacy_restore(
                        at_setup_phase(error, "USER_COMMAND"),
                        &legacy_archive,
                    ));
                }
            };
            if verify_activated_bundle(&bundle, &targets).is_ok() {
                let result = setup_result(
                    &bundle,
                    &targets,
                    SetupStatus::Current,
                    legacy_archive.backup_path(),
                    &retired_plugin_removal,
                )?;
                match install_user_commands(
                    &targets,
                    profile,
                    &path_projection_authority,
                    Some(current_agent_projection),
                ) {
                    Ok(()) => {}
                    Err(UserCommandInstallFailure::BeforeReceipt(error)) => {
                        return Err(with_legacy_restore(
                            at_setup_phase(error, "USER_COMMAND"),
                            &legacy_archive,
                        ));
                    }
                    Err(UserCommandInstallFailure::AfterReceipt(error)) => {
                        return Err(at_setup_phase(error, "USER_COMMAND"));
                    }
                }
                path_projection_authority.complete_force_reset_recovery()?;
                return Ok(result);
            }
        }

        let (previous, backup) = match install_validated_bundle(
            &bundle,
            &targets,
            migrated_config.as_deref(),
            &path_projection_authority,
        ) {
            Ok(installed) => installed,
            Err(error) => {
                return Err(with_legacy_restore(
                    at_setup_phase(error, "BUNDLE_ACTIVATION"),
                    &legacy_archive,
                ));
            }
        };
        if let Err(error) = verify_activated_bundle(&bundle, &targets) {
            rollback_activated_bundle(&targets, previous.as_deref())?;
            let mut failure = CliError::new(
                "SETUP_VERIFY_FAILED",
                format!("Activated release failed verification and was rolled back: {error}"),
            );
            failure.details.insert("phase".to_string(), "VERIFY".to_string());
            failure.details.insert(
                "rerun".to_string(),
                format!("kastctl setup --source {}", source.display()),
            );
            return Err(with_legacy_restore(failure, &legacy_archive));
        }
        test_path_projection_crash("after-bundle-activation");
        let result = setup_result(
            &bundle,
            &targets,
            SetupStatus::Activated,
            backup.as_deref().or_else(|| legacy_archive.backup_path()),
            &retired_plugin_removal,
        )?;
        match install_user_commands(&targets, profile, &path_projection_authority, None) {
            Ok(()) => {}
            Err(UserCommandInstallFailure::BeforeReceipt(error)) => {
                rollback_activated_bundle(&targets, previous.as_deref())?;
                return Err(with_legacy_restore(
                    at_setup_phase(error, "USER_COMMAND"),
                    &legacy_archive,
                ));
            }
            Err(UserCommandInstallFailure::AfterReceipt(error)) => {
                return Err(at_setup_phase(error, "USER_COMMAND"));
            }
        }
        path_projection_authority.complete_force_reset_recovery()?;
        Ok(result)
    })
}

fn plan_existing_config_migration(targets: &ActivationTargetPaths) -> Result<Option<String>> {
    let config_file = targets.current_link.join("config/config.toml");
    let contents = match fs::read_to_string(&config_file) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    match crate::runtime::plan_legacy_backend_migration(&contents)? {
        crate::runtime::LegacyBackendMigrationPlan::NoChange => Ok(None),
        crate::runtime::LegacyBackendMigrationPlan::Replace(patch) => {
            Ok(Some(patch.migrated_contents().to_string()))
        }
    }
}

fn write_setup_config_atomic(path: &Path, contents: &str) -> Result<()> {
    let parent = path.parent().ok_or_else(|| {
        CliError::new(
            "SETUP_MIGRATION_TARGET_INVALID",
            "The setup configuration has no parent directory.",
        )
    })?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".config.toml.{}.tmp", std::process::id()));
    fs::write(&temporary, contents)?;
    if let Err(error) = fs::rename(&temporary, path) {
        let _ = fs::remove_file(&temporary);
        return Err(error.into());
    }
    Ok(())
}

include!("bundle_entrypoint/legacy_plugin_removal.rs");

fn at_setup_phase(mut error: CliError, phase: &'static str) -> CliError {
    error.details.insert("phase".to_string(), phase.to_string());
    error
}

fn at_setup_step(mut error: CliError, step: &'static str) -> CliError {
    error.details.insert("step".to_string(), step.to_string());
    error
}

fn with_legacy_restore(
    mut error: CliError,
    legacy_archive: &LegacyInstallationArchive,
) -> CliError {
    if let Err(restore_error) = legacy_archive.restore() {
        error.details.insert(
            "legacyRestoreError".to_string(),
            restore_error.to_string(),
        );
    }
    error
}

fn archive_legacy_installations(targets: &ActivationTargetPaths) -> Result<LegacyInstallationArchive> {
    let backups = targets.resolved.install_root.join("backups");
    fs::create_dir_all(&backups)?;
    let home = manifest::home_dir();
    let user_command = home.join(".local/bin/_kastctl");
    let user_command_is_managed =
        managed_user_command(&user_command, &targets.resolved.install_root, &[]);
    let mut legacy = vec![
        (
            targets.resolved.install_root.join("install.json"),
            "legacy-install.json",
        ),
        (home.join(".config/kast"), "legacy-config"),
        (
            home.join("Library/Application Support/Kast/machine"),
            "legacy-machine",
        ),
        (
            home.join("Library/Application Support/Kast/homebrew-install.json"),
            "legacy-homebrew-install.json",
        ),
    ];
    if !user_command_is_managed {
        legacy.push((user_command, "legacy-local-bin-kastctl"));
    }
    let agent_user_command = home.join(".local/bin/kast");
    let agent_user_command_is_managed = fs::read_link(&agent_user_command)
        .is_ok_and(|target| target.starts_with(&targets.current_link));
    if !agent_user_command_is_managed {
        legacy.push((agent_user_command, "legacy-local-bin-kast"));
    }
    let mut archived = Vec::new();
    for (source, name) in legacy {
        let source_identity = match fs::symlink_metadata(&source) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
            Ok(_) => projection_file_identity(&source)?,
        };
        let target = unique_legacy_archive_path(&backups, name);
        let archive_move = IdentityTransactionalMove::new(
            &source,
            &target,
            source_identity,
            "legacy archive source",
        );
        let archive_move = if name == "legacy-local-bin-kast" {
            archive_move.with_after_validation_barrier(
                "after-legacy-archive-validation",
            )
        } else {
            archive_move
        };
        archive_move.execute()?;
        manifest::sync_parent_directory(&source)?;
        manifest::sync_parent_directory(&target)?;
        archived.push(LegacyInstallationArchiveEntry {
            original: source,
            backup: target,
            identity: source_identity,
        });
    }
    Ok(LegacyInstallationArchive { entries: archived })
}

fn unique_legacy_archive_path(backups: &Path, name: &str) -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static LEGACY_ARCHIVE_COUNTER: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let sequence = LEGACY_ARCHIVE_COUNTER.fetch_add(1, Ordering::Relaxed);
    backups.join(format!(
        "{name}-{}-{nonce}-{sequence}",
        std::process::id(),
    ))
}

#[derive(Debug)]
enum UserCommandInstallFailure {
    BeforeReceipt(CliError),
    AfterReceipt(CliError),
}

fn install_user_commands(
    targets: &ActivationTargetPaths,
    profile: manifest::SetupProfile,
    path_projection_authority: &PathProjectionAuthority,
    existing_agent_projection: Option<AgentCommandProjection>,
) -> std::result::Result<(), UserCommandInstallFailure> {
    let local_bin = manifest::home_dir().join(".local/bin");
    let obsolete_control = local_bin.join("_kastctl");
    if managed_user_command(
        &obsolete_control,
        &targets.resolved.install_root,
        &[],
    ) {
        manifest::remove_path(&obsolete_control)
            .map_err(UserCommandInstallFailure::BeforeReceipt)?;
    }
    let user_command = local_bin.join("kast");
    let agent_binary = targets.current_link.join(AGENT_CLI_BUNDLE_PATH);
    let control_command = local_bin.join("kastctl");
    let control_binary = path_projection_authority.control_target().to_path_buf();
    let receipt_path = targets
        .current_link
        .join(manifest::INSTALL_MANIFEST_FILE);
    let mut receipt = manifest_from_file(&receipt_path).map_err(|error| {
        let mut error = at_setup_step(error, "READ_INSTALL_RECEIPT");
        error
            .details
            .insert("receiptPath".to_string(), receipt_path.display().to_string());
        error.details.insert(
            "currentTarget".to_string(),
            fs::read_link(&targets.current_link)
                .map(|path| path.display().to_string())
                .unwrap_or_else(|read_error| format!("unavailable: {read_error}")),
        );
        error.details.insert(
            "versionReceiptExists".to_string(),
            targets
                .version_dir
                .join(manifest::INSTALL_MANIFEST_FILE)
                .is_file()
                .to_string(),
        );
        UserCommandInstallFailure::BeforeReceipt(error)
    })?;
    receipt
        .owned_paths
        .retain(|path| {
            let path = Path::new(path);
            path != obsolete_control && path != user_command && path != control_command
        });
    let user_command_state = user_command.display().to_string();
    receipt.owned_paths.push(user_command_state);
    receipt.setup_profile = profile;
    receipt.schema_version = crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION;
    receipt.updated_at = manifest::current_timestamp();
    receipt.path_projections = vec![manifest::PathProjectionReceipt {
        command: manifest::PathProjectionCommand::Kast,
        path: user_command.display().to_string(),
        target: agent_binary.display().to_string(),
    }];
    if profile.projects_control_command() {
        receipt
            .owned_paths
            .push(control_command.display().to_string());
        receipt
            .path_projections
            .push(manifest::PathProjectionReceipt {
                command: manifest::PathProjectionCommand::Kastctl,
                path: control_command.display().to_string(),
                target: control_binary.display().to_string(),
            });
    }
    #[cfg(unix)]
    {
        let agent_projection = match existing_agent_projection {
            Some(projection) => projection,
            None => project_agent_command(targets)
                .map_err(UserCommandInstallFailure::BeforeReceipt)?,
        };
        let receipt_publication = (|| {
            let mut transaction = path_projection_authority.begin_transaction(
                profile,
                &receipt_path,
                &receipt.release_digest,
                &targets.resolved.install_root,
            )
            .map_err(UserCommandInstallFailure::BeforeReceipt)?;
            if let Some(transaction) = transaction.as_mut() {
                transaction
                    .apply()
                    .map_err(|error| {
                        UserCommandInstallFailure::BeforeReceipt(at_setup_step(
                            error,
                            "PROJECT_CONTROL_COMMAND",
                        ))
                    })?;
            }
            test_path_projection_crash("after-control-apply");
            let receipt_write = test_path_projection_failure("before-receipt-write")
                .and_then(|()| manifest::write_manifest_atomic(&receipt_path, &receipt));
            if let Err(mut error) = receipt_write {
                if exact_install_receipt_is_visible(&receipt_path, &receipt) {
                    error
                        .details
                        .insert("receiptVisible".to_string(), "true".to_string());
                    error.details.insert(
                        "receiptDurability".to_string(),
                        "AMBIGUOUS".to_string(),
                    );
                    return Err(UserCommandInstallFailure::AfterReceipt(at_setup_step(
                        error,
                        "WRITE_INSTALL_RECEIPT",
                    )));
                }
                if let Some(transaction) = transaction.take()
                    && let Err(rollback_error) = transaction.rollback_preserving_journal()
                {
                    error.details.insert(
                        "pathProjectionRollbackError".to_string(),
                        rollback_error.to_string(),
                    );
                }
                return Err(UserCommandInstallFailure::BeforeReceipt(at_setup_step(
                    error,
                    "WRITE_INSTALL_RECEIPT",
                )));
            }
            Ok(transaction)
        })();
        let transaction = match receipt_publication {
            Ok(transaction) => transaction,
            Err(UserCommandInstallFailure::BeforeReceipt(mut error)) => {
                if let Err(rollback_error) = agent_projection.rollback() {
                    error.details.insert(
                        "agentProjectionRollbackError".to_string(),
                        rollback_error.to_string(),
                    );
                }
                return Err(UserCommandInstallFailure::BeforeReceipt(error));
            }
            Err(UserCommandInstallFailure::AfterReceipt(error)) => {
                return Err(UserCommandInstallFailure::AfterReceipt(error));
            }
        };
        test_path_projection_crash("after-receipt-commit");
        if let Err(mut error) =
            test_path_projection_failure("before-control-transaction-finalize")
        {
            error
                .details
                .insert("receiptCommitted".to_string(), "true".to_string());
            return Err(UserCommandInstallFailure::AfterReceipt(at_setup_step(
                error,
                "COMMIT_CONTROL_PROJECTION",
            )));
        }
        if let Some(transaction) = transaction
            && let Err(mut error) = transaction.commit()
        {
            error
                .details
                .insert("receiptCommitted".to_string(), "true".to_string());
            return Err(UserCommandInstallFailure::AfterReceipt(at_setup_step(
                error,
                "COMMIT_CONTROL_PROJECTION",
            )));
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (
            user_command,
            agent_binary,
            control_command,
            control_binary,
            existing_agent_projection,
        );
        if let Err(mut error) = manifest::write_manifest_atomic(&receipt_path, &receipt) {
            let failure = if exact_install_receipt_is_visible(&receipt_path, &receipt) {
                error
                    .details
                    .insert("receiptVisible".to_string(), "true".to_string());
                error.details.insert(
                    "receiptDurability".to_string(),
                    "AMBIGUOUS".to_string(),
                );
                UserCommandInstallFailure::AfterReceipt(error)
            } else {
                UserCommandInstallFailure::BeforeReceipt(error)
            };
            return Err(failure);
        }
    }
    Ok(())
}

fn exact_install_receipt_is_visible(
    path: &Path,
    receipt: &manifest::KastInstallManifest,
) -> bool {
    let Ok(mut expected) = serde_json::to_vec_pretty(receipt) else {
        return false;
    };
    expected.push(b'\n');
    fs::read(path).is_ok_and(|contents| contents == expected)
}

#[cfg(unix)]
fn project_agent_command(targets: &ActivationTargetPaths) -> Result<AgentCommandProjection> {
    let path = manifest::home_dir().join(".local/bin/kast");
    let target = targets.current_link.join(AGENT_CLI_BUNDLE_PATH);
    AgentCommandProjection::project(&path, &target)
        .map_err(|error| at_setup_step(error, "PROJECT_USER_COMMAND"))
}

#[cfg(not(unix))]
fn project_agent_command(_targets: &ActivationTargetPaths) -> Result<AgentCommandProjection> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "PATH projection requires a Unix platform.",
    ))
}

fn current_release_matches(targets: &ActivationTargetPaths) -> bool {
    match (
        fs::canonicalize(&targets.current_link),
        fs::canonicalize(&targets.version_dir),
    ) {
        (Ok(current), Ok(version)) => current == version,
        _ => false,
    }
}

fn setup_result(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
    status: SetupStatus,
    backup: Option<&Path>,
    retired_plugin_removal: &RetiredPublicPluginRemoval,
) -> Result<SetupResult> {
    Ok(SetupResult {
        result_type: "KAST_SETUP",
        status,
        release_digest: bundle.release_digest.clone(),
        manifest_digest: bundle.manifest_digest.clone(),
        kast_home: targets.resolved.install_root.display().to_string(),
        current: targets.current_link.display().to_string(),
        active_binary: targets.resolved.active_binary.display().to_string(),
        developer_operations: DeveloperOperationsRoute::try_from_cli_path(
            &targets.resolved.active_binary,
        )?,
        backup: backup.map(|path| path.display().to_string()),
        restart_requirement: retired_plugin_removal.restart_requirement.clone(),
        artifacts: bundle
            .manifest
            .artifacts
            .iter()
            .map(|artifact| SetupArtifact {
                role: artifact.role.clone(),
                path: targets.current_link.join(&artifact.path).display().to_string(),
                sha256: artifact.sha256.clone(),
                verified: true,
            })
            .collect(),
        verified: true,
        schema_version: SCHEMA_VERSION,
    })
}
