pub fn setup(args: SetupArgs) -> Result<SetupResult> {
    let mode = SetupMode::from_force_flag(args.force);
    setup_bundle(args.source, mode, args.profile)
}

fn setup_bundle(
    source: PathBuf,
    mode: SetupMode,
    profile: manifest::SetupProfile,
) -> Result<SetupResult> {
    let kast_home =
        env_path("KAST_HOME").unwrap_or_else(|| manifest::home_dir().join(".local/share/kast"));
    let source = config::normalize(source);
    let scratch = ScratchDir::new("kast-setup")?;
    let bundle_root = bundle_source_root(&source, scratch.path())?;
    let bundle = validate_bundle(&bundle_root)?;
    let targets = activation_target_paths(kast_home, &bundle)?;
    require_force_source_outside_install_root(mode, &bundle.root, &targets.resolved.install_root)?;

    manifest::with_install_lock(&targets.resolved, || {
        recover_path_projection_transaction(&targets)?;
        let mut path_projection_authority = PathProjectionAuthority::capture(&targets)?;
        path_projection_authority.require_profile(profile)?;
        crate::runtime::retire_registered_legacy_headless_daemons(
            &targets.resolved.descriptor_dir,
        )?;
        if mode.is_force() {
            path_projection_authority.preserve_for_force_reset(&targets.resolved.install_root)?;
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
            failure
                .details
                .insert("phase".to_string(), "VERIFY".to_string());
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
