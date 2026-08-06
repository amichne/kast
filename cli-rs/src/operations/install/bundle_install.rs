fn install_validated_bundle<'a>(
    bundle: &ValidatedBundle,
    targets: &'a ActivationTargetPaths,
    config_migration: &ExistingConfigMigrationPlan,
    path_projection_authority: &PathProjectionAuthority,
) -> Result<BundleActivationGuard<'a>> {
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
    let install_manifest = project_install_manifest(bundle, targets, path_projection_authority);
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
    link_active_indexer(bundle, &staged)?;
    manifest::make_executable(&staged.join(&bundle.cli_relative))?;
    manifest::make_executable(&staged.join(AGENT_CLI_BUNDLE_PATH))?;
    let staged_config = staged.join("config/config.toml");
    let staged_config = config_migration.stage_for_bundle(&staged_config)?;
    manifest::write_manifest_atomic(
        &staged.join(manifest::INSTALL_MANIFEST_FILE),
        &install_manifest,
    )?;

    staged_config.activate(targets, &staged)
}

fn project_install_manifest(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
    path_projection_authority: &PathProjectionAuthority,
) -> manifest::KastInstallManifest {
    let now = manifest::current_timestamp();
    let normalized_version = bundle.version.normalized();
    let indexer_root = targets.indexer_current_dir.clone();
    let install_id = format!("kast-{}-{}", bundle.manifest.platform, normalized_version);
    let mut install_manifest = manifest::KastInstallManifest {
        tool: "kast".to_string(),
        install_id,
        release_digest: bundle.release_digest.clone(),
        manifest_digest: bundle.manifest_digest.clone(),
        profile: bundle.manifest.profile.clone(),
        setup_profile: manifest::SetupProfile::Standard,
        active_version: bundle.version.as_str().to_string(),
        previous_version: None,
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
            "indexer".to_string(),
            "manifest".to_string(),
        ],
        backends: vec![manifest::BackendComponentState {
            name: "indexer".to_string(),
            version: bundle.manifest.activation.backend.version.clone(),
            install_dir: indexer_root.display().to_string(),
            runtime_libs_dir: indexer_root.join("runtime-libs").display().to_string(),
            idea_home: Some(indexer_root.join("idea-home").display().to_string()),
        }],
        managed_paths: bundle
            .manifest
            .artifacts
            .iter()
            .map(|artifact| artifact.path.clone())
            .collect(),
        owned_paths: manifest::owned_paths(&targets.resolved),
        path_projections: vec![],
        shell_rc_patches: vec![],
        schema_version: crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION,
    };
    path_projection_authority.carry_prior_ownership_into(&mut install_manifest);
    install_manifest
}

fn verify_activated_bundle(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    let receipt = targets.current_link.join(manifest::INSTALL_MANIFEST_FILE);
    let active_binary = targets.current_link.join(&bundle.cli_relative);
    let agent_binary = targets.current_link.join(AGENT_CLI_BUNDLE_PATH);
    require_file(&receipt, "install receipt")?;
    require_executable(&active_binary, "kast CLI")?;
    require_executable(&agent_binary, "kast agent CLI")?;
    if manifest::sha256_file(&active_binary)? != manifest::sha256_file(&agent_binary)? {
        return Err(CliError::new(
            "BUNDLE_INSTALL_MISMATCH",
            "Installed kastctl and kast entrypoints are not byte-identical.",
        ));
    }
    require_directory(&targets.version_dir, "installed bundle version")?;
    if !is_macos_indexer(&bundle.manifest) {
        require_file(
            &targets
                .resolved
                .indexer_runtime_libs_dir
                .join("classpath.txt"),
            "installed runtime classpath",
        )?;
        if let Some(idea_home) = &targets.resolved.indexer_host_home {
            require_file(
                &idea_home.join("lib/nio-fs.jar"),
                "installed IDEA nio-fs.jar",
            )?;
            require_file(
                &idea_home.join("modules/module-descriptors.dat"),
                "installed IDEA module descriptors",
            )?;
        }
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
        .arg("release")
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
            &["ready".to_string(), "--for".to_string(), "release".to_string()],
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
