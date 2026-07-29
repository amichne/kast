fn install_idea_plugin(source: &Path, target: &Path) -> Result<Option<PathBuf>> {
    let parent = target
        .parent()
        .ok_or_else(|| CliError::new("IDE_PROFILE_INVALID", "IDE plugin target has no parent."))?;
    let profile = parent.parent().ok_or_else(|| {
        CliError::new(
            "IDE_PROFILE_INVALID",
            "IDE plugin directory has no profile root.",
        )
    })?;
    fs::create_dir_all(parent)?;
    let staging = parent.join(format!(".kast-staging-{}", std::process::id()));
    manifest::remove_path(&staging)?;
    copy_bundle_tree(source, &staging)?;
    let backup = if fs::symlink_metadata(target).is_ok() {
        let backup = profile.join(".kast-plugin-backup");
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
    let active_agent_cli = targets.current_link.join(KAGENT_BUNDLE_PATH);
    require_executable(&active_cli, "installed Kast CLI")?;
    require_executable(&active_agent_cli, "installed Kagent CLI")?;
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
    if manifest::sha256_file(&active_cli)? != cli_sha256
        || manifest::sha256_file(&active_agent_cli)? != cli_sha256
    {
        return Err(CliError::new(
            "SETUP_VERIFY_FAILED",
            "Installed Kast and Kagent CLIs do not match the setup source.",
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
                role: "agent-cli".to_string(),
                path: targets
                    .current_link
                    .join(KAGENT_BUNDLE_PATH)
                    .display()
                    .to_string(),
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
