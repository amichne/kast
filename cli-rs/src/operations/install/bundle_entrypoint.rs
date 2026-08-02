pub fn setup(args: SetupArgs) -> Result<SetupResult> {
    let mode = SetupMode::from_force_flag(args.force);
    setup_bundle(args.source, mode)
}

fn setup_bundle(source: PathBuf, mode: SetupMode) -> Result<SetupResult> {
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
        if mode.is_force() {
            ForceResetPlan::build(&targets)?.execute()?;
        }
        let migrated_config = plan_existing_config_migration(&targets)?;
        let public_plugin_migration = remove_known_public_plugins()?;
        let legacy_archive = archive_legacy_installations(&targets)?;
        manifest::remove_path(&targets.resolved.install_root.join("staging"))?;
        fs::create_dir_all(targets.resolved.install_root.join("staging"))?;

        if current_release_matches(&targets) {
            if let Err(error) = install_user_command(&targets) {
                return Err(with_legacy_restore(
                    at_setup_phase(error, "USER_COMMAND"),
                    &legacy_archive,
                ));
            }
            if let Some(contents) = migrated_config.as_deref() {
                write_setup_config_atomic(
                    &targets.current_link.join("config/config.toml"),
                    contents,
                )?;
            }
            if verify_activated_bundle(&bundle, &targets).is_ok() {
                return Ok(setup_result(
                    &bundle,
                    &targets,
                    SetupStatus::Current,
                    legacy_archive.backup_path(),
                    &public_plugin_migration,
                ));
            }
        }

        let (previous, backup) = match install_validated_bundle(
            &bundle,
            &targets,
            migrated_config.as_deref(),
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
        if let Err(error) = install_user_command(&targets) {
            rollback_activated_bundle(&targets, previous.as_deref())?;
            return Err(with_legacy_restore(
                at_setup_phase(error, "USER_COMMAND"),
                &legacy_archive,
            ));
        }
        Ok(setup_result(
            &bundle,
            &targets,
            SetupStatus::Activated,
            backup.as_deref().or_else(|| legacy_archive.backup_path()),
            &public_plugin_migration,
        ))
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

fn remove_known_public_plugins() -> Result<PublicPluginMigration> {
    let mut removed_public_plugin = false;
    for plugins in supported_idea_plugins_dirs()? {
        let public_plugin = validated_child(
            &plugins,
            "kast",
            "Kast public IDEA plugin",
        )?;
        removed_public_plugin |= fs::symlink_metadata(&public_plugin).is_ok();
        manifest::remove_path(&public_plugin)?;
        let profile = plugins.parent().ok_or_else(|| {
            CliError::new(
                "SETUP_MIGRATION_TARGET_INVALID",
                format!(
                    "IDEA plugins directory has no profile parent: {}",
                    plugins.display()
                ),
            )
        })?;
        manifest::remove_path(&validated_child(
            profile,
            ".kast-plugin-backup",
            "Kast public IDEA plugin backup",
        )?)?;
    }
    let restart_requirement = if removed_public_plugin && foreground_ide_is_open()? {
        Some(SetupRestartRequirement {
            code: "FOREGROUND_IDE_RESTART_REQUIRED",
            message: "Restart IntelliJ IDEA or Android Studio to unload the retired public Kast plugin. Kast did not stop, close, or relaunch the application.",
        })
    } else {
        None
    };
    Ok(PublicPluginMigration {
        restart_requirement,
    })
}

include!("bundle_entrypoint/idea_migration.rs");

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
        if fs::symlink_metadata(&source).is_err() {
            continue;
        }
        let target = backups.join(name);
        manifest::remove_path(&target)?;
        fs::rename(&source, &target)?;
        archived.push(LegacyInstallationArchiveEntry {
            original: source,
            backup: target,
        });
    }
    Ok(LegacyInstallationArchive { entries: archived })
}

fn install_user_command(targets: &ActivationTargetPaths) -> Result<()> {
    let local_bin = manifest::home_dir().join(".local/bin");
    let obsolete_control = local_bin.join("_kastctl");
    if managed_user_command(
        &obsolete_control,
        &targets.resolved.install_root,
        &[],
    ) {
        manifest::remove_path(&obsolete_control)?;
    }
    let user_command = local_bin.join("kast");
    let agent_binary = targets.current_link.join(AGENT_CLI_BUNDLE_PATH);
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
        error
    })?;
    receipt
        .owned_paths
        .retain(|path| Path::new(path) != obsolete_control);
    let user_command_state = user_command.display().to_string();
    if !receipt.owned_paths.contains(&user_command_state) {
        receipt.owned_paths.push(user_command_state);
    }
    manifest::write_manifest_atomic(&receipt_path, &receipt)
        .map_err(|error| at_setup_step(error, "WRITE_INSTALL_RECEIPT"))?;
    #[cfg(unix)]
    manifest::replace_symlink_or_copy(&agent_binary, &user_command)
        .map_err(|error| at_setup_step(error, "PROJECT_USER_COMMAND"))?;
    #[cfg(not(unix))]
    let _ = (user_command, agent_binary);
    Ok(())
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
    public_plugin_migration: &PublicPluginMigration,
) -> SetupResult {
    SetupResult {
        result_type: "KAST_SETUP",
        status,
        release_digest: bundle.release_digest.clone(),
        manifest_digest: bundle.manifest_digest.clone(),
        kast_home: targets.resolved.install_root.display().to_string(),
        current: targets.current_link.display().to_string(),
        active_binary: targets.resolved.active_binary.display().to_string(),
        backup: backup.map(|path| path.display().to_string()),
        restart_requirement: public_plugin_migration.restart_requirement.clone(),
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
    }
}
