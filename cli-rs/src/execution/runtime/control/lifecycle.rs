fn stop_backend_candidates(
    workspace_root: &Path,
    preference: RuntimeBackendPreference,
    allow_reachable_idea_descriptor_delete: bool,
    reachable_idea_lifecycle_method: Option<&str>,
) -> Result<DaemonStopResult> {
    let backend_name = preference.fixed_backend().unwrap_or(BackendName::Headless);
    let inspection = inspect_workspace(
        workspace_root,
        preference,
        StaleDescriptorPolicy::Prune,
    )?;
    let mut actions = vec![];
    let mut warnings = vec![];
    for candidate in inspection.candidates {
        if candidate.descriptor.backend_name != backend_name.canonical() {
            continue;
        }
        actions.push(stop_candidate(
            &inspection.descriptor_directory,
            candidate,
            allow_reachable_idea_descriptor_delete,
            reachable_idea_lifecycle_method,
            &mut warnings,
        )?);
    }
    let stopped_count = actions
        .iter()
        .filter(|action| {
            action.terminated || action.descriptor_deleted || action.lifecycle_accepted
        })
        .count();
    let stopped = stopped_count > 0;
    let descriptor_path = actions
        .iter()
        .find(|action| action.descriptor_deleted || action.terminated || action.lifecycle_accepted)
        .or_else(|| actions.first())
        .map(|action| action.descriptor_path.clone());
    let pid = actions
        .iter()
        .find(|action| action.descriptor_deleted || action.terminated || action.lifecycle_accepted)
        .or_else(|| actions.first())
        .map(|action| action.pid);
    let forced = actions.iter().any(|action| action.forced);
    Ok(DaemonStopResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: backend_name.canonical().to_string(),
        stopped,
        stopped_count,
        descriptor_path,
        pid,
        forced,
        candidates: actions,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn stop_candidate(
    descriptor_directory: &Path,
    candidate: RuntimeCandidateStatus,
    allow_reachable_idea_descriptor_delete: bool,
    reachable_idea_lifecycle_method: Option<&str>,
    warnings: &mut Vec<String>,
) -> Result<RuntimeStopAction> {
    let is_idea = candidate.descriptor.backend_name == BackendName::Idea.canonical();
    let mut lifecycle_accepted = false;
    let mut lifecycle_method = None;
    let mut lifecycle_action = None;
    let mut terminated = false;
    let mut descriptor_deleted = false;
    let mut forced = false;
    let mut skipped_reason = None;

    if is_idea && candidate.reachable {
        if let Some(method) = reachable_idea_lifecycle_method {
            let response = request_runtime_lifecycle(&candidate, method)?;
            lifecycle_accepted = response.accepted;
            lifecycle_method = Some(method.to_string());
            lifecycle_action = Some(response.action);
            if method == "runtime/shutdown" {
                descriptor_deleted = wait_for_descriptor_release(
                    descriptor_directory,
                    &candidate.descriptor,
                    Duration::from_secs(5),
                )?;
                if lifecycle_accepted && !descriptor_deleted {
                    warnings.push(format!(
                        "IDEA accepted {method}, but its descriptor was still present after waiting for shutdown."
                    ));
                }
            }
        } else if !allow_reachable_idea_descriptor_delete {
            let reason = "IDEA-hosted backends run inside the IDE process; close or restart the project in IDEA to stop the live backend.".to_string();
            warnings.push(reason.clone());
            skipped_reason = Some(reason);
        } else {
            delete_descriptor(descriptor_directory, &candidate.descriptor)?;
            descriptor_deleted = true;
        }
    } else {
        if !is_idea && candidate.pid_alive {
            terminate_process(candidate.descriptor.pid, false);
            terminated = true;
            for _ in 0..20 {
                if !is_process_alive(candidate.descriptor.pid) {
                    break;
                }
                thread::sleep(Duration::from_millis(250));
            }
            if is_process_alive(candidate.descriptor.pid) {
                terminate_process(candidate.descriptor.pid, true);
                forced = true;
            }
        }
        delete_descriptor(descriptor_directory, &candidate.descriptor)?;
        descriptor_deleted = true;
    }

    Ok(RuntimeStopAction {
        backend_name: candidate.descriptor.backend_name,
        descriptor_path: candidate.descriptor_path,
        pid: candidate.descriptor.pid,
        pid_alive: candidate.pid_alive,
        reachable: candidate.reachable,
        lifecycle_accepted,
        lifecycle_method,
        lifecycle_action,
        terminated,
        descriptor_deleted,
        forced,
        skipped_reason,
        schema_version: SCHEMA_VERSION,
    })
}

fn restart_idea_backend_candidates(
    workspace_root: &Path,
    config: &KastConfig,
    args: &RuntimeArgs,
) -> Result<Option<WorkspaceRestartResult>> {
    let inspection = inspect_workspace_with_config(
        workspace_root,
        config,
        RuntimeBackendPreference::Fixed(BackendName::Idea),
        StaleDescriptorPolicy::Prune,
    )?;
    if inspection.candidates.is_empty()
        || !inspection
            .candidates
            .iter()
            .any(|candidate| candidate.reachable)
    {
        return Ok(None);
    }

    let mut warnings = vec![];
    let mut actions = vec![];
    for candidate in inspection.candidates {
        actions.push(stop_candidate(
            &inspection.descriptor_directory,
            candidate,
            false,
            Some("runtime/restart"),
            &mut warnings,
        )?);
    }
    let stopped_count = actions
        .iter()
        .filter(|action| {
            action.terminated || action.descriptor_deleted || action.lifecycle_accepted
        })
        .count();
    let descriptor_path = actions
        .iter()
        .find(|action| action.lifecycle_accepted)
        .or_else(|| actions.iter().find(|action| action.descriptor_deleted))
        .or_else(|| actions.first())
        .map(|action| action.descriptor_path.clone());
    let pid = actions
        .iter()
        .find(|action| action.lifecycle_accepted)
        .or_else(|| actions.iter().find(|action| action.descriptor_deleted))
        .or_else(|| actions.first())
        .map(|action| action.pid);
    let stopped = actions
        .iter()
        .any(|action| action.lifecycle_accepted || action.descriptor_deleted || action.terminated);
    let forced = actions.iter().any(|action| action.forced);
    let stop = DaemonStopResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: BackendName::Idea.canonical().to_string(),
        stopped,
        stopped_count,
        descriptor_path,
        pid,
        forced,
        candidates: actions,
        warnings,
        schema_version: SCHEMA_VERSION,
    };
    let selected = wait_for_servable(
        workspace_root,
        Some(BackendName::Idea),
        args.accept_indexing.unwrap_or(false),
        args.wait_timeout_ms,
    )?;
    let path_resolution = config::path_resolution_report(
        config,
        Some(workspace_root),
        config::PathResolutionMode::Cli,
    )?;
    let ensure = WorkspaceEnsureResult {
        workspace_root: workspace_root.display().to_string(),
        descriptor_directory: inspection.descriptor_directory.display().to_string(),
        path_resolution,
        started: false,
        launch_disposition: Some(LaunchDisposition::ReusedOpenProject),
        log_file: None,
        selected,
        note: Some("Requested IDEA backend restart through runtime/restart.".to_string()),
        schema_version: SCHEMA_VERSION,
    };
    Ok(Some(WorkspaceRestartResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: BackendName::Idea.canonical().to_string(),
        stop,
        ensure,
        schema_version: SCHEMA_VERSION,
    }))
}

fn request_runtime_lifecycle(
    candidate: &RuntimeCandidateStatus,
    method: &str,
) -> Result<RuntimeLifecycleResponse> {
    rpc::request_wait_for_close::<RuntimeLifecycleResponse>(
        Path::new(&candidate.descriptor.socket_path),
        method,
        Value::Object(Default::default()),
        Duration::from_secs(5),
    )
}

fn wait_for_descriptor_release(
    descriptor_directory: &Path,
    descriptor: &ServerInstanceDescriptor,
    timeout: Duration,
) -> Result<bool> {
    let expected_descriptor_id = descriptor_id(descriptor);
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let still_registered = read_descriptors(descriptor_directory)?
            .iter()
            .any(|candidate| descriptor_id(candidate) == expected_descriptor_id);
        if !still_registered {
            return Ok(true);
        }
        thread::sleep(Duration::from_millis(100));
    }
    Ok(false)
}
