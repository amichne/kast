fn setup_idea_plugin(
    idea_plugin: PathBuf,
    idea_plugins_dir: Option<PathBuf>,
    config_defaults: Option<PathBuf>,
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
            {"role": "cli", "path": "bin/kast", "sha256": cli_sha256},
            {"role": "idea-plugin", "path": "idea/kast.zip", "sha256": plugin_sha256}
        ]
    }))?;
    bundle_manifest.push(b'\n');
    let manifest_digest = manifest::sha256_bytes(&bundle_manifest);
    let resolved = manifest::default_resolved_paths();
    let targets = idea_activation_target_paths(resolved, &release_digest);
    let plugins_dir = idea_plugins_dir
        .map(config::normalize)
        .map(Ok)
        .unwrap_or_else(default_idea_plugins_dir)?;
    let scratch = ScratchDir::new("kast-idea-setup")?;
    let extracted_plugin = scratch.path().join("plugin");
    extract_idea_plugin_zip(&idea_plugin, &extracted_plugin)?;
    let extracted_plugin_digest = directory_sha256(&extracted_plugin)?;

    manifest::with_install_lock(&targets.resolved, || {
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
            install_user_command(&targets)?;
            return Ok(idea_setup_result(
                &targets,
                (SetupStatus::Current, None),
                &release_digest,
                &cli_sha256,
                &extracted_plugin_digest,
                &manifest_digest,
                &installed_plugin,
            ));
        }

        let plugin_is_current = directory_sha256(&installed_plugin).ok().as_deref()
            == Some(extracted_plugin_digest.as_str());
        if !plugin_is_current {
            require_jetbrains_ides_closed()?;
        }
        let config_defaults = idea_config_defaults(&targets, config_defaults.as_deref())?;
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
    fs::create_dir_all(staged.join("idea"))?;
    fs::create_dir_all(staged.join("config"))?;
    fs::copy(current_exe, staged.join("bin/kast"))?;
    manifest::make_executable(&staged.join("bin/kast"))?;
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
    let contents = fs::read_to_string(previous)?;
    config::validate_toml(&contents)?;
    migrate_missing_idea_launch_choice(contents)
}

fn migrate_missing_idea_launch_choice(mut contents: String) -> Result<String> {
    let mut value: toml::Value = toml::from_str(&contents)?;
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
        components: vec!["cli".to_string(), "idea-plugin".to_string()],
        backends: vec![],
        managed_paths: vec!["bin/kast".to_string(), "idea/kast.zip".to_string()],
        owned_paths: manifest::owned_paths(&targets.resolved),
        shell_rc_patches: vec![],
        repos: vec![],
        schema_version: crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION,
    }
}

fn install_idea_plugin(source: &Path, target: &Path) -> Result<Option<PathBuf>> {
    let parent = target
        .parent()
        .ok_or_else(|| CliError::new("IDE_PROFILE_INVALID", "IDE plugin target has no parent."))?;
    fs::create_dir_all(parent)?;
    let staging = parent.join(format!(".kast-staging-{}", std::process::id()));
    manifest::remove_path(&staging)?;
    copy_bundle_tree(source, &staging)?;
    let backup = if fs::symlink_metadata(target).is_ok() {
        let backup = parent.join(format!(".kast-backup-{}", std::process::id()));
        manifest::remove_path(&backup)?;
        fs::rename(target, &backup)?;
        Some(backup)
    } else {
        None
    };
    if let Err(error) = fs::rename(&staging, target) {
        if let Some(backup) = &backup {
            let _ = fs::rename(backup, target);
        }
        return Err(error.into());
    }
    Ok(backup)
}

fn rollback_idea_plugin(target: &Path, backup: Option<&Path>) -> Result<()> {
    manifest::remove_path(target)?;
    if let Some(backup) = backup {
        fs::rename(backup, target)?;
    }
    Ok(())
}

fn verify_idea_plugin_setup(
    targets: &ActivationTargetPaths,
    installed_plugin: &Path,
    cli_sha256: &str,
    plugin_digest: &str,
    release_digest: &str,
    manifest_digest: &str,
) -> Result<()> {
    let active_cli = targets.current_link.join("bin/kast");
    require_executable(&active_cli, "installed Kast CLI")?;
    let receipt_path = targets.current_link.join(manifest::INSTALL_MANIFEST_FILE);
    require_file(&receipt_path, "install receipt")?;
    let receipt = manifest_from_file(&receipt_path)?;
    let bundle_manifest = targets.current_link.join(BUNDLE_MANIFEST_FILE);
    require_file(&bundle_manifest, "bundle manifest")?;
    if receipt.release_digest != release_digest
        || receipt.manifest_digest != manifest_digest
        || manifest::sha256_file(&bundle_manifest)? != manifest_digest
    {
        return Err(CliError::new(
            "SETUP_VERIFY_FAILED",
            "Installed Kast manifest does not match the setup source.",
        ));
    }
    if manifest::sha256_file(&active_cli)? != cli_sha256 {
        return Err(CliError::new(
            "SETUP_VERIFY_FAILED",
            "Installed Kast CLI does not match the setup source.",
        ));
    }
    if directory_sha256(installed_plugin)? != plugin_digest {
        return Err(CliError::new(
            "SETUP_VERIFY_FAILED",
            "Installed IDEA plugin does not match the setup source.",
        ));
    }
    Ok(())
}

fn idea_setup_result(
    targets: &ActivationTargetPaths,
    activation: (SetupStatus, Option<&Path>),
    release_digest: &str,
    cli_sha256: &str,
    plugin_digest: &str,
    manifest_digest: &str,
    installed_plugin: &Path,
) -> SetupResult {
    let (status, backup) = activation;
    SetupResult {
        result_type: "KAST_SETUP",
        status,
        release_digest: release_digest.to_string(),
        manifest_digest: manifest_digest.to_string(),
        kast_home: targets.resolved.install_root.display().to_string(),
        current: targets.current_link.display().to_string(),
        active_binary: targets.resolved.active_binary.display().to_string(),
        backup: backup.map(|path| path.display().to_string()),
        artifacts: vec![
            SetupArtifact {
                role: "cli".to_string(),
                path: targets.resolved.active_binary.display().to_string(),
                sha256: cli_sha256.to_string(),
                verified: true,
            },
            SetupArtifact {
                role: "idea-plugin".to_string(),
                path: installed_plugin.display().to_string(),
                sha256: plugin_digest.to_string(),
                verified: true,
            },
        ],
        verified: true,
        schema_version: crate::SCHEMA_VERSION,
    }
}

fn macos_platform_id() -> String {
    match env::consts::ARCH {
        "aarch64" => "macos-arm64".to_string(),
        "x86_64" => "macos-x64".to_string(),
        arch => format!("macos-{arch}"),
    }
}

fn extract_idea_plugin_zip(source: &Path, target: &Path) -> Result<()> {
    let file = fs::File::open(source)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        CliError::new(
            "IDE_PLUGIN_ARCHIVE_INVALID",
            format!("Cannot read IDEA plugin ZIP {}: {error}", source.display()),
        )
    })?;
    let mut root_name = None;
    let mut file_count = 0usize;
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|error| CliError::new("IDE_PLUGIN_ARCHIVE_INVALID", error.to_string()))?;
        let enclosed = entry.enclosed_name().ok_or_else(|| {
            CliError::new(
                "IDE_PLUGIN_ARCHIVE_UNSAFE",
                format!("IDEA plugin ZIP contains an unsafe path: {}", entry.name()),
            )
        })?;
        if entry
            .unix_mode()
            .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            return Err(CliError::new(
                "IDE_PLUGIN_ARCHIVE_UNSAFE",
                format!("IDEA plugin ZIP contains a symlink: {}", entry.name()),
            ));
        }
        let mut components = enclosed.components();
        let Some(Component::Normal(first)) = components.next() else {
            continue;
        };
        match &root_name {
            Some(expected) if expected != first => {
                return Err(CliError::new(
                    "IDE_PLUGIN_ARCHIVE_INVALID",
                    "IDEA plugin ZIP must contain exactly one top-level directory.",
                ));
            }
            None => root_name = Some(first.to_os_string()),
            _ => {}
        }
        let relative = components.collect::<PathBuf>();
        if relative.as_os_str().is_empty() {
            continue;
        }
        let output = target.join(relative);
        if entry.is_dir() {
            fs::create_dir_all(&output)?;
        } else {
            if let Some(parent) = output.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut file = fs::File::create(&output)?;
            io::copy(&mut entry, &mut file)?;
            file_count += 1;
        }
    }
    if root_name.is_none() || file_count == 0 {
        return Err(CliError::new(
            "IDE_PLUGIN_ARCHIVE_INVALID",
            "IDEA plugin ZIP must contain one nonempty top-level plugin directory.",
        ));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
pub(crate) fn idea_plugin_directory_matches_archive(
    installed_plugin: &Path,
    plugin_archive: &Path,
) -> Result<bool> {
    if !installed_plugin.is_dir() || !plugin_archive.is_file() {
        return Ok(false);
    }
    let scratch = ScratchDir::new("kast-idea-plugin-preflight")?;
    let extracted = scratch.path().join("plugin");
    extract_idea_plugin_zip(plugin_archive, &extracted)?;
    Ok(directory_sha256(installed_plugin)? == directory_sha256(&extracted)?)
}

fn default_idea_plugins_dir() -> Result<PathBuf> {
    let application_support = manifest::home_dir().join("Library/Application Support");
    let mut candidates = Vec::new();
    for (root, prefixes) in [
        (
            application_support.join("JetBrains"),
            &["IntelliJIdea2026.2", "IdeaIC2026.2"][..],
        ),
        (
            application_support.join("Google"),
            &["AndroidStudio2026.1"][..],
        ),
    ] {
        let entries = match fs::read_dir(&root) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(CliError::new(
                    "IDE_PROFILE_NOT_FOUND",
                    format!("Cannot inspect {}: {error}", root.display()),
                ));
            }
        };
        candidates.extend(
            entries
                .filter_map(std::result::Result::ok)
                .filter(|entry| {
                    entry.file_type().is_ok_and(|kind| kind.is_dir())
                        && entry.file_name().to_str().is_some_and(|name| {
                            prefixes.iter().any(|prefix| name.starts_with(prefix))
                        })
                })
                .map(|entry| entry.path().join("plugins")),
        );
    }
    candidates.sort();
    candidates.dedup();
    match candidates.as_slice() {
        [plugins] => Ok(plugins.clone()),
        [] => Err(CliError::new(
            "IDE_PROFILE_NOT_FOUND",
            "No supported IntelliJ IDEA 2026.2 or Android Studio 2026.1 profile was found; pass --idea-plugins-dir.",
        )),
        _ => Err(CliError::new(
            "IDE_PROFILE_AMBIGUOUS",
            "Multiple supported JetBrains profiles were found; pass --idea-plugins-dir for the selected IntelliJ IDEA or Android Studio host.",
        )),
    }
}

fn require_jetbrains_ides_closed() -> Result<()> {
    if let Ok(state) = env::var("KAST_MACHINE_IDE_STATE") {
        return match state.as_str() {
            "closed" => Ok(()),
            "open" => Err(CliError::new(
                "IDE_RESTART_REQUIRED",
                "Close IntelliJ IDEA or Android Studio, then rerun `kast setup`.",
            )),
            _ => Err(CliError::new(
                "IDE_STATE_INVALID",
                "KAST_MACHINE_IDE_STATE must be `open` or `closed` when set.",
            )),
        };
    }
    #[cfg(target_os = "macos")]
    {
        let output = ProcessCommand::new("pgrep")
            .args([
                "-f",
                "/(IntelliJ IDEA|Android Studio)[^/]*\\.app/Contents/MacOS/",
            ])
            .output()?;
        match output.status.code() {
            Some(1) => Ok(()),
            Some(0) => Err(CliError::new(
                "IDE_RESTART_REQUIRED",
                "Close IntelliJ IDEA or Android Studio, then rerun `kast setup`.",
            )),
            status => Err(CliError::new(
                "IDE_STATE_UNAVAILABLE",
                format!("Could not determine IDE process state: {status:?}."),
            )),
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        Ok(())
    }
}

fn require_regular_file(path: &Path, label: &str) -> Result<()> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        CliError::new(
            "SETUP_COMPONENT_MISSING",
            format!("Cannot read {label} at {}: {error}", path.display()),
        )
    })?;
    if metadata.is_file() && !metadata.file_type().is_symlink() {
        Ok(())
    } else {
        Err(CliError::new(
            "SETUP_COMPONENT_INVALID",
            format!("{label} must be a regular file: {}", path.display()),
        ))
    }
}
