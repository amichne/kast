pub fn repair_install_state(args: InstallRepairArgs) -> Result<InstallRepairResult> {
    reconcile_install_state(args)
}

fn reconcile_install_state(args: InstallRepairArgs) -> Result<InstallRepairResult> {
    let config_path = config::global_config_path();
    let backup_root = config::kast_config_home()
        .join("backups")
        .join(format!("install-repair-{}", backup_timestamp()));
    let mut result = InstallRepairResult {
        applied: args.apply,
        config_path: config_path.display().to_string(),
        apply_command: "kast repair --apply".to_string(),
        actions: vec![],
        backups: vec![],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    };
    let mut config_backed_up = false;

    if !repair_macos_homebrew_cli_authority(&args, &mut result, &backup_root)? {
        return Ok(result);
    }

    if !config_path.is_file() {
        push_repair_action(
            &mut result,
            "provision-config",
            &config_path,
            "Create the global Kast config from current defaults.",
            None,
        );
        if args.apply {
            config::init_config()?;
        }
    }

    let Some(global_config) =
        load_global_config_for_repair(&args, &mut result, &backup_root, &mut config_backed_up)?
    else {
        return Ok(result);
    };
    repair_install_config_state(
        &args,
        &global_config,
        &mut result,
        &backup_root,
        &mut config_backed_up,
    )?;
    repair_install_shell_sources(&args, &mut result, &backup_root)?;
    repair_recognized_legacy_idea_plugin_links(&args, &mut result, &backup_root)?;

    Ok(result)
}

fn load_global_config_for_repair(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<Option<config::KastConfig>> {
    match config::KastConfig::load_global() {
        Ok(global_config) => Ok(Some(global_config)),
        Err(error) if error.code == "CONFIG_ERROR" => {
            let config_path = config::global_config_path();
            push_repair_action(
                result,
                "recover-invalid-config",
                &config_path,
                "Back up the invalid global Kast config and restore safe defaults.",
                Some("kast repair --apply".to_string()),
            );
            if !args.apply {
                result.warnings.push(format!(
                    "Global config is invalid at {}; rerun with --apply to back it up and restore safe defaults: {}",
                    config_path.display(),
                    error.message
                ));
                return Ok(None);
            }
            backup_config_once(result, backup_root, config_backed_up)?;
            if let Some(parent) = config_path.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(&config_path, config::default_config_template()?)?;
            Ok(Some(config::KastConfig::load_global()?))
        }
        Err(error) => Err(error),
    }
}

fn repair_install_config_state(
    args: &InstallRepairArgs,
    global_config: &config::KastConfig,
    result: &mut InstallRepairResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<()> {
    let config_path = config::global_config_path();
    let document = read_toml_document(&config_path)?;
    let remove_paths_table = document.contains_key("paths");
    let remove_cli_binary_path = document
        .get("cli")
        .and_then(toml::Value::as_table)
        .is_some_and(|cli| cli.contains_key("binaryPath"));
    let remove_install_table = document.contains_key("install");
    let remove_headless_runtime_paths = document
        .get("backends")
        .and_then(toml::Value::as_table)
        .and_then(|backends| backends.get("headless"))
        .and_then(toml::Value::as_table)
        .is_some_and(|headless| {
            headless.contains_key("runtimeLibsDir") || headless.contains_key("ideaHome")
        });

    if remove_paths_table
        || remove_cli_binary_path
        || remove_install_table
        || remove_headless_runtime_paths
    {
        push_repair_action(
            result,
            "remove-install-owned-config",
            &config_path,
            "Remove install-owned TOML keys so install identity and paths resolve only from the active install authority.",
            None,
        );
    }
    if args.apply
        && (remove_paths_table
            || remove_cli_binary_path
            || remove_install_table
            || remove_headless_runtime_paths)
    {
        backup_config_once(result, backup_root, config_backed_up)?;
        self_mgmt::update_global_config(|document| {
            if remove_paths_table {
                document.remove("paths");
            }
            if remove_cli_binary_path
                && let Some(toml::Value::Table(cli)) = document.get_mut("cli")
            {
                cli.remove("binaryPath");
                if cli.is_empty() {
                    document.remove("cli");
                }
            }
            if remove_install_table {
                document.remove("install");
            }
            if remove_headless_runtime_paths
                && let Some(toml::Value::Table(backends)) = document.get_mut("backends")
                && let Some(toml::Value::Table(headless)) = backends.get_mut("headless")
            {
                headless.remove("runtimeLibsDir");
                headless.remove("ideaHome");
            }
            Ok(())
        })?;
    }

    let Some(mut install) = self_mgmt::read_global_install_state()? else {
        return Ok(());
    };
    let homebrew_authority_active = match resolve_macos_homebrew_authority() {
        MacosHomebrewAuthorityResolution::Active(_) => true,
        MacosHomebrewAuthorityResolution::Recoverable(_)
        | MacosHomebrewAuthorityResolution::Blocked(_)
            if !args.apply =>
        {
            true
        }
        MacosHomebrewAuthorityResolution::Absent => false,
        MacosHomebrewAuthorityResolution::Recoverable(_) => false,
        MacosHomebrewAuthorityResolution::Blocked(error) => return Err(error),
    };
    if homebrew_authority_active {
        repair_legacy_macos_install_identity(args, &install, result, backup_root)?;
        return Ok(());
    }
    let mut install_changed = false;
    if install.version.trim() != cli::version() {
        push_repair_action(
            result,
            "update-install-version",
            &config_path,
            &format!(
                "Record the running kast CLI version {} in install metadata.",
                cli::version()
            ),
            None,
        );
        install_changed = true;
    }

    let mut surviving_backends = vec![];
    for backend in install.backends {
        let unsupported = backend.name != HEADLESS_BACKEND_NAME;
        let classpath_missing = !Path::new(&backend.runtime_libs_dir)
            .join("classpath.txt")
            .is_file();
        let install_dir_missing = !path_exists_or_symlink(Path::new(&backend.install_dir));
        if unsupported || classpath_missing || install_dir_missing {
            let reason = if unsupported {
                format!(
                    "Remove unsupported {} backend state from install metadata.",
                    backend.name
                )
            } else {
                format!(
                    "Remove backend state whose runtime files are missing at {}.",
                    backend.runtime_libs_dir
                )
            };
            push_repair_action(
                result,
                "remove-stale-backend-state",
                Path::new(&backend.install_dir),
                &reason,
                Some("Reinstall or refresh the Linux headless tarball.".to_string()),
            );
            if args.apply {
                let install_dir = Path::new(&backend.install_dir);
                backup_existing_path(install_dir, backup_root, result)?;
                remove_existing_path(install_dir)?;
            }
            install_changed = true;
        } else {
            surviving_backends.push(backend);
        }
    }
    install.backends = surviving_backends;

    let surviving_backend_components = install
        .backends
        .iter()
        .map(|backend| format!("backend:{}", backend.name))
        .collect::<BTreeSet<_>>();
    let original_components = install.components.clone();
    install.components.retain(|component| {
        !component.starts_with("backend:") || surviving_backend_components.contains(component)
    });
    for component in original_components {
        if !install.components.contains(&component) {
            push_repair_action(
                result,
                "remove-stale-component-state",
                Path::new(&component),
                "Remove install metadata for a backend component that is no longer present.",
                Some("Reinstall or refresh the Linux headless tarball.".to_string()),
            );
            install_changed = true;
        }
    }

    let original_managed_paths = std::mem::take(&mut install.managed_paths);
    for managed_path_value in original_managed_paths {
        let managed = managed_install_path(&global_config.paths.install_root, &managed_path_value);
        if !path_exists_or_symlink(&managed) {
            push_repair_action(
                result,
                "prune-missing-managed-path",
                &managed,
                "Remove a missing managed path from install metadata.",
                None,
            );
            install_changed = true;
            continue;
        }
        install.managed_paths.push(managed_path_value);
    }

    let mut seen_repos = BTreeSet::new();
    let mut deduped_repos = vec![];
    for repo in install.repos {
        let normalized = config::normalize(PathBuf::from(&repo.path));
        let normalized_value = normalized.display().to_string();
        if seen_repos.insert(normalized_value.clone()) {
            deduped_repos.push(self_mgmt::ManagedRepo {
                path: normalized_value,
                copilot_package_version: repo.copilot_package_version,
                resources: repo.resources,
            });
        } else {
            push_repair_action(
                result,
                "dedupe-managed-repo",
                &normalized,
                "Remove duplicate managed repo install metadata.",
                None,
            );
            install_changed = true;
        }
    }
    install.repos = deduped_repos;

    if install_changed {
        install.version = cli::version().to_string();
        install.installed_at = current_timestamp();
        install.platform = format!("{}-{}", env::consts::OS, env::consts::ARCH);
    }
    if args.apply && install_changed {
        backup_config_once(result, backup_root, config_backed_up)?;
        if install.components.is_empty()
            && install.backends.is_empty()
            && install.managed_paths.is_empty()
            && install.shell_rc_patches.is_empty()
            && install.repos.is_empty()
        {
            self_mgmt::remove_global_install_state()?;
        } else {
            self_mgmt::write_install_state(&install)?;
        }
    }

    Ok(())
}

fn repair_legacy_macos_install_identity(
    args: &InstallRepairArgs,
    install: &self_mgmt::InstallState,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<()> {
    let manifest_path = manifest::default_install_manifest_path();
    let shim = PathBuf::from(&install.entrypoints.shim);
    let active_binary = PathBuf::from(&install.entrypoints.active_binary);
    let managed_shim = manifest::is_managed_shim_for(&shim, &active_binary);
    if !managed_shim && path_exists_or_symlink(&shim) {
        result.warnings.push(format!(
            "Legacy manifest shim {} is not a confirmed Kast-managed shim; preserving the legacy shim and manifest unchanged",
            shim.display()
        ));
        return Ok(());
    }
    push_repair_action(
        result,
        "retire-legacy-macos-install",
        &manifest_path,
        "Back up and retire legacy managed-local install identity; the macOS Homebrew receipt remains authoritative.",
        Some("kast repair --for machine --apply".to_string()),
    );
    if !args.apply {
        return Ok(());
    }
    if managed_shim {
        retire_inactive_legacy_path(&shim, backup_root, result)?;
    }
    retire_inactive_legacy_path(&manifest_path, backup_root, result)
}

fn retire_inactive_legacy_path(
    path: &Path,
    backup_root: &Path,
    result: &mut InstallRepairResult,
) -> Result<()> {
    if !path_parent_is_writable(path) {
        result.warnings.push(format!(
            "Cannot remove {}; leaving inactive legacy path unchanged because its parent directory is not writable by the current user",
            path.display()
        ));
        return Ok(());
    }
    backup_existing_path(path, backup_root, result)?;
    remove_existing_path(path)
}

#[cfg(unix)]
pub(crate) fn path_parent_is_writable(path: &Path) -> bool {
    use std::os::unix::fs::MetadataExt;

    let Some(parent) = path.parent() else {
        return false;
    };
    let Ok(metadata) = fs::metadata(parent) else {
        return false;
    };
    let current_uid = [Path::new("/usr/bin/id"), Path::new("/bin/id")]
        .into_iter()
        .find(|candidate| candidate.is_file())
        .and_then(|id| ProcessCommand::new(id).arg("-u").output().ok())
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .and_then(|output| output.trim().parse::<u32>().ok());
    let mode = metadata.mode();
    current_uid.is_some_and(|uid| metadata.uid() == uid && mode & 0o200 != 0)
        || mode & 0o002 != 0
}

#[cfg(not(unix))]
pub(crate) fn path_parent_is_writable(path: &Path) -> bool {
    path.parent()
        .and_then(|parent| fs::metadata(parent).ok())
        .is_some_and(|metadata| !metadata.permissions().readonly())
}

fn repair_install_shell_sources(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<()> {
    let shell_dir = config::kast_config_home().join("shell");
    if !shell_dir.is_dir() {
        return Ok(());
    }
    let mut entries = fs::read_dir(&shell_dir)?.collect::<std::result::Result<Vec<_>, _>>()?;
    entries.sort_by_key(|entry| entry.path());
    for entry in entries {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(shell) = path
            .extension()
            .and_then(|extension| extension.to_str())
            .and_then(|extension| match extension {
                "bash" => Some(ShellKind::Bash),
                "zsh" => Some(ShellKind::Zsh),
                _ => None,
            })
        else {
            continue;
        };
        let Some(command_name) = path
            .file_stem()
            .and_then(|stem| stem.to_str())
            .filter(|name| !name.trim().is_empty())
        else {
            continue;
        };
        validate_shell_command_name(command_name)?;
        let content = fs::read_to_string(&path)?;
        if !content.contains("Managed by `kast machine shell`")
            && !content.contains("Managed by `kast install shell`")
        {
            continue;
        }
        let Some(bin_dir) = resolve_command_bin_dir(command_name)? else {
            result.warnings.push(format!(
                "Could not resolve `{command_name}` on PATH; leaving managed shell source {} unchanged",
                path.display()
            ));
            continue;
        };
        if content.contains(&format!(
            "_kast_bin_dir={}",
            shell_quote(&bin_dir.display().to_string())
        )) {
            continue;
        }
        push_repair_action(
            result,
            "refresh-shell-source",
            &path,
            &format!(
                "Back up and rewrite managed shell integration for `{command_name}` to use {}.",
                bin_dir.display()
            ),
            Some("kast repair --apply".to_string()),
        );
        if args.apply {
            backup_existing_path(&path, backup_root, result)?;
            fs::write(
                &path,
                shell_source_content(shell, command_name, &bin_dir, &config::kast_config_home()),
            )?;
        }
    }
    Ok(())
}

fn repair_macos_homebrew_cli_authority(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<bool> {
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (args, result, backup_root);
        Ok(true)
    }
    #[cfg(target_os = "macos")]
    {
        let receipt_path = default_macos_homebrew_receipt_path();
        with_macos_homebrew_receipt_lock(&receipt_path, || {
            let replacement = match resolve_macos_homebrew_authority() {
                MacosHomebrewAuthorityResolution::Absent
                | MacosHomebrewAuthorityResolution::Active(_) => return Ok(true),
                MacosHomebrewAuthorityResolution::Recoverable(replacement) => replacement,
                MacosHomebrewAuthorityResolution::Blocked(_)
                    if args.reset_homebrew_receipt =>
                {
                    let replacement = discover_running_homebrew_receipt()?.ok_or_else(|| {
                        CliError::new(
                            "MACOS_HOMEBREW_RECEIPT_RESET_UNAVAILABLE",
                            format!(
                                "Homebrew receipt at {} is blocked, and reset requires the exact running Cellar/kast formula executable; the receipt was preserved unchanged",
                                receipt_path.display(),
                            ),
                        )
                    })?;
                    let reset_command = format!(
                        "{} repair --for machine --reset-homebrew-receipt --apply",
                        shell_quote(&replacement.cli.binary.display().to_string())
                    );
                    result.apply_command = reset_command.clone();
                    push_repair_action(
                        result,
                        "reset-homebrew-cli-receipt",
                        &receipt_path,
                        "Preserve the exact blocked receipt bytes, then atomically establish CLI authority from the running Cellar/kast executable.",
                        Some(reset_command),
                    );
                    if args.apply {
                        backup_existing_path(&receipt_path, backup_root, result)?;
                        write_macos_homebrew_receipt_at(&receipt_path, &replacement)?;
                        let written = read_macos_homebrew_receipt_at(&receipt_path)?;
                        validate_running_macos_homebrew_receipt(&receipt_path, written)?;
                    }
                    return Ok(false);
                }
                MacosHomebrewAuthorityResolution::Blocked(mut error) => {
                    if let Ok(Some(replacement)) = discover_running_homebrew_receipt() {
                        let reset_command = format!(
                            "{} repair --for machine --reset-homebrew-receipt --apply",
                            shell_quote(&replacement.cli.binary.display().to_string())
                        );
                        error.message = format!(
                            "Homebrew CLI authority is blocked by receipt state at {} and was preserved unchanged; explicitly reset it with: {reset_command}",
                            receipt_path.display(),
                        );
                        error
                            .details
                            .insert("resetCommand".to_string(), reset_command);
                    }
                    return Err(error);
                }
            };
            let repair_command = format!(
                "{} repair --for machine --apply",
                shell_quote(&replacement.cli.binary.display().to_string())
            );
            result.apply_command = repair_command.clone();
            push_repair_action(
                result,
                "establish-homebrew-cli-receipt",
                &receipt_path,
                "Back up recognized legacy or stale receipt state and write the current CLI-only Homebrew authority receipt.",
                Some(repair_command),
            );
            if args.apply {
                backup_existing_path(&receipt_path, backup_root, result)?;
                write_macos_homebrew_receipt_at(&receipt_path, &replacement)?;
                let written = read_macos_homebrew_receipt_at(&receipt_path)?;
                validate_running_macos_homebrew_receipt(&receipt_path, written)?;
            }
            Ok(true)
        })
    }
}

fn repair_recognized_legacy_idea_plugin_links(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<()> {
    let receipt = match resolve_macos_homebrew_authority() {
        MacosHomebrewAuthorityResolution::Active(receipt) => receipt,
        MacosHomebrewAuthorityResolution::Recoverable(receipt) if !args.apply => receipt,
        MacosHomebrewAuthorityResolution::Absent => return Ok(()),
        MacosHomebrewAuthorityResolution::Blocked(_) if !args.apply => return Ok(()),
        MacosHomebrewAuthorityResolution::Recoverable(_) => {
            return Err(CliError::new(
                "MACOS_HOMEBREW_RECEIPT_RECOVERY_INCOMPLETE",
                "Homebrew receipt recovery was applied but strict authority resolution is not active.",
            ));
        }
        MacosHomebrewAuthorityResolution::Blocked(error) => return Err(error),
    };
    let jetbrains_config_root = args
        .jetbrains_config_root
        .clone()
        .map(config::normalize)
        .unwrap_or_else(|| {
            config::home_dir().join("Library/Application Support/JetBrains")
        });
    let owned_links = owned_legacy_idea_plugin_links(
        &jetbrains_config_root,
        &receipt.cli.formula_prefix,
    )?;
    for owned in &owned_links {
        push_repair_action(
            result,
            "remove-legacy-idea-plugin-link",
            &owned.path,
            &format!(
                "Back up and remove the recognized legacy Homebrew plugin link to {}; JetBrains now owns plugin installation and updates.",
                owned.target.display()
            ),
            Some("Quit the IDE, run `install.sh install` to install the release-matched plugin through JetBrains, then reopen this exact project.".to_string()),
        );
    }
    if args.apply && !owned_links.is_empty() {
        apply_owned_legacy_idea_plugin_cleanup(
            owned_links,
            backup_root,
            result,
            require_jetbrains_ides_closed_for_legacy_cleanup,
        )?;
    }
    Ok(())
}

fn apply_owned_legacy_idea_plugin_cleanup(
    owned_links: Vec<OwnedLegacySymlink>,
    backup_root: &Path,
    result: &mut InstallRepairResult,
    require_ides_closed: impl FnOnce() -> Result<()>,
) -> Result<()> {
    if owned_links.is_empty() {
        return Ok(());
    }
    require_ides_closed()?;
    fs::create_dir_all(backup_root)?;
    for owned in owned_links {
        let backup_path = backup_root.join(format!(
            "{:03}-{}",
            result.backups.len() + 1,
            backup_label(&owned.path),
        ));
        if path_exists_or_symlink(&backup_path) {
            return Err(CliError::new(
                "LEGACY_IDEA_PLUGIN_CLEANUP_CONFLICT",
                format!(
                    "Refusing to replace existing cleanup backup {}; leave plugin state unchanged and remove that conflict manually if appropriate",
                    backup_path.display(),
                ),
            ));
        }
        fs::rename(&owned.path, &backup_path)?;
        let remains_exact_owned_link = fs::symlink_metadata(&backup_path)
            .is_ok_and(|metadata| metadata.file_type().is_symlink())
            && fs::read_link(&backup_path).is_ok_and(|target| target == owned.target);
        if !remains_exact_owned_link {
            if !path_exists_or_symlink(&owned.path) {
                fs::rename(&backup_path, &owned.path)?;
            }
            return Err(CliError::new(
                "LEGACY_IDEA_PLUGIN_CLEANUP_STATE_CHANGED",
                format!(
                    "Refusing to remove {} because it changed after cleanup selection; the observed state was preserved",
                    owned.path.display(),
                ),
            ));
        }
        result.backups.push(backup_path.display().to_string());
    }
    Ok(())
}

fn push_repair_action(
    result: &mut InstallRepairResult,
    kind: &str,
    target: &Path,
    message: &str,
    command: Option<String>,
) {
    result.actions.push(InstallRepairAction {
        kind: kind.to_string(),
        target: target.display().to_string(),
        status: if result.applied { "applied" } else { "planned" }.to_string(),
        message: message.to_string(),
        command,
    });
}

fn backup_config_once(
    result: &mut InstallRepairResult,
    backup_root: &Path,
    config_backed_up: &mut bool,
) -> Result<()> {
    if *config_backed_up {
        return Ok(());
    }
    backup_existing_path(&config::global_config_path(), backup_root, result)?;
    *config_backed_up = true;
    Ok(())
}

fn backup_existing_path(
    path: &Path,
    backup_root: &Path,
    result: &mut InstallRepairResult,
) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    fs::create_dir_all(backup_root)?;
    let backup_path = backup_root.join(format!(
        "{:03}-{}",
        result.backups.len() + 1,
        backup_label(path)
    ));
    if metadata.file_type().is_symlink() {
        let target = fs::read_link(path)?;
        fs::write(
            &backup_path,
            format!("symlink {}\n", target.display()).as_bytes(),
        )?;
    } else if metadata.is_file() {
        fs::copy(path, &backup_path)?;
    } else if metadata.is_dir() {
        copy_path_recursive(path, &backup_path)?;
    }
    result.backups.push(backup_path.display().to_string());
    Ok(())
}

fn copy_path_recursive(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        let link_target = fs::read_link(source)?;
        fs::write(
            target,
            format!("symlink {}\n", link_target.display()).as_bytes(),
        )?;
    } else if metadata.is_file() {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(source, target)?;
    } else if metadata.is_dir() {
        fs::create_dir_all(target)?;
        for entry in fs::read_dir(source)? {
            let entry = entry?;
            copy_path_recursive(&entry.path(), &target.join(entry.file_name()))?;
        }
    }
    Ok(())
}

fn backup_label(path: &Path) -> String {
    let raw = path
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("path");
    let sanitized = raw
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '_'
            }
        })
        .collect::<String>();
    if sanitized.is_empty() {
        "path".to_string()
    } else {
        sanitized
    }
}

fn remove_existing_path(path: &Path) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    if metadata.file_type().is_symlink() || metadata.is_file() {
        fs::remove_file(path)?;
    } else if metadata.is_dir() {
        fs::remove_dir_all(path)?;
    }
    Ok(())
}

fn path_exists_or_symlink(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok()
}

fn managed_install_path(install_root: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        install_root.join(path)
    }
}

fn read_toml_document(path: &Path) -> Result<toml::Table> {
    if !path.is_file() {
        return Ok(toml::Table::new());
    }
    Ok(fs::read_to_string(path)?.parse::<toml::Table>()?)
}

fn resolve_command_bin_dir(command_name: &str) -> Result<Option<PathBuf>> {
    let current_exe = env::current_exe()?;
    if current_exe
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == command_name)
    {
        return Ok(current_exe.parent().map(Path::to_path_buf));
    }
    let output = ProcessCommand::new("which").arg(command_name).output();
    let Ok(output) = output else {
        return Ok(None);
    };
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let command_path = stdout
        .lines()
        .next()
        .map(str::trim)
        .filter(|line| !line.is_empty());
    Ok(command_path
        .map(PathBuf::from)
        .and_then(|path| path.parent().map(Path::to_path_buf)))
}

fn backup_timestamp() -> String {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs().to_string())
        .unwrap_or_else(|_| "0".to_string())
}
