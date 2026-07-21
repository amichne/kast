fn install_validated_bundle(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<(Option<PathBuf>, Option<PathBuf>)> {
    if bundle.root.starts_with(&targets.resolved.install_root) {
        return Err(CliError::new(
            "BUNDLE_SOURCE_UNSAFE",
            format!(
                "Bundle source {} must not be inside the install root {}.",
                bundle.root.display(),
                targets.resolved.install_root.display()
            ),
        ));
    }
    let install_manifest = project_install_manifest(bundle, targets)?;
    for directory in [
        targets.resolved.install_root.join("releases"),
        targets.resolved.install_root.join("backups"),
        targets.resolved.install_root.join("staging"),
        targets.resolved.install_root.join("state/cache"),
        targets.resolved.install_root.join("state/data"),
        targets.resolved.install_root.join("state/logs"),
        targets.resolved.install_root.join("state/runtime"),
    ] {
        fs::create_dir_all(directory)?;
    }
    let staged = targets
        .resolved
        .install_root
        .join("staging")
        .join(format!("{}-{}", bundle.release_digest, std::process::id()));
    manifest::remove_path(&staged)?;
    copy_bundle_tree(&bundle.root, &staged)?;
    link_active_headless_backend(bundle, &staged)?;
    manifest::make_executable(&staged.join(&bundle.cli_relative))?;
    write_headless_config(&staged.join("config/config.toml"))?;
    manifest::write_manifest_atomic(
        &staged.join(manifest::INSTALL_MANIFEST_FILE),
        &install_manifest,
    )?;

    let (previous, backup) = archive_current_activation(targets)?;
    manifest::remove_path(&targets.version_dir)?;
    fs::rename(&staged, &targets.version_dir)?;
    if let Some(previous) = &previous {
        manifest::replace_symlink_or_copy(previous, &targets.previous_link)?;
    }
    manifest::replace_symlink_or_copy(&targets.version_dir, &targets.current_link)?;
    Ok((previous, backup))
}

fn archive_current_activation(
    targets: &ActivationTargetPaths,
) -> Result<(Option<PathBuf>, Option<PathBuf>)> {
    let backups = targets.resolved.install_root.join("backups");
    fs::create_dir_all(&backups)?;
    if let Ok(mut previous) = fs::read_link(&targets.current_link) {
        if previous.is_relative() {
            previous = targets.resolved.install_root.join(previous);
        }
        let digest = previous
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("previous");
        if previous == targets.version_dir && previous.exists() {
            let backup = backups.join(format!("{digest}-replaced-{}", std::process::id()));
            manifest::remove_path(&backup)?;
            fs::rename(&previous, &backup)?;
            return Ok((Some(backup.clone()), Some(backup)));
        }
        let backup = backups.join(digest);
        manifest::replace_symlink_or_copy(&previous, &backup)?;
        return Ok((Some(previous), Some(backup)));
    }
    if targets.current_link.exists() {
        let backup = backups.join(format!("legacy-current-{}", std::process::id()));
        manifest::remove_path(&backup)?;
        fs::rename(&targets.current_link, &backup)?;
        return Ok((Some(backup.clone()), Some(backup)));
    }
    Ok((None, None))
}

fn rollback_activated_bundle(
    targets: &ActivationTargetPaths,
    previous: Option<&Path>,
) -> Result<()> {
    if let Some(previous) = previous {
        manifest::replace_symlink_or_copy(previous, &targets.current_link)?;
    } else {
        manifest::remove_path(&targets.current_link)?;
    }
    manifest::remove_path(&targets.version_dir)
}

fn project_install_manifest(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<manifest::KastInstallManifest> {
    let active_receipt = targets.current_link.join(manifest::INSTALL_MANIFEST_FILE);
    let previous = if active_receipt.is_file() {
        let manifest = manifest_from_file(&active_receipt)?;
        BundleVersion::parse(&manifest.active_version)
            .ok()
            .filter(|version| version.as_str() != bundle.version.as_str())
            .map(BundleVersion::into_string)
    } else {
        None
    };
    let now = manifest::current_timestamp();
    let normalized_version = bundle.version.normalized();
    let headless_root = targets.headless_current_dir.clone();
    let install_id = format!("kast-{}-{}", bundle.manifest.platform, normalized_version);
    Ok(manifest::KastInstallManifest {
        tool: "kast".to_string(),
        install_id,
        release_digest: bundle.release_digest.clone(),
        manifest_digest: bundle.manifest_digest.clone(),
        profile: bundle.manifest.profile.clone(),
        active_version: bundle.version.as_str().to_string(),
        previous_version: previous,
        created_at: now.clone(),
        updated_at: now,
        roots: manifest::ManifestRoots {
            install: targets.resolved.install_root.display().to_string(),
            bin: targets.resolved.bin_dir.display().to_string(),
            config: targets.resolved.config_root.display().to_string(),
            data: targets.resolved.data_dir.display().to_string(),
            cache: targets.resolved.cache_dir.display().to_string(),
            runtime: targets.resolved.runtime_dir.display().to_string(),
            logs: targets.resolved.logs_dir.display().to_string(),
            locks: targets.resolved.locks_dir.display().to_string(),
        },
        entrypoints: manifest::ManifestEntrypoints {
            shim: targets.resolved.shim_path.display().to_string(),
            active_binary: targets.resolved.active_binary.display().to_string(),
        },
        schemas: manifest::ManifestSchemas::default(),
        version: normalized_version.clone(),
        backend_version: bundle.manifest.activation.backend.version.clone(),
        installed_at: format!("{}:{}", bundle.manifest.platform, bundle.version.as_str()),
        platform: bundle.manifest.platform.clone(),
        components: vec![
            "cli".to_string(),
            "headless-backend".to_string(),
            "manifest".to_string(),
        ],
        backends: vec![manifest::BackendComponentState {
            name: "headless".to_string(),
            version: bundle.manifest.activation.backend.version.clone(),
            install_dir: headless_root.display().to_string(),
            runtime_libs_dir: headless_root.join("runtime-libs").display().to_string(),
            idea_home: Some(headless_root.join("idea-home").display().to_string()),
        }],
        managed_paths: bundle
            .manifest
            .artifacts
            .iter()
            .map(|artifact| artifact.path.clone())
            .collect(),
        owned_paths: manifest::owned_paths(&targets.resolved),
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: SCHEMA_VERSION,
    })
}

fn verify_activated_bundle(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    let receipt = targets.current_link.join(manifest::INSTALL_MANIFEST_FILE);
    let active_binary = targets.current_link.join(&bundle.cli_relative);
    require_file(&receipt, "install receipt")?;
    require_executable(&active_binary, "kast CLI")?;
    require_directory(&targets.version_dir, "installed bundle version")?;
    require_file(
        &targets
            .resolved
            .headless_runtime_libs_dir
            .join("classpath.txt"),
        "installed runtime classpath",
    )?;
    if let Some(idea_home) = &targets.resolved.headless_idea_home {
        require_file(
            &idea_home.join("lib/nio-fs.jar"),
            "installed IDEA nio-fs.jar",
        )?;
        require_file(
            &idea_home.join("modules/module-descriptors.dat"),
            "installed IDEA module descriptors",
        )?;
    }
    let manifest = manifest_from_file(&receipt)?;
    if manifest.active_version != bundle.version.as_str() {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            format!(
                "Install manifest activeVersion is `{}`, expected `{}`.",
                manifest.active_version,
                bundle.version.as_str()
            ),
        ));
    }
    if manifest.entrypoints.active_binary != active_binary.display().to_string() {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            "Install manifest activeBinary does not match the projected bundle activation path.",
        ));
    }
    let output = ProcessCommand::new(&active_binary)
        .arg("ready")
        .arg("--for")
        .arg("machine")
        .env("KAST_HOME", &targets.resolved.install_root)
        .output()
        .map_err(|error| {
            CliError::new(
                "BUNDLE_READY_FAILED",
                format!("Could not run installed kast ready: {error}"),
            )
        })?;
    if output.status.success() {
        Ok(())
    } else {
        Err(command_error(
            "BUNDLE_READY_FAILED",
            "Installed bundle did not pass kast ready",
            &["ready".to_string(), "--for".to_string(), "machine".to_string()],
            &output,
        ))
    }
}

fn manifest_from_file(path: &Path) -> Result<manifest::KastInstallManifest> {
    serde_json::from_str(&fs::read_to_string(path)?).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!("Invalid install manifest at {}: {error}", path.display()),
        )
    })
}
