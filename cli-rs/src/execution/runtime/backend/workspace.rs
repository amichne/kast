include!("workspace/launch_lock.rs");

pub fn workspace_status(mut args: RuntimeArgs) -> Result<WorkspaceStatusResult> {
    args.accept_indexing = Some(true);
    args.no_auto_start = Some(true);
    let admission = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    let candidate = admission.candidate().clone();
    let semantic_graph = candidate_advertises_semantic_graph(&candidate)
        .then(|| crate::repository_intelligence::semantic_graph_readiness_for_admission(&admission));
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

fn workspace_ensure_result(admission: &AdmittedIndexerRuntime) -> Result<WorkspaceEnsureResult> {
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

pub fn workspace_stop(args: RuntimeArgs) -> Result<DaemonStopResult> {
    indexer_authority::stop_workspace_runtime(args)
}

pub fn workspace_restart(mut args: RuntimeArgs) -> Result<WorkspaceRestartResult> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let stop = indexer_authority::stop_workspace_runtime(args.clone())?;
    args.accept_indexing = Some(true);
    args.workspace_root = Some(workspace_root.clone());
    args.no_auto_start = Some(false);
    let restarted = admitted_runtime(semantic_workspace_route_for_runtime(args)?)?;
    let ensure = workspace_ensure_result(&restarted)?;
    Ok(WorkspaceRestartResult {
        workspace_root: workspace_root.display().to_string(),
        backend_name: BackendName::Indexer.canonical().to_string(),
        stop,
        ensure,
        schema_version: SCHEMA_VERSION,
    })
}

fn admitted_runtime(route: SemanticWorkspaceRoute) -> Result<AdmittedIndexerRuntime> {
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
