fn install_idea_plugin_into_jetbrains_profiles(
    args: IdeaPluginInstallArgs,
    homebrew: HomebrewContext,
    cask_token: String,
    mut warnings: Vec<String>,
    reporter: &mut dyn InstallReporter,
) -> Result<InstallIdeaPluginResult> {
    let jetbrains_config_root = args
        .jetbrains_config_root
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    reporter.idea_plugin_step_started(&format!(
        "Finding JetBrains profiles under {}",
        jetbrains_config_root.display()
    ))?;
    let plugin_directories = match jetbrains_plugin_dirs(&jetbrains_config_root) {
        Ok(plugin_directories) if !plugin_directories.is_empty() => {
            reporter.idea_plugin_step_finished(&format!(
                "Found {}",
                jetbrains_profile_count_label(plugin_directories.len())
            ))?;
            plugin_directories
        }
        Ok(_) => {
            reporter.idea_plugin_step_failed(&format!(
                "No JetBrains profiles found under {}",
                jetbrains_config_root.display()
            ))?;
            Vec::new()
        }
        Err(error) => {
            reporter.idea_plugin_step_failed("Could not read JetBrains profiles")?;
            return Err(error);
        }
    };
    if plugin_directories.is_empty() {
        let mut error = CliError::new(
            "JETBRAINS_CONFIG_NOT_FOUND",
            format!(
                "No JetBrains IDE profile directories were found under {}",
                jetbrains_config_root.display()
            ),
        );
        error.details.insert(
            "expectedRoot".to_string(),
            jetbrains_config_root.display().to_string(),
        );
        return Err(error);
    }

    let cask_name = cask_name(&cask_token);
    let installed_cask_version = run_reported_step(
        reporter,
        &format!("Checking installed Homebrew cask {cask_name}"),
        |installed: &Option<String>| match installed {
            Some(version) => format!("Found installed Homebrew cask version {version}"),
            None => "Homebrew cask is not installed yet".to_string(),
        },
        "Could not check installed Homebrew cask",
        || homebrew_cask_version(&cask_name),
    )?;
    let download_plan = run_reported_step(
        reporter,
        &format!("Reading Homebrew cask metadata for {cask_token}"),
        |plan: &IdeaPluginDownloadPlan| {
            format!(
                "Resolved plugin version {} and cache {}",
                plan.plugin_version,
                plan.download_cache.display()
            )
        },
        "Could not read Homebrew cask metadata",
        || homebrew_cask_download_plan(&cask_token, &plugin_directories),
    )?;
    if download_plan.plugin_version != cli::version() {
        let mut error = CliError::new(
            "HOMEBREW_PLUGIN_VERSION_MISMATCH",
            format!(
                "Homebrew Kast plugin version {} does not match running CLI version {}.",
                download_plan.plugin_version,
                cli::version()
            ),
        );
        error.details.insert(
            "cliVersion".to_string(),
            cli::version().to_string(),
        );
        error.details.insert(
            "pluginVersion".to_string(),
            download_plan.plugin_version.clone(),
        );
        return Err(error);
    }
    let planned_plugin_target = homebrew
        .brew_prefix
        .join("Caskroom")
        .join(&cask_name)
        .join(&download_plan.plugin_version)
        .join("backend-idea");
    run_reported_step(
        reporter,
        "Checking existing JetBrains profile plugin paths",
        |_| "No unmanaged JetBrains plugin paths found".to_string(),
        "Found unmanaged JetBrains plugin paths",
        || reject_unmanaged_homebrew_plugin_paths(&planned_plugin_target, &plugin_directories),
    )?;
    let brew_action = HomebrewCaskInstallAction::for_versions(
        installed_cask_version.as_deref(),
        &download_plan.plugin_version,
        args.force,
    );
    let brew_args = brew_action.brew_args(&cask_token, args.force);
    reporter.idea_plugin_plan(&download_plan)?;
    let downloaded_bytes = if args.dry_run {
        reporter.idea_plugin_step_started(
            "Dry run requested; skipping fetch, Homebrew install, and profile links",
        )?;
        reporter.idea_plugin_step_finished("Dry run complete; no files changed")?;
        file_size(&download_plan.download_cache).unwrap_or(0)
    } else if brew_action == HomebrewCaskInstallAction::None {
        file_size(&download_plan.download_cache).unwrap_or(0)
    } else {
        prefetch_homebrew_cask(
            &download_plan.cask_token,
            args.force,
            &download_plan.download_cache,
            reporter,
        )?
    };
    if !args.dry_run {
        if brew_action == HomebrewCaskInstallAction::None {
            reporter.idea_plugin_step_started("Matching Homebrew cask is already installed")?;
            reporter.idea_plugin_step_finished(brew_action.completion_label())?;
        } else {
            reporter.idea_plugin_step_started(&format!(
                "Running Homebrew {} ({})",
                brew_action.as_brew_arg(),
                brew_command_display(&brew_args)
            ))?;
            let output = match run_brew_with_jetbrains_root(&brew_args, &jetbrains_config_root) {
                Ok(output) => output,
                Err(error) => {
                    reporter.idea_plugin_step_failed("Could not start Homebrew")?;
                    return Err(error);
                }
            };
            if !output.status.success() {
                reporter.idea_plugin_step_failed(brew_action.failure_label())?;
                return Err(command_error(
                    "HOMEBREW_CASK_INSTALL_FAILED",
                    "Homebrew failed to install the Kast IDEA plugin cask",
                    &brew_args,
                    &output,
                ));
            }
            reporter.idea_plugin_step_finished(brew_action.completion_label())?;
        }
        reporter.idea_plugin_step_started(&format!(
            "Linking Kast plugin into {}",
            jetbrains_profile_count_label(plugin_directories.len())
        ))?;
        match ensure_homebrew_plugin_profile_links(
            &homebrew,
            &cask_name,
            &plugin_directories,
            &mut warnings,
        ) {
            Ok(()) => reporter.idea_plugin_step_finished(&format!(
                "Linked Kast plugin into {}",
                jetbrains_profile_count_label(plugin_directories.len())
            ))?,
            Err(error) => {
                reporter.idea_plugin_step_failed("Could not link JetBrains profile plugins")?;
                return Err(error);
            }
        }
    }
    let developer_defaults = if args.dry_run {
        self_mgmt::configure_developer_machine_defaults(true)?
    } else {
        reporter.idea_plugin_step_started("Configuring developer-machine IDEA defaults")?;
        let defaults = self_mgmt::configure_developer_machine_defaults(false)?;
        reporter.idea_plugin_step_finished(&format!(
            "Configured IDEA plugin backend defaults at {}",
            defaults.config_path
        ))?;
        defaults
    };
    let homebrew_receipt = if args.dry_run {
        default_macos_homebrew_receipt_path()
    } else {
        write_macos_homebrew_receipt(&MacosHomebrewInstallReceipt::new(
            homebrew.cli_path.clone(),
            homebrew.formula_prefix.clone(),
            cli::version().to_string(),
            cask_token.clone(),
            download_plan.plugin_version.clone(),
        ))?
    };

    Ok(InstallIdeaPluginResult {
        cask_token,
        plugin_version: download_plan.plugin_version,
        download_cache: download_plan.download_cache.display().to_string(),
        downloaded_bytes,
        brew_action: brew_action.as_brew_arg().to_string(),
        brew_command: if brew_args.is_empty() {
            Vec::new()
        } else {
            std::iter::once("brew".to_string())
                .chain(brew_args)
                .collect()
        },
        brew_prefix: homebrew.brew_prefix.display().to_string(),
        formula_prefix: homebrew.formula_prefix.display().to_string(),
        cli_path: homebrew.cli_path.display().to_string(),
        homebrew_receipt: homebrew_receipt.display().to_string(),
        jetbrains_config_root: Some(jetbrains_config_root.display().to_string()),
        plugin_directories: plugin_directories
            .into_iter()
            .map(|path| path.display().to_string())
            .collect(),
        dry_run: args.dry_run,
        developer_defaults,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn ensure_homebrew_plugin_profile_links(
    homebrew: &HomebrewContext,
    cask_name: &str,
    plugin_directories: &[PathBuf],
    warnings: &mut Vec<String>,
) -> Result<()> {
    let Some(expected_plugin_target) =
        expected_homebrew_plugin_target_for_cask(cask_name, &homebrew.brew_prefix, warnings)?
    else {
        return Err(CliError::new(
            "HOMEBREW_PLUGIN_TARGET_MISSING",
            format!(
                "Homebrew cask {cask_name} did not expose an installed Kast plugin target after convergence."
            ),
        ));
    };
    reject_unmanaged_homebrew_plugin_paths(&expected_plugin_target, plugin_directories)?;
    let mut active_profiles = 0_usize;
    for plugin_dir in plugin_directories {
        let plugin_link = plugin_dir.join("kast");
        let outcome =
            ensure_homebrew_plugin_profile_link(&expected_plugin_target, &plugin_link, warnings)?;
        if outcome.is_active() {
            active_profiles += 1;
        }
    }
    if active_profiles != plugin_directories.len() {
        let mut error = CliError::new(
            "JETBRAINS_PLUGIN_LINK_CONFLICT",
            "Kast could not prove every JetBrains profile uses the Homebrew-managed plugin; no Homebrew authority receipt was written.",
        );
        error.details.insert(
            "profileCount".to_string(),
            plugin_directories.len().to_string(),
        );
        error.details.insert(
            "activeProfileCount".to_string(),
            active_profiles.to_string(),
        );
        return Err(error);
    }
    Ok(())
}

fn reject_unmanaged_homebrew_plugin_paths(
    expected_plugin_target: &Path,
    plugin_directories: &[PathBuf],
) -> Result<()> {
    let conflicting_profiles = plugin_directories
        .iter()
        .map(|plugin_dir| plugin_dir.join("kast"))
        .filter_map(|plugin_link| {
            match classify_homebrew_plugin_profile_path(expected_plugin_target, &plugin_link) {
                HomebrewPluginProfilePath::Unmanaged { current_target } => {
                    Some(current_target.map_or_else(
                        || plugin_link.display().to_string(),
                        |target| format!("{} -> {}", plugin_link.display(), target.display()),
                    ))
                }
                HomebrewPluginProfilePath::Missing
                | HomebrewPluginProfilePath::Active
                | HomebrewPluginProfilePath::ManagedStale { .. } => None,
            }
        })
        .collect::<Vec<_>>();
    if !conflicting_profiles.is_empty() {
        let mut error = CliError::new(
            "JETBRAINS_PLUGIN_LINK_CONFLICT",
            "Kast preserved unmanaged JetBrains plugin paths and did not certify Homebrew authority; remove or relocate those paths explicitly, then rerun the installer.",
        );
        error.details.insert(
            "conflictingProfiles".to_string(),
            conflicting_profiles.join("\n"),
        );
        return Err(error);
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum HomebrewPluginProfileLinkOutcome {
    AlreadyActive,
    Linked,
    PreservedConflict,
}

impl HomebrewPluginProfileLinkOutcome {
    fn is_active(self) -> bool {
        matches!(self, Self::AlreadyActive | Self::Linked)
    }
}

fn ensure_homebrew_plugin_profile_link(
    expected_plugin_target: &Path,
    plugin_link: &Path,
    warnings: &mut Vec<String>,
) -> Result<HomebrewPluginProfileLinkOutcome> {
    match classify_homebrew_plugin_profile_path(expected_plugin_target, plugin_link) {
        HomebrewPluginProfilePath::Active => {
            return Ok(HomebrewPluginProfileLinkOutcome::AlreadyActive);
        }
        HomebrewPluginProfilePath::Missing => {}
        HomebrewPluginProfilePath::ManagedStale { .. } => {
            remove_existing_path(plugin_link)?;
        }
        HomebrewPluginProfilePath::Unmanaged { current_target } => {
            let existing_path = current_target.map_or_else(
                || plugin_link.display().to_string(),
                |target| format!("{} -> {}", plugin_link.display(), target.display()),
            );
            warnings.push(format!(
                "Not replacing unmanaged JetBrains plugin path {existing_path}"
            ));
            return Ok(HomebrewPluginProfileLinkOutcome::PreservedConflict);
        }
    }
    if let Some(parent) = plugin_link.parent() {
        fs::create_dir_all(parent)?;
    }
    create_plugin_link(expected_plugin_target, plugin_link, warnings)?;
    if fs::read_link(plugin_link)
        .ok()
        .is_some_and(|target| target == expected_plugin_target)
    {
        Ok(HomebrewPluginProfileLinkOutcome::Linked)
    } else {
        Err(CliError::new(
            "JETBRAINS_PLUGIN_LINK_FAILED",
            format!(
                "Kast could not verify the JetBrains plugin link {} -> {} after creating it.",
                plugin_link.display(),
                expected_plugin_target.display()
            ),
        ))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum HomebrewPluginProfilePath {
    Missing,
    Active,
    ManagedStale { current_target: PathBuf },
    Unmanaged { current_target: Option<PathBuf> },
}

fn classify_homebrew_plugin_profile_path(
    expected_plugin_target: &Path,
    plugin_link: &Path,
) -> HomebrewPluginProfilePath {
    if !path_exists_or_symlink(plugin_link) {
        return HomebrewPluginProfilePath::Missing;
    }
    let Ok(current_target) = fs::read_link(plugin_link) else {
        return HomebrewPluginProfilePath::Unmanaged {
            current_target: None,
        };
    };
    if current_target == expected_plugin_target {
        return HomebrewPluginProfilePath::Active;
    }
    let managed_cask_root = expected_plugin_target
        .parent()
        .and_then(Path::parent);
    if managed_cask_root
        .is_some_and(|root| current_target.parent().and_then(Path::parent) == Some(root))
        && current_target.file_name().is_some_and(|name| name == "backend-idea")
    {
        HomebrewPluginProfilePath::ManagedStale { current_target }
    } else {
        HomebrewPluginProfilePath::Unmanaged {
            current_target: Some(current_target),
        }
    }
}

fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    format!("unix:{seconds}")
}
