fn install_validated_bundle(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
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
    manifest::with_install_lock(&targets.resolved, || {
        manifest::ensure_install_directories(&targets.resolved)?;
        let staged = targets.resolved.install_root.join("versions").join(format!(
            "{}.tmp-{}",
            bundle.version.as_str(),
            std::process::id()
        ));
        manifest::remove_path(&staged)?;
        copy_bundle_tree(&bundle.root, &staged)?;
        manifest::remove_path(&targets.version_dir)?;
        fs::rename(&staged, &targets.version_dir)?;
        link_active_headless_backend(bundle, targets)?;
        manifest::replace_symlink_or_copy(&targets.version_dir, &targets.current_link)?;
        if let Some(previous) = &install_manifest.previous_version {
            let previous_dir = targets
                .resolved
                .install_root
                .join("versions")
                .join(previous);
            if previous_dir.exists() {
                manifest::replace_symlink_or_copy(&previous_dir, &targets.previous_link)?;
            }
        }
        let active_binary = ensure_active_cli_path(bundle, targets)?;
        write_headless_kast_shim(
            &targets.resolved.shim_path,
            &active_binary,
            &targets.resolved.install_root,
            &targets.resolved.config_root,
            &bundle.manifest.activation.shim.java_opts,
        )?;
        write_headless_config(&targets.resolved.config_file)?;
        manifest::write_manifest_atomic(&targets.resolved.manifest_file, &install_manifest)?;
        Ok(())
    })
}

fn project_install_manifest(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<manifest::KastInstallManifest> {
    let previous = if targets.resolved.manifest_file.is_file() {
        let manifest = manifest_from_file(&targets.resolved.manifest_file)?;
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
        managed_paths: vec![
            "bin".to_string(),
            "lib".to_string(),
            "cache".to_string(),
            "logs".to_string(),
        ],
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
    require_file(&targets.resolved.manifest_file, "install manifest")?;
    require_executable(&targets.resolved.shim_path, "kast shim")?;
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
    let manifest = manifest_from_file(&targets.resolved.manifest_file)?;
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
    if manifest.entrypoints.active_binary != targets.resolved.active_binary.display().to_string() {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            "Install manifest activeBinary does not match the projected bundle activation path.",
        ));
    }
    let shim = fs::read_to_string(&targets.resolved.shim_path)?;
    for java_opt in &bundle.manifest.activation.shim.java_opts {
        if !shim.contains(java_opt) {
            return Err(CliError::new(
                "BUNDLE_INSTALL_MISMATCH",
                format!("Installed shim does not include required JVM option `{java_opt}`."),
            ));
        }
    }
    if !shim.contains("KAST_INSTALL_ROOT") || !shim.contains("KAST_CONFIG_HOME") {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            "Installed shim does not export KAST_INSTALL_ROOT and KAST_CONFIG_HOME.",
        ));
    }
    let output = ProcessCommand::new(&targets.resolved.shim_path)
        .arg("ready")
        .env("KAST_INSTALL_ROOT", &targets.resolved.install_root)
        .env("KAST_CONFIG_HOME", &targets.resolved.config_root)
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
            &["ready".to_string()],
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

fn activate_bundle_result(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
    skipped: bool,
    verify_only: bool,
) -> ActivateBundleResult {
    ActivateBundleResult {
        installed_at: targets.version_dir.display().to_string(),
        version: bundle.version.as_str().to_string(),
        platform: bundle.manifest.platform.clone(),
        profile: bundle.manifest.profile.clone(),
        install_root: targets.resolved.install_root.display().to_string(),
        current: targets.current_link.display().to_string(),
        manifest: targets.resolved.manifest_file.display().to_string(),
        active_binary: targets.resolved.active_binary.display().to_string(),
        shim: targets.resolved.shim_path.display().to_string(),
        skipped,
        verify_only,
        schema_version: SCHEMA_VERSION,
    }
}
