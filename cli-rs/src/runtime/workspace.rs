pub fn workspace_status(args: RuntimeArgs) -> Result<WorkspaceStatusResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let config = KastConfig::load(&workspace_root)?;
    let path_resolution = config::path_resolution_report(
        &config,
        Some(&workspace_root),
        config::PathResolutionMode::Cli,
    )?;
    let preference = runtime_backend_preference(&config, args.backend_name);
    validate_macos_workspace_for_preference(&workspace_root, preference)?;
    let inspection = inspect_workspace_with_config(
        &workspace_root,
        &config,
        preference,
        StaleDescriptorPolicy::Preserve,
    )?;
    Ok(WorkspaceStatusResult {
        workspace_root: workspace_root.display().to_string(),
        descriptor_directory: inspection.descriptor_directory.display().to_string(),
        path_resolution,
        selected: inspection.selected,
        candidates: inspection.candidates,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn workspace_ensure(args: RuntimeArgs) -> Result<WorkspaceEnsureResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let config = KastConfig::load(&workspace_root)?;
    let path_resolution = config::path_resolution_report(
        &config,
        Some(&workspace_root),
        config::PathResolutionMode::Cli,
    )?;
    let preference = runtime_backend_preference(&config, args.backend_name);
    validate_macos_idea_gradle_workspace(&workspace_root, preference)?;
    if !should_defer_macos_workspace_validation(&workspace_root, preference, &config) {
        validate_macos_workspace_for_preference(&workspace_root, preference)?;
    }
    let stale_descriptor_policy = if args.no_auto_start.unwrap_or(false) {
        StaleDescriptorPolicy::Preserve
    } else {
        StaleDescriptorPolicy::Prune
    };
    let inspection = inspect_workspace_with_config(
        &workspace_root,
        &config,
        preference,
        stale_descriptor_policy,
    )?;
    reject_ambiguous_servable_backends(
        &inspection.candidates,
        preference,
        args.accept_indexing.unwrap_or(false),
    )?;
    if let Some(selected) = select_servable(
        &inspection.candidates,
        preference.backend_filter(),
        args.accept_indexing.unwrap_or(false),
    ) {
        validate_macos_workspace_after_bootstrap(&workspace_root, &selected)?;
        let launch_disposition = reused_project_launch_disposition(&selected);
        return Ok(WorkspaceEnsureResult {
            workspace_root: workspace_root.display().to_string(),
            descriptor_directory: inspection.descriptor_directory.display().to_string(),
            path_resolution,
            started: false,
            launch_disposition,
            log_file: None,
            selected,
            note: None,
            schema_version: SCHEMA_VERSION,
        });
    }

    if args.no_auto_start.unwrap_or(false) {
        return Err(no_backend_error(
            &workspace_root,
            preference.backend_filter(),
        ));
    }

    let idea_launch_ops = SystemIdeaBackendLaunchOps;
    if let Some((selected, launch_disposition)) = maybe_launch_idea_backend(
        &workspace_root,
        &config,
        preference,
        args.accept_indexing.unwrap_or(false),
        &idea_launch_ops,
    )? {
        validate_macos_workspace_after_bootstrap(&workspace_root, &selected)?;
        return Ok(WorkspaceEnsureResult {
            workspace_root: workspace_root.display().to_string(),
            descriptor_directory: inspection.descriptor_directory.display().to_string(),
            path_resolution,
            started: true,
            launch_disposition: Some(launch_disposition),
            log_file: None,
            selected,
            note: Some("Started IDEA with the configured runtime.ideaLaunch command.".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    }

    let Some(launch_backend) = fallback_launch_backend(preference) else {
        return Err(CliError::new(
            "IDEA_NOT_RUNNING",
            format!(
                "No IDEA backend is available for {}. Open the project in IDEA with the Kast plugin installed.",
                workspace_root.display()
            ),
        ));
    };

    if launch_backend == BackendName::Headless {
        if select_servable(&inspection.candidates, Some(launch_backend), true).is_some()
            && let Ok(selected) = wait_for_servable(
                &workspace_root,
                Some(launch_backend),
                args.accept_indexing.unwrap_or(false),
                args.wait_timeout_ms,
            )
        {
            return Ok(WorkspaceEnsureResult {
                workspace_root: workspace_root.display().to_string(),
                descriptor_directory: inspection.descriptor_directory.display().to_string(),
                path_resolution,
                started: false,
                launch_disposition: None,
                log_file: None,
                selected,
                note: Some(
                    "Reused an existing headless runtime after it became ready.".to_string(),
                ),
                schema_version: SCHEMA_VERSION,
            });
        }
        let _ = stop_backend_candidates(
            &workspace_root,
            RuntimeBackendPreference::Fixed(launch_backend),
            true,
            None,
        )?;
    }

    let runtime_libs_dir = match config
        .backends
        .headless
        .runtime_libs_dir
        .clone()
        .filter(|path| path.is_dir())
    {
        Some(path) => path,
        None => return Err(no_backend_error(&workspace_root, Some(launch_backend))),
    };
    let log_file = daemon_log_file(&config, &workspace_root, launch_backend);
    let daemon_args = DaemonStartArgs {
        workspace_root: Some(workspace_root.clone()),
        backend_name: Some(launch_backend),
        runtime_libs_dir: Some(runtime_libs_dir),
        ..DaemonStartArgs::from(args.clone())
    };
    let mut child = daemon::spawn_background(daemon_args, &log_file)?;
    let spawned_at = Instant::now();
    thread::spawn(move || {
        let _ = child.wait();
    });
    let remaining_wait_timeout =
        remaining_runtime_wait_timeout(spawned_at, args.wait_timeout_ms);
    let started = true;
    let note = None;
    let selected = match wait_for_servable(
        &workspace_root,
        Some(launch_backend),
        args.accept_indexing.unwrap_or(false),
        remaining_wait_timeout,
    ) {
        Ok(selected) => selected,
        Err(error) => {
            if launch_backend == BackendName::Headless && started {
                let _ = stop_backend_candidates(
                    &workspace_root,
                    RuntimeBackendPreference::Fixed(launch_backend),
                    true,
                    None,
                );
            }
            return Err(error);
        }
    };
    Ok(WorkspaceEnsureResult {
        workspace_root: workspace_root.display().to_string(),
        descriptor_directory: inspection.descriptor_directory.display().to_string(),
        path_resolution,
        started,
        launch_disposition: None,
        log_file: started.then(|| log_file.display().to_string()),
        selected,
        note,
        schema_version: SCHEMA_VERSION,
    })
}

fn validate_macos_idea_gradle_workspace(
    workspace_root: &Path,
    preference: RuntimeBackendPreference,
) -> Result<()> {
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (workspace_root, preference);
    }
    #[cfg(target_os = "macos")]
    if preference.fixed_backend() == Some(BackendName::Idea)
        && !is_gradle_workspace(workspace_root)
    {
        return Err(CliError::new(
            "SEMANTIC_WORKSPACE_UNSUPPORTED",
            format!(
                "{} is not a supported Kotlin Gradle workspace. Select a workspace containing settings.gradle(.kts) or build.gradle(.kts).",
                workspace_root.display()
            ),
        ));
    }
    Ok(())
}

fn reused_project_launch_disposition(
    selected: &RuntimeCandidateStatus,
) -> Option<LaunchDisposition> {
    (selected.descriptor.backend_name == BackendName::Idea.canonical())
        .then_some(LaunchDisposition::ReusedOpenProject)
}

fn remaining_runtime_wait_timeout(started_at: Instant, total_wait_timeout_ms: u64) -> u64 {
    let elapsed_ms = u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX);
    total_wait_timeout_ms.saturating_sub(elapsed_ms).max(1)
}

pub fn workspace_stop(args: RuntimeArgs) -> Result<DaemonStopResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let config = KastConfig::load(&workspace_root)?;
    let preference = runtime_backend_preference(&config, args.backend_name);
    validate_macos_workspace_for_preference(&workspace_root, preference)?;
    let backend_name = preference.fixed_backend().unwrap_or(BackendName::Headless);
    stop_backend_candidates(
        &workspace_root,
        RuntimeBackendPreference::Fixed(backend_name),
        true,
        Some("runtime/shutdown"),
    )
}

pub fn workspace_restart(args: RuntimeArgs) -> Result<WorkspaceRestartResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let config = KastConfig::load(&workspace_root)?;
    let preference = runtime_backend_preference(&config, args.backend_name);
    validate_macos_workspace_for_preference(&workspace_root, preference)?;
    let backend_name = preference.fixed_backend().unwrap_or(BackendName::Headless);
    if backend_name == BackendName::Idea
        && let Some(result) = restart_idea_backend_candidates(&workspace_root, &config, &args)?
    {
        return Ok(result);
    }
    let mut stop_args = args.clone();
    stop_args.backend_name = Some(backend_name);
    let stop = workspace_stop(stop_args)?;
    let mut ensure_args = args;
    ensure_args.backend_name = Some(backend_name);
    let ensure = workspace_ensure(ensure_args)?;
    Ok(WorkspaceRestartResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: backend_name.canonical().to_string(),
        stop,
        ensure,
        schema_version: SCHEMA_VERSION,
    })
}

fn validate_macos_workspace_for_preference(
    workspace_root: &Path,
    preference: RuntimeBackendPreference,
) -> Result<()> {
    #[cfg(not(target_os = "macos"))]
    {
        let _ = preference;
        self_mgmt::validate_macos_plugin_workspace(workspace_root)
    }
    #[cfg(target_os = "macos")]
    if preference.fixed_backend() == Some(BackendName::Headless) {
        return Err(CliError::new(
            "HEADLESS_LOCAL_UNSUPPORTED",
            "Kast does not run headless IntelliJ JVMs on macOS developer machines. Open this exact root in IntelliJ IDEA or Android Studio and use the IDEA backend.",
        ));
    }
    #[cfg(target_os = "macos")]
    self_mgmt::validate_macos_plugin_workspace(workspace_root).map_err(|error| {
        if preference.fixed_backend() == Some(BackendName::Idea) {
            let mut update = CliError::new(
                "IDEA_PLUGIN_UPDATE_REQUIRED",
                format!(
                    "The Kast IDEA plugin is missing or stale for {}. Run Kast setup, restart the selected IntelliJ IDEA 2026.2 application if setup requests it, and retry. {}",
                    workspace_root.display(),
                    error.message,
                ),
            );
            update.details.insert("cause".to_string(), error.code.to_string());
            return update;
        }
        error
    })
}

fn should_defer_macos_workspace_validation(
    workspace_root: &Path,
    preference: RuntimeBackendPreference,
    config: &KastConfig,
) -> bool {
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (workspace_root, preference, config);
        false
    }
    #[cfg(target_os = "macos")]
    {
        preference.fixed_backend() == Some(BackendName::Idea)
            && config.runtime.idea_launch.enabled
            && !workspace_root.join(".kast/setup/workspace.json").is_file()
    }
}

fn validate_macos_workspace_after_bootstrap(
    workspace_root: &Path,
    selected: &RuntimeCandidateStatus,
) -> Result<()> {
    if selected.descriptor.backend_name == BackendName::Idea.canonical() {
        validate_macos_workspace_for_preference(
            workspace_root,
            RuntimeBackendPreference::Fixed(BackendName::Idea),
        )?;
    }
    Ok(())
}
