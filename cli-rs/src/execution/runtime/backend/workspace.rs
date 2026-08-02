include!("workspace/launch_lock.rs");

pub fn workspace_status(mut args: RuntimeArgs) -> Result<WorkspaceStatusResult> {
    args.accept_indexing = Some(true);
    args.no_auto_start = Some(true);
    let admission = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    let candidate = admission.candidate().clone();
    let semantic_graph = candidate_advertises_semantic_graph(&candidate)
        .then(|| crate::repository_intelligence::semantic_graph_readiness(admission.workspace_root()));
    let path_resolution = config::path_resolution_report(
        admission.config(),
        Some(admission.workspace_root()),
        config::PathResolutionMode::Cli,
    )?;
    Ok(WorkspaceStatusResult {
        workspace_root: admission.workspace_root().display().to_string(),
        descriptor_directory: admission.config().paths.descriptor_dir.display().to_string(),
        path_resolution,
        selected: Some(candidate.clone()),
        semantic_graph,
        candidates: vec![candidate],
        schema_version: SCHEMA_VERSION,
    })
}

pub fn workspace_ensure(args: RuntimeArgs) -> Result<WorkspaceEnsureResult> {
    let admission = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    workspace_ensure_result(&admission)
}

fn workspace_ensure_result(admission: &AdmittedHeadlessRuntime) -> Result<WorkspaceEnsureResult> {
    let path_resolution = config::path_resolution_report(
        admission.config(),
        Some(admission.workspace_root()),
        config::PathResolutionMode::Cli,
    )?;
    Ok(WorkspaceEnsureResult {
        workspace_root: admission.workspace_root().display().to_string(),
        descriptor_directory: admission.config().paths.descriptor_dir.display().to_string(),
        path_resolution,
        started: admission.started(),
        log_file: admission.started().then(|| {
            daemon_log_file(admission.config(), admission.workspace_root(), admission.backend())
                .display()
                .to_string()
        }),
        selected: admission.candidate().clone(),
        note: None,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn workspace_stop(mut args: RuntimeArgs) -> Result<DaemonStopResult> {
    args.accept_indexing = Some(true);
    args.no_auto_start = Some(true);
    let admission = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    stop_admitted_runtime(admission)
}

pub fn workspace_restart(mut args: RuntimeArgs) -> Result<WorkspaceRestartResult> {
    args.accept_indexing = Some(true);
    args.no_auto_start = Some(true);
    let admission = admitted_runtime(semantic_workspace_route_for_runtime(args.clone())?)?;
    let workspace_root = admission.workspace_root().to_path_buf();
    let backend_name = admission.backend_name().to_string();
    let stop = stop_admitted_runtime(admission)?;
    args.workspace_root = Some(workspace_root.clone());
    args.backend_name = Some(BackendName::Headless);
    args.no_auto_start = Some(false);
    let restarted = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    let ensure = workspace_ensure_result(&restarted)?;
    Ok(WorkspaceRestartResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name,
        stop,
        ensure,
        schema_version: SCHEMA_VERSION,
    })
}

fn admitted_runtime(route: SemanticWorkspaceRoute) -> Result<AdmittedHeadlessRuntime> {
    match route {
        SemanticWorkspaceRoute::Admitted(admission) => Ok(*admission),
        SemanticWorkspaceRoute::Rejected(rejection) => Err(rejection.into_cli_error()),
    }
}

fn candidate_advertises_semantic_graph(candidate: &RuntimeCandidateStatus) -> bool {
    candidate
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.get("readCapabilities"))
        .and_then(Value::as_array)
        .is_some_and(|capabilities| {
            capabilities
                .iter()
                .any(|capability| capability.as_str() == Some("SEMANTIC_GRAPH"))
        })
}
