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
        error
            .details
            .insert("legacyRestoreError".to_string(), restore_error.to_string());
    }
    error
}

fn archive_legacy_installations(
    targets: &ActivationTargetPaths,
) -> Result<LegacyInstallationArchive> {
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
        let source_identity = match fs::symlink_metadata(&source) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
            Ok(_) => projection_file_identity(&source)?,
        };
        let target = unique_legacy_archive_path(&backups, name);
        let archive_move = IdentityTransactionalMove::new(
            &source,
            &target,
            source_identity,
            "legacy archive source",
        );
        let archive_move = if name == "legacy-local-bin-kast" {
            archive_move.with_after_validation_barrier("after-legacy-archive-validation")
        } else {
            archive_move
        };
        archive_move.execute()?;
        manifest::sync_parent_directory(&source)?;
        manifest::sync_parent_directory(&target)?;
        archived.push(LegacyInstallationArchiveEntry {
            original: source,
            backup: target,
            identity: source_identity,
        });
    }
    Ok(LegacyInstallationArchive { entries: archived })
}

fn unique_legacy_archive_path(backups: &Path, name: &str) -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static LEGACY_ARCHIVE_COUNTER: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let sequence = LEGACY_ARCHIVE_COUNTER.fetch_add(1, Ordering::Relaxed);
    backups.join(format!("{name}-{}-{nonce}-{sequence}", std::process::id(),))
}
