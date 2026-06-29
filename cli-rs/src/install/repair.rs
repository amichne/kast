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
        apply_command: "kast ready --fix".to_string(),
        actions: vec![],
        backups: vec![],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    };
    let mut config_backed_up = false;

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
    repair_install_copilot_repos(&args, &mut result, &backup_root)?;
    repair_install_shell_sources(&args, &mut result, &backup_root)?;
    repair_install_jetbrains_profiles(&args, &mut result, &backup_root)?;

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
                Some("kast ready --fix".to_string()),
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
    let remove_cli_table = document.contains_key("cli");
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
        || remove_cli_table
        || remove_install_table
        || remove_headless_runtime_paths
    {
        push_repair_action(
            result,
            "remove-install-owned-config",
            &config_path,
            "Remove install-owned TOML keys so install identity and paths resolve only from install.json.",
            None,
        );
    }
    if args.apply
        && (remove_paths_table
            || remove_cli_table
            || remove_install_table
            || remove_headless_runtime_paths)
    {
        backup_config_once(result, backup_root, config_backed_up)?;
        self_mgmt::update_global_config(|document| {
            if remove_paths_table {
                document.remove("paths");
            }
            if remove_cli_table {
                document.remove("cli");
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

fn repair_install_copilot_repos(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    _backup_root: &Path,
) -> Result<()> {
    let Some(install) = self_mgmt::read_global_install_state()? else {
        return Ok(());
    };
    let mut seen = BTreeSet::new();
    for repo in install.repos {
        let repo_root = config::normalize(PathBuf::from(repo.path));
        if !seen.insert(repo_root.display().to_string()) {
            continue;
        }
        let github_dir = repo_root.join(".github");
        let copilot_resource = repo
            .resources
            .iter()
            .find(|resource| resource.kind == ManagedResourceKind::CopilotPackage);
        let needs_refresh = if let Some(resource) = copilot_resource {
            resource.primitive_version != cli::version()
                || !manifest::verify_managed_resource_outputs(resource)?.ok
        } else {
            !repo.copilot_package_version.trim().is_empty()
        };
        if !needs_refresh {
            continue;
        }
        push_repair_action(
            result,
            "refresh-copilot-package",
            &github_dir,
            "Refresh a stale managed Copilot LSP package install from the active binary bundles.",
            Some(format!(
                "kast agent setup copilot --target-dir {} --force",
                shell_quote_path(&github_dir)
            )),
        );
        if args.apply {
            install_copilot(CopilotInstallArgs {
                target_dir: Some(github_dir),
                force: true,
                no_auto_exclude_git: false,
                dry_run: false,
            })?;
        }
    }
    Ok(())
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
            Some("kast ready --fix".to_string()),
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

fn repair_install_jetbrains_profiles(
    args: &InstallRepairArgs,
    result: &mut InstallRepairResult,
    backup_root: &Path,
) -> Result<()> {
    let Some(expected_plugin_target) = expected_homebrew_plugin_target(result)? else {
        return Ok(());
    };
    let jetbrains_config_root = args
        .jetbrains_config_root
        .clone()
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    for plugin_dir in jetbrains_plugin_dirs(&jetbrains_config_root)? {
        let plugin_link = plugin_dir.join("kast");
        if !path_exists_or_symlink(&plugin_link) {
            continue;
        }
        if fs::read_link(&plugin_link)
            .ok()
            .is_some_and(|target| target == expected_plugin_target)
        {
            continue;
        }
        push_repair_action(
            result,
            "refresh-idea-plugin-link",
            &plugin_link,
            &format!(
                "Back up and relink a stale IDEA or Android Studio profile plugin to {}.",
                expected_plugin_target.display()
            ),
            Some("kast machine plugin --force".to_string()),
        );
        if args.apply {
            backup_existing_path(&plugin_link, backup_root, result)?;
            remove_existing_path(&plugin_link)?;
            if let Some(parent) = plugin_link.parent() {
                fs::create_dir_all(parent)?;
            }
            create_plugin_link(&expected_plugin_target, &plugin_link, &mut result.warnings)?;
        }
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

pub(crate) fn kast_idea_plugin_installed() -> Result<bool> {
    let jetbrains_config_root = env::var_os("KAST_JETBRAINS_CONFIG_ROOT")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    kast_idea_plugin_installed_under(&jetbrains_config_root)
}

pub(crate) fn kast_idea_plugin_installed_under(jetbrains_config_root: &Path) -> Result<bool> {
    Ok(jetbrains_plugin_dirs(jetbrains_config_root)?
        .into_iter()
        .any(|plugin_dir| path_exists_or_symlink(&plugin_dir.join("kast"))))
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

fn expected_homebrew_plugin_target(result: &mut InstallRepairResult) -> Result<Option<PathBuf>> {
    expected_homebrew_plugin_target_with_warnings(&mut result.warnings)
}

fn expected_homebrew_plugin_target_with_warnings(
    warnings: &mut Vec<String>,
) -> Result<Option<PathBuf>> {
    let brew_prefix = match homebrew_prefix(&["--prefix"]) {
        Ok(value) => value,
        Err(error) => {
            warnings.push(format!(
                "Could not resolve Homebrew prefix; skipping JetBrains plugin link repair: {}",
                error.message
            ));
            return Ok(None);
        }
    };
    let formula_tap = homebrew_formula_tap().unwrap_or_else(|error| {
        warnings.push(format!(
            "Could not resolve the Homebrew tap for kast; using {DEFAULT_KAST_TAP}: {}",
            error.message
        ));
        DEFAULT_KAST_TAP.to_string()
    });
    let cask_token = format!("{formula_tap}/{KAST_PLUGIN_CASK_NAME}");
    let cask_name = cask_name(&cask_token);
    expected_homebrew_plugin_target_for_cask(&cask_name, &brew_prefix, warnings)
}

fn expected_homebrew_plugin_target_for_cask(
    cask_name: &str,
    brew_prefix: &Path,
    warnings: &mut Vec<String>,
) -> Result<Option<PathBuf>> {
    let Some(version) = homebrew_cask_version(cask_name)? else {
        warnings.push(format!(
            "Homebrew cask {cask_name} is not installed; skipping JetBrains plugin link repair"
        ));
        return Ok(None);
    };
    Ok(Some(
        brew_prefix
            .join("Caskroom")
            .join(cask_name)
            .join(version)
            .join("backend-idea"),
    ))
}

#[cfg(unix)]
fn create_plugin_link(source: &Path, target: &Path, _warnings: &mut Vec<String>) -> Result<()> {
    std::os::unix::fs::symlink(source, target)?;
    Ok(())
}

#[cfg(not(unix))]
fn create_plugin_link(_source: &Path, target: &Path, warnings: &mut Vec<String>) -> Result<()> {
    warnings.push(format!(
        "Cannot create JetBrains plugin symlink on this platform; left {} unchanged",
        target.display()
    ));
    Ok(())
}

fn backup_timestamp() -> String {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs().to_string())
        .unwrap_or_else(|_| "0".to_string())
}
