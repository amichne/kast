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
    let cask_installed = run_reported_step(
        reporter,
        &format!("Checking installed Homebrew cask {cask_name}"),
        |installed| {
            if *installed {
                "Existing Homebrew cask found; reinstall will refresh it".to_string()
            } else {
                "Homebrew cask is not installed yet".to_string()
            }
        },
        "Could not check installed Homebrew cask",
        || homebrew_cask_installed(&cask_name),
    )?;
    let brew_action = HomebrewCaskInstallAction::for_installed_cask(cask_installed);
    let brew_args = brew_action.brew_args(&cask_token, args.force);
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
    reporter.idea_plugin_plan(&download_plan)?;
    let downloaded_bytes = if args.dry_run {
        reporter.idea_plugin_step_started(
            "Dry run requested; skipping fetch, Homebrew install, and profile links",
        )?;
        reporter.idea_plugin_step_finished("Dry run complete; no files changed")?;
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

    Ok(InstallIdeaPluginResult {
        cask_token,
        plugin_version: download_plan.plugin_version,
        download_cache: download_plan.download_cache.display().to_string(),
        downloaded_bytes,
        brew_action: brew_action.as_brew_arg().to_string(),
        brew_command: std::iter::once("brew".to_string())
            .chain(brew_args)
            .collect(),
        brew_prefix: homebrew.brew_prefix.display().to_string(),
        formula_prefix: homebrew.formula_prefix.display().to_string(),
        cli_path: homebrew.cli_path.display().to_string(),
        jetbrains_config_root: Some(jetbrains_config_root.display().to_string()),
        plugin_directories: plugin_directories
            .into_iter()
            .map(|path| path.display().to_string())
            .collect(),
        dry_run: args.dry_run,
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
        return Ok(());
    };
    for plugin_dir in plugin_directories {
        let plugin_link = plugin_dir.join("kast");
        ensure_homebrew_plugin_profile_link(&expected_plugin_target, &plugin_link, warnings)?;
    }
    Ok(())
}

fn ensure_homebrew_plugin_profile_link(
    expected_plugin_target: &Path,
    plugin_link: &Path,
    warnings: &mut Vec<String>,
) -> Result<()> {
    if fs::read_link(plugin_link)
        .ok()
        .is_some_and(|target| target == expected_plugin_target)
    {
        return Ok(());
    }
    if path_exists_or_symlink(plugin_link) {
        let Some(current_target) = fs::read_link(plugin_link).ok() else {
            warnings.push(format!(
                "Not replacing existing JetBrains plugin path {}; run `kast ready --fix` for backed-up repair",
                plugin_link.display()
            ));
            return Ok(());
        };
        if !current_target
            .display()
            .to_string()
            .contains("/Caskroom/kast-plugin/")
            && !current_target
                .display()
                .to_string()
                .contains("/kast-plugin/")
        {
            warnings.push(format!(
                "Not replacing existing JetBrains plugin link {} -> {}; run `kast ready --fix` for backed-up repair",
                plugin_link.display(),
                current_target.display()
            ));
            return Ok(());
        }
        remove_existing_path(plugin_link)?;
    }
    if let Some(parent) = plugin_link.parent() {
        fs::create_dir_all(parent)?;
    }
    create_plugin_link(expected_plugin_target, plugin_link, warnings)?;
    Ok(())
}

fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    format!("unix:{seconds}")
}
