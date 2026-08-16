fn start_indexer_runtime(
    request: &SemanticRuntimeRequest,
) -> std::result::Result<(RuntimeCandidateStatus, bool), SemanticRuntimeRejection> {
    let deadline = RuntimeStartDeadline::after_millis(request.wait_timeout_ms);
    let launch_lock = WorkspaceLaunchLock::acquire_until(
        &request.config,
        &request.workspace_root,
        deadline,
    )
    .map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    if let Ok(candidate) = admitted_candidate(request) {
        return Ok((candidate, false));
    }
    let (spawned, _) = spawn_indexer(request, &launch_lock, deadline)?;
    let mut child = spawned.child;
    if let Some(candidate) = poll_for_spawned_runtime_candidate(
        &mut child,
        u64::try_from(deadline.remaining().as_millis()).unwrap_or(u64::MAX),
        250,
        {
            let started_at = Instant::now();
            move || u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX)
        },
        || admitted_candidate(request).ok(),
        |duration_ms| thread::sleep(Duration::from_millis(duration_ms)),
    )
    .map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })? {
        thread::spawn(move || {
            let _ = child.wait();
        });
        return Ok((candidate, true));
    }
    Err(runtime_cli_rejection(
        &request.workspace_root,
        request.workspace_kind,
        CliError::new(
            "RUNTIME_TIMEOUT",
            format!(
                "Timed out waiting for the indexer for {}.",
                request.workspace_root.display()
            ),
        ),
    ))
}

pub(crate) fn start_indexer_runtime_background(
    request: SemanticRuntimeRequest,
    deadline: RuntimeStartDeadline,
) -> std::result::Result<BackgroundRuntimeStartResult, SemanticRuntimeRejection> {
    let launch_lock = WorkspaceLaunchLock::acquire_until(
        &request.config,
        &request.workspace_root,
        deadline,
    )
    .map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let mut reuse_request = request.clone();
    reuse_request.availability = SemanticRuntimeAvailability::ReuseOnly;
    match admit_indexer_runtime(reuse_request) {
        Ok(admission) => {
            let storage_identity =
                daemon::IndexerStorageIdentity::resolve(admission.workspace_root(), admission.config())
                    .map_err(|error| {
                        runtime_cli_rejection(
                            &request.workspace_root,
                            request.workspace_kind,
                            error,
                        )
                    })?;
            let pid = admission.candidate().descriptor.pid;
            let owner_pid = u32::try_from(pid).map_err(|_| {
                runtime_identity_rejection(&request.workspace_root, request.workspace_kind)
            })?;
            daemon::require_admitted_storage_owner(&storage_identity, owner_pid).map_err(
                |error| {
                    runtime_cli_rejection(
                        &request.workspace_root,
                        request.workspace_kind,
                        error,
                    )
                },
            )?;
            if deadline.is_elapsed() {
                return Err(runtime_cli_rejection(
                    &request.workspace_root,
                    request.workspace_kind,
                    CliError::new(
                        "RUNTIME_START_TIMEOUT",
                        "The background runtime start deadline expired before reuse admission.",
                    ),
                ));
            }
            return Ok(BackgroundRuntimeStartResult {
                workspace_root: admission.workspace_root().display().to_string(),
                storage_root: storage_identity.storage_root().display().to_string(),
                state: BackgroundRuntimeStartState::Reused,
                pid,
                log_file: None,
                schema_version: SCHEMA_VERSION,
            });
        }
        Err(rejection)
            if matches!(rejection.code, "NO_INDEXER_AVAILABLE" | "RUNTIME_NOT_READY") => {}
        Err(rejection) => return Err(rejection),
    }
    let (spawned, log_file) = spawn_indexer(&request, &launch_lock, deadline)?;
    let pid = u64::from(spawned.child.id());
    let storage_root = spawned.storage_identity.storage_root().display().to_string();
    let workspace_root = spawned.storage_identity.workspace_root().display().to_string();
    let mut child = spawned.child;
    thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(BackgroundRuntimeStartResult {
        workspace_root,
        storage_root,
        state: BackgroundRuntimeStartState::Started,
        pid,
        log_file: Some(log_file.display().to_string()),
        schema_version: SCHEMA_VERSION,
    })
}

fn spawn_indexer(
    request: &SemanticRuntimeRequest,
    launch_lock: &WorkspaceLaunchLock,
    deadline: RuntimeStartDeadline,
) -> std::result::Result<(daemon::SpawnedIndexer, PathBuf), SemanticRuntimeRejection> {
    #[cfg(target_os = "macos")]
    let runtime_libs_dir = None;
    #[cfg(not(target_os = "macos"))]
    let runtime_libs_dir = Some(
        request
            .config
            .indexer
            .runtime_libs_dir
            .clone()
            .filter(|path| path.is_dir())
            .ok_or_else(|| {
                indexer_distribution_unavailable_rejection(
                    &request.workspace_root,
                    request.workspace_kind,
                )
            })?,
    );
    let log_file = daemon_log_file(
        &request.config,
        &request.workspace_root,
        BackendName::Indexer,
    );
    let daemon_args = DaemonStartArgs {
        workspace_root: Some(request.workspace_root.clone()),
        runtime_libs_dir,
        ..DaemonStartArgs::from(request.runtime_args.clone())
    };
    let spawned = daemon::spawn_background(daemon_args, &log_file, launch_lock, deadline)
        .map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    debug_assert_eq!(spawned.storage_identity, *launch_lock.storage_identity());
    Ok((spawned, log_file))
}

fn poll_for_spawned_runtime_candidate<T>(
    child: &mut Child,
    wait_timeout_ms: u64,
    poll_interval_ms: u64,
    elapsed_ms: impl FnMut() -> u64,
    candidate: impl FnMut() -> Option<T>,
    pause: impl FnMut(u64),
) -> Result<Option<T>> {
    let admitted = poll_for_runtime_candidate(
        wait_timeout_ms,
        poll_interval_ms,
        elapsed_ms,
        candidate,
        pause,
    );
    if admitted.is_none() {
        stop_spawned_indexer(child)?;
    }
    Ok(admitted)
}

fn stop_spawned_indexer(child: &mut Child) -> Result<()> {
    if child.try_wait()?.is_some() {
        return Ok(());
    }
    child.kill().map_err(|error| {
        CliError::new(
            "RUNTIME_START_CANCELLATION_FAILED",
            format!(
                "Cannot stop timed-out indexer process {}: {error}",
                child.id(),
            ),
        )
    })?;
    child.wait()?;
    Ok(())
}

fn poll_for_runtime_candidate<T>(
    wait_timeout_ms: u64,
    poll_interval_ms: u64,
    mut elapsed_ms: impl FnMut() -> u64,
    mut candidate: impl FnMut() -> Option<T>,
    mut pause: impl FnMut(u64),
) -> Option<T> {
    while elapsed_ms() < wait_timeout_ms {
        if let Some(admitted) = candidate() {
            return Some(admitted);
        }
        pause(poll_interval_ms);
    }
    None
}
