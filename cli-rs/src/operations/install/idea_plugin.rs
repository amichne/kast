fn setup_idea_plugin(
    idea_plugin: PathBuf,
    idea_plugins_dir: Option<PathBuf>,
    config_defaults: Option<PathBuf>,
    mode: SetupMode,
) -> Result<SetupResult> {
    let idea_plugin = config::normalize(idea_plugin);
    require_regular_file(&idea_plugin, "Kast IDEA plugin ZIP")?;
    let config_defaults = if let Some(path) = config_defaults.map(config::normalize) {
        require_regular_file(&path, "Kast config defaults")?;
        let contents = fs::read_to_string(path)?;
        config::validate_toml(&contents)?;
        Some(contents)
    } else {
        None
    };

    let current_exe = env::current_exe()?;
    require_executable(&current_exe, "running Kast CLI")?;
    let cli_sha256 = manifest::sha256_file(&current_exe)?;
    let plugin_sha256 = manifest::sha256_file(&idea_plugin)?;
    let release_digest =
        manifest::sha256_bytes(format!("{cli_sha256}\n{plugin_sha256}\n").as_bytes());
    let mut bundle_manifest = serde_json::to_vec_pretty(&serde_json::json!({
        "artifacts": [
            {"role": "cli", "path": CONTROL_CLI_BUNDLE_PATH, "sha256": cli_sha256},
            {"role": "agent-cli", "path": AGENT_CLI_BUNDLE_PATH, "sha256": cli_sha256},
            {"role": "idea-plugin", "path": "idea/kast.zip", "sha256": plugin_sha256}
        ]
    }))?;
    bundle_manifest.push(b'\n');
    let manifest_digest = manifest::sha256_bytes(&bundle_manifest);
    let resolved = manifest::default_resolved_paths();
    let targets = idea_activation_target_paths(resolved, &release_digest);
    require_force_source_outside_install_root(
        mode,
        &current_exe,
        &targets.resolved.install_root,
    )?;
    require_force_source_outside_install_root(
        mode,
        &idea_plugin,
        &targets.resolved.install_root,
    )?;
    let plugins_dir = idea_plugins_dir
        .map(config::normalize)
        .map(Ok)
        .unwrap_or_else(default_idea_plugins_dir)?;
    let scratch = ScratchDir::new("kast-idea-setup")?;
    let extracted_plugin = scratch.path().join("plugin");
    extract_idea_plugin_zip(&idea_plugin, &extracted_plugin)?;
    let extracted_plugin_digest = directory_sha256(&extracted_plugin)?;

    manifest::with_install_lock(&targets.resolved, || {
        if mode.is_force() {
            ForceResetPlan::build(&targets, Some(&plugins_dir))?.execute()?;
        }
        let installed_plugin = plugins_dir.join("kast");
        if current_release_matches(&targets)
            && verify_idea_plugin_setup(
                &targets,
                &installed_plugin,
                &cli_sha256,
                &extracted_plugin_digest,
                &release_digest,
                &manifest_digest,
            )
            .is_ok()
        {
            if let Some(config_defaults) = &config_defaults {
                fs::write(
                    targets.current_link.join("config/config.toml"),
                    config_defaults,
                )?;
            }
            let legacy_backup = install_current_idea_user_command(&targets)?;
            return Ok(idea_setup_result(
                &targets,
                (SetupStatus::Current, legacy_backup.as_deref()),
                &release_digest,
                &cli_sha256,
                &extracted_plugin_digest,
                &manifest_digest,
                &installed_plugin,
            ));
        }

        let plugin_is_current = directory_sha256(&installed_plugin).ok().as_deref()
            == Some(extracted_plugin_digest.as_str());
        require_jetbrains_ides_closed()?;
        let config_defaults = if mode.is_force() {
            config_defaults
                .clone()
                .unwrap_or_else(|| DEFAULT_IDEA_CONFIG.to_string())
        } else {
            idea_config_defaults(&targets, config_defaults.as_deref())?
        };
        let legacy_backup = archive_legacy_installations(&targets)?;
        let (previous, release_backup) = install_idea_release(
            &targets,
            &current_exe,
            &idea_plugin,
            &release_digest,
            &config_defaults,
            &bundle_manifest,
            &manifest_digest,
        )?;
        let plugin_backup = if plugin_is_current {
            None
        } else {
            match install_idea_plugin(&extracted_plugin, &installed_plugin) {
                Ok(backup) => Some(backup),
                Err(error) => {
                    rollback_activated_bundle(&targets, previous.as_deref())?;
                    return Err(error);
                }
            }
        };
        if let Err(error) = verify_idea_plugin_setup(
            &targets,
            &installed_plugin,
            &cli_sha256,
            &extracted_plugin_digest,
            &release_digest,
            &manifest_digest,
        ) {
            if let Some(plugin_backup) = &plugin_backup {
                rollback_idea_plugin(&installed_plugin, plugin_backup.as_deref())?;
            }
            rollback_activated_bundle(&targets, previous.as_deref())?;
            return Err(error);
        }

        if let Err(error) = install_user_command(&targets) {
            if let Some(plugin_backup) = &plugin_backup {
                rollback_idea_plugin(&installed_plugin, plugin_backup.as_deref())?;
            }
            rollback_activated_bundle(&targets, previous.as_deref())?;
            return Err(error);
        }
        Ok(idea_setup_result(
            &targets,
            (
                SetupStatus::Activated,
                release_backup.as_deref().or(legacy_backup.as_deref()),
            ),
            &release_digest,
            &cli_sha256,
            &extracted_plugin_digest,
            &manifest_digest,
            &installed_plugin,
        ))
    })
}

fn install_current_idea_user_command(targets: &ActivationTargetPaths) -> Result<Option<PathBuf>> {
    let local_bin = manifest::home_dir().join(".local/bin");
    let backups = targets.resolved.install_root.join("backups");
    fs::create_dir_all(&backups)?;
    let mut moved = Vec::new();
    for (user_command, backup_name) in [
        (local_bin.join("_kastctl"), "legacy-local-bin-kastctl"),
        (local_bin.join("kast"), "legacy-local-bin-kast"),
    ] {
        let is_managed = fs::read_link(&user_command)
            .is_ok_and(|target| target.starts_with(&targets.current_link));
        if is_managed || fs::symlink_metadata(&user_command).is_err() {
            continue;
        }
        let backup = backups.join(backup_name);
        manifest::remove_path(&backup)?;
        fs::rename(&user_command, &backup)?;
        moved.push((user_command, backup));
    }
    if let Err(error) = install_user_command(targets) {
        for (user_command, backup) in &moved {
            manifest::remove_path(user_command)?;
            fs::rename(backup, user_command)?;
        }
        return Err(error);
    }
    Ok(moved.first().map(|(_, backup)| backup.clone()))
}

fn idea_activation_target_paths(
    resolved: manifest::ResolvedKastPaths,
    release_digest: &str,
) -> ActivationTargetPaths {
    let version_dir = resolved.install_root.join("releases").join(release_digest);
    ActivationTargetPaths {
        current_link: resolved.install_root.join("current"),
        previous_link: resolved.install_root.join("previous"),
        headless_current_dir: version_dir.join("lib/backends/headless/current"),
        version_dir,
        resolved,
    }
}

fn install_idea_release(
    targets: &ActivationTargetPaths,
    current_exe: &Path,
    idea_plugin: &Path,
    release_digest: &str,
    config_defaults: &str,
    bundle_manifest: &[u8],
    manifest_digest: &str,
) -> Result<(Option<PathBuf>, Option<PathBuf>)> {
    let staging_root = targets.resolved.install_root.join("staging");
    manifest::remove_path(&staging_root)?;
    fs::create_dir_all(&staging_root)?;
    fs::create_dir_all(targets.resolved.install_root.join("releases"))?;
    fs::create_dir_all(targets.resolved.install_root.join("backups"))?;
    let staged = staging_root.join(format!("{release_digest}-{}", std::process::id()));
    fs::create_dir_all(staged.join("bin"))?;
    fs::create_dir_all(staged.join("libexec"))?;
    fs::create_dir_all(staged.join("idea"))?;
    fs::create_dir_all(staged.join("config"))?;
    fs::copy(current_exe, staged.join(CONTROL_CLI_BUNDLE_PATH))?;
    manifest::make_executable(&staged.join(CONTROL_CLI_BUNDLE_PATH))?;
    fs::copy(current_exe, staged.join(AGENT_CLI_BUNDLE_PATH))?;
    manifest::make_executable(&staged.join(AGENT_CLI_BUNDLE_PATH))?;
    fs::copy(idea_plugin, staged.join("idea/kast.zip"))?;
    fs::write(staged.join("config/config.toml"), config_defaults)?;
    fs::write(staged.join(BUNDLE_MANIFEST_FILE), bundle_manifest)?;
    manifest::write_manifest_atomic(
        &staged.join(manifest::INSTALL_MANIFEST_FILE),
        &idea_install_manifest(
            targets,
            release_digest,
            manifest_digest,
        ),
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

fn idea_config_defaults(
    targets: &ActivationTargetPaths,
    selected: Option<&str>,
) -> Result<String> {
    if let Some(selected) = selected {
        return Ok(selected.to_string());
    }
    let previous = targets.current_link.join("config/config.toml");
    if !previous.is_file() {
        return Ok(DEFAULT_IDEA_CONFIG.to_string());
    }
    let previous_receipt = targets
        .current_link
        .join(manifest::INSTALL_MANIFEST_FILE);
    if !manifest_from_file(&previous_receipt)
        .is_ok_and(|receipt| receipt.profile == "macos-idea")
    {
        return Ok(DEFAULT_IDEA_CONFIG.to_string());
    }
    let contents = fs::read_to_string(previous)?;
    config::validate_toml(&contents)?;
    migrate_missing_idea_launch_choice(contents)
}

fn migrate_missing_idea_launch_choice(mut contents: String) -> Result<String> {
    let mut value: toml::Value = toml::from_str(&contents)?;
    if value
        .get("runtime")
        .and_then(toml::Value::as_table)
        .and_then(|runtime| runtime.get("defaultBackend"))
        .and_then(toml::Value::as_str)
        == Some("headless")
    {
        return Ok(DEFAULT_IDEA_CONFIG.to_string());
    }
    if let Some(launch) = value
        .get_mut("runtime")
        .and_then(toml::Value::as_table_mut)
        .and_then(|runtime| runtime.get_mut("ideaLaunch"))
        .and_then(toml::Value::as_table_mut)
    {
        if launch.contains_key("enabled") {
            return Ok(contents);
        }
        launch.insert("enabled".to_string(), toml::Value::Boolean(true));
        return Ok(toml::to_string_pretty(&value)?);
    }
    if !contents.ends_with('\n') {
        contents.push('\n');
    }
    contents.push_str("\n[runtime.ideaLaunch]\nenabled = true\n");
    Ok(contents)
}

const DEFAULT_IDEA_CONFIG: &str = "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = true\n\n[backends.headless]\nenabled = false\n\n[backends.idea]\nenabled = true\n";

fn idea_install_manifest(
    targets: &ActivationTargetPaths,
    release_digest: &str,
    manifest_digest: &str,
) -> manifest::KastInstallManifest {
    let now = manifest::current_timestamp();
    let version = crate::cli::version().to_string();
    manifest::KastInstallManifest {
        tool: "kast".to_string(),
        install_id: format!("kast-macos-idea-{version}"),
        release_digest: release_digest.to_string(),
        manifest_digest: manifest_digest.to_string(),
        profile: "macos-idea".to_string(),
        active_version: version.clone(),
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
        version: version.clone(),
        backend_version: String::new(),
        installed_at: format!("macos-idea:{version}"),
        platform: macos_platform_id(),
        components: vec![
            "cli".to_string(),
            "agent-cli".to_string(),
            "idea-plugin".to_string(),
        ],
        backends: vec![],
        managed_paths: vec![
            CONTROL_CLI_BUNDLE_PATH.to_string(),
            AGENT_CLI_BUNDLE_PATH.to_string(),
            "idea/kast.zip".to_string(),
        ],
        owned_paths: manifest::owned_paths(&targets.resolved),
        shell_rc_patches: vec![],
        schema_version: crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION,
    }
}

include!("../parts/idea_plugin/installation.rs");
