pub fn setup(args: SetupArgs) -> Result<SetupResult> {
    match (args.source, args.idea_plugin) {
        (Some(source), None) => setup_bundle(source),
        (None, Some(idea_plugin)) => setup_idea_plugin(
            idea_plugin,
            args.idea_plugins_dir,
            args.config_defaults,
        ),
        _ => Err(CliError::new(
            "CLI_USAGE",
            "Pass exactly one of --source or --idea-plugin.",
        )),
    }
}

fn setup_bundle(source: PathBuf) -> Result<SetupResult> {
    let kast_home = env_path("KAST_HOME")
        .unwrap_or_else(|| manifest::home_dir().join(".local/share/kast"));
    let source = config::normalize(source);
    let scratch = ScratchDir::new("kast-setup")?;
    let bundle_root = bundle_source_root(&source, scratch.path())?;
    let bundle = validate_bundle(&bundle_root)?;
    let targets = activation_target_paths(kast_home, &bundle)?;

    manifest::with_install_lock(&targets.resolved, || {
        let legacy_backup = archive_legacy_installations(&targets)?;
        manifest::remove_path(&targets.resolved.install_root.join("staging"))?;
        fs::create_dir_all(targets.resolved.install_root.join("staging"))?;

        if current_release_matches(&targets) && verify_activated_bundle(&bundle, &targets).is_ok() {
            install_user_command(&targets)?;
            return Ok(setup_result(
                &bundle,
                &targets,
                SetupStatus::Current,
                None,
            ));
        }

        let (previous, backup) = install_validated_bundle(&bundle, &targets)?;
        if let Err(error) = verify_activated_bundle(&bundle, &targets) {
            rollback_activated_bundle(&targets, previous.as_deref())?;
            let mut failure = CliError::new(
                "SETUP_VERIFY_FAILED",
                format!("Activated release failed verification and was rolled back: {error}"),
            );
            failure.details.insert("phase".to_string(), "VERIFY".to_string());
            failure.details.insert(
                "rerun".to_string(),
                format!("kast setup --source {}", source.display()),
            );
            return Err(failure);
        }
        install_user_command(&targets)?;
        Ok(setup_result(
            &bundle,
            &targets,
            SetupStatus::Activated,
            backup.as_deref().or(legacy_backup.as_deref()),
        ))
    })
}

fn archive_legacy_installations(targets: &ActivationTargetPaths) -> Result<Option<PathBuf>> {
    let backups = targets.resolved.install_root.join("backups");
    fs::create_dir_all(&backups)?;
    let home = manifest::home_dir();
    let user_command = home.join(".local/bin/kast");
    let user_command_target = &targets.resolved.active_binary;
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
    if fs::read_link(&user_command).ok().as_deref() != Some(user_command_target.as_path()) {
        legacy.push((user_command, "legacy-local-bin-kast"));
    }
    let mut archived = None;
    for (source, name) in legacy {
        if fs::symlink_metadata(&source).is_err() {
            continue;
        }
        let target = backups.join(name);
        manifest::remove_path(&target)?;
        fs::rename(&source, &target)?;
        archived = Some(target);
    }
    Ok(archived)
}

fn install_user_command(targets: &ActivationTargetPaths) -> Result<()> {
    #[cfg(unix)]
    manifest::replace_symlink_or_copy(
        &targets.resolved.active_binary,
        &manifest::home_dir().join(".local/bin/kast"),
    )?;
    Ok(())
}

fn current_release_matches(targets: &ActivationTargetPaths) -> bool {
    fs::canonicalize(&targets.current_link).ok() == fs::canonicalize(&targets.version_dir).ok()
}

fn setup_result(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
    status: SetupStatus,
    backup: Option<&Path>,
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
