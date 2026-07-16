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
    let local_authority_active =
        crate::local_development::active_local_development_receipt()?.is_some();
    validate_macos_workspace_for_preference(&workspace_root, preference)?;
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
        return Ok(WorkspaceEnsureResult {
            workspace_root: workspace_root.display().to_string(),
            descriptor_directory: inspection.descriptor_directory.display().to_string(),
            path_resolution,
            started: false,
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
    if let Some(selected) = maybe_launch_idea_backend(
        &workspace_root,
        &config,
        preference,
        args.accept_indexing.unwrap_or(false),
        &idea_launch_ops,
    )? {
        return Ok(WorkspaceEnsureResult {
            workspace_root: workspace_root.display().to_string(),
            descriptor_directory: inspection.descriptor_directory.display().to_string(),
            path_resolution,
            started: true,
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

    if launch_backend == BackendName::Headless && !local_authority_active {
        if select_servable(&inspection.candidates, Some(launch_backend), true).is_some()
            && let Ok(selected) = wait_for_servable(
                &workspace_root,
                Some(launch_backend),
                args.accept_indexing.unwrap_or(false),
                runtime_wait_timeout(
                    args.wait_timeout_ms,
                    launch_backend,
                    local_authority_active,
                    RuntimeWaitPhase::ExistingRuntime,
                ),
            )
        {
            return Ok(WorkspaceEnsureResult {
                workspace_root: workspace_root.display().to_string(),
                descriptor_directory: inspection.descriptor_directory.display().to_string(),
                path_resolution,
                started: false,
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
    let spawned_wait_timeout = runtime_wait_timeout(
        args.wait_timeout_ms,
        launch_backend,
        local_authority_active,
        RuntimeWaitPhase::SpawnedRuntime,
    );
    let launch =
        crate::local_development::with_active_local_runtime_start_lock(|local_authority| {
            if local_authority {
                let locked_inspection = inspect_workspace_with_config(
                    &workspace_root,
                    &config,
                    RuntimeBackendPreference::Fixed(launch_backend),
                    StaleDescriptorPolicy::Prune,
                )?;
                if locked_inspection.candidates.iter().any(|candidate| {
                    candidate.pid_alive
                        && candidate.descriptor.backend_name == launch_backend.canonical()
                }) {
                    return Ok(RuntimeLaunch::ReusedRegistered);
                }
            }

            let mut child = daemon::spawn_background(daemon_args, &log_file)?;
            let spawned_at = Instant::now();
            if local_authority
                && let Err(error) = wait_for_runtime_registration(
                    &inspection.descriptor_directory,
                    &workspace_root,
                    launch_backend,
                    &mut child,
                    remaining_runtime_wait_timeout(spawned_at, spawned_wait_timeout),
                )
            {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error);
            }
            thread::spawn(move || {
                let _ = child.wait();
            });
            Ok(RuntimeLaunch::Spawned { spawned_at })
        })?;
    let (remaining_wait_timeout, started, note) = match launch {
        RuntimeLaunch::ReusedRegistered => (
            runtime_wait_timeout(
                args.wait_timeout_ms,
                launch_backend,
                local_authority_active,
                RuntimeWaitPhase::ExistingRuntime,
            ),
            false,
            Some("Reused a concurrently registered headless runtime.".to_string()),
        ),
        RuntimeLaunch::Spawned { spawned_at } => (
            remaining_runtime_wait_timeout(spawned_at, spawned_wait_timeout),
            true,
            None,
        ),
    };
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
        log_file: started.then(|| log_file.display().to_string()),
        selected,
        note,
        schema_version: SCHEMA_VERSION,
    })
}

fn wait_for_runtime_registration(
    descriptor_directory: &Path,
    workspace_root: &Path,
    backend_name: BackendName,
    child: &mut std::process::Child,
    wait_timeout_ms: u64,
) -> Result<()> {
    let pid = u64::from(child.id());
    let deadline = Instant::now() + Duration::from_millis(wait_timeout_ms);
    loop {
        if let Some(status) = child.try_wait()? {
            return Err(CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "The spawned {} runtime process {pid} exited with {status} before registering for {}.",
                    backend_name.canonical(),
                    workspace_root.display(),
                ),
            ));
        }
        let registered = read_descriptors(descriptor_directory)?
            .into_iter()
            .any(|descriptor| {
                descriptor.pid == pid
                    && descriptor.backend_name == backend_name.canonical()
                    && config::normalize(PathBuf::from(descriptor.workspace_root)) == workspace_root
            });
        if registered {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(CliError::new(
                "RUNTIME_REGISTRATION_TIMEOUT",
                format!(
                    "Timed out waiting for spawned {} runtime process {pid} to register for {}.",
                    backend_name.canonical(),
                    workspace_root.display(),
                ),
            ));
        }
        thread::sleep(Duration::from_millis(50));
    }
}

fn remaining_runtime_wait_timeout(started_at: Instant, total_wait_timeout_ms: u64) -> u64 {
    let elapsed_ms = u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX);
    total_wait_timeout_ms.saturating_sub(elapsed_ms).max(1)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuntimeLaunch {
    ReusedRegistered,
    Spawned { spawned_at: Instant },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuntimeWaitPhase {
    ExistingRuntime,
    SpawnedRuntime,
}

fn runtime_wait_timeout(
    requested_timeout_ms: u64,
    backend_name: BackendName,
    local_authority_active: bool,
    phase: RuntimeWaitPhase,
) -> u64 {
    if local_authority_active
        && backend_name == BackendName::Headless
        && phase == RuntimeWaitPhase::SpawnedRuntime
    {
        requested_timeout_ms.max(300_000)
    } else {
        requested_timeout_ms
    }
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
    if preference.fixed_backend() != Some(BackendName::Headless) {
        self_mgmt::validate_macos_plugin_workspace(workspace_root)?;
    }
    Ok(())
}
