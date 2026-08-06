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
        let config_migration = plan_existing_config_migration(&targets)?;
        let retired_plugin_removal = remove_retired_public_plugins()?;
        let legacy_archive = archive_legacy_installations(&targets)?;
        manifest::remove_path(&targets.resolved.install_root.join("staging"))?;
        fs::create_dir_all(targets.resolved.install_root.join("staging"))?;

        if current_release_matches(&targets) {
            test_path_projection_failure("before-current-migration")
                .map_err(|error| at_setup_phase(error, "MIGRATION"))?;
            test_path_projection_failure("before-current-verify")
                .map_err(|error| at_setup_phase(error, "VERIFY"))?;
            if verify_activated_bundle(&bundle, &targets).is_ok() {
                let result = match setup_result(
                    &bundle,
                    &targets,
                    SetupStatus::Current,
                    legacy_archive.backup_path(),
                    &retired_plugin_removal,
                ) {
                    Ok(result) => result,
                    Err(error) => {
                        return Err(with_legacy_restore(error, &legacy_archive));
                    }
                };
                match install_user_commands(
                    &targets,
                    profile,
                    &path_projection_authority,
                    config_migration.current_patch(),
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

        let activation = match install_validated_bundle(
            &bundle,
            &targets,
            &config_migration,
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
            return Err(activation.rollback_into(with_legacy_restore(
                failure,
                &legacy_archive,
            )));
        }
        test_path_projection_crash("after-bundle-activation");
        let result = match setup_result(
            &bundle,
            &targets,
            SetupStatus::Activated,
            activation
                .backup_path()
                .or_else(|| legacy_archive.backup_path()),
            &retired_plugin_removal,
        ) {
            Ok(result) => result,
            Err(error) => {
                return Err(
                    activation.rollback_into(with_legacy_restore(error, &legacy_archive))
                );
            }
        };
        match install_user_commands(&targets, profile, &path_projection_authority, None) {
            Ok(()) => {}
            Err(UserCommandInstallFailure::BeforeReceipt(error)) => {
                return Err(activation.rollback_into(with_legacy_restore(
                    at_setup_phase(error, "USER_COMMAND"),
                    &legacy_archive,
                )));
            }
            Err(UserCommandInstallFailure::AfterReceipt(error)) => {
                activation.commit();
                return Err(at_setup_phase(error, "USER_COMMAND"));
            }
        }
        activation.commit();
        path_projection_authority.complete_force_reset_recovery()?;
        Ok(result)
    })
}
