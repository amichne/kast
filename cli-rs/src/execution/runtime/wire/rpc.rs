pub fn raw_request_passthrough(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<String> {
    if let Some(response) = try_handle_local_raw_rpc(&raw_request, requested_workspace_root.clone())?
    {
        return Ok(response);
    }
    let session = raw_rpc_session(requested_workspace_root, backend_name)?;
    raw_request_passthrough_in_session(raw_request, None, &session)
}

#[derive(Debug, Clone)]
pub struct RawRpcSession {
    socket_path: PathBuf,
    response_timeout: Duration,
}

pub fn raw_rpc_session(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<RawRpcSession> {
    raw_rpc_session_with_auto_start(requested_workspace_root, backend_name, true)
}

pub fn raw_rpc_session_reuse_only(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<RawRpcSession> {
    raw_rpc_session_with_auto_start(requested_workspace_root, backend_name, false)
}

pub fn raw_rpc_session_ready(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<RawRpcSession> {
    let workspace_root = workspace_root(requested_workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    let preference = runtime_backend_preference(&config, backend_name);
    validate_macos_idea_gradle_workspace(&workspace_root, preference)?;
    validate_macos_workspace_for_preference(&workspace_root, preference)?;
    let inspection = inspect_workspace_with_config(
        &workspace_root,
        &config,
        preference,
        StaleDescriptorPolicy::Preserve,
    )?;
    reject_ambiguous_servable_backends(&inspection.candidates, preference, false)?;
    if let Some(selected) =
        select_servable(&inspection.candidates, preference.backend_filter(), false)
    {
        validate_macos_workspace_after_bootstrap(&workspace_root, &selected)?;
        return Ok(raw_rpc_session_for_candidate(&config, &selected));
    }
    let selected = select_status_candidate(&inspection.candidates, preference.backend_filter());
    let indexing = selected
        .as_ref()
        .and_then(|candidate| candidate.runtime_status.as_ref())
        .is_some_and(|status| status.state == RuntimeState::Indexing || status.indexing);
    Err(if indexing {
        CliError::new(
            "RUNTIME_INDEXING",
            format!(
                "The semantic runtime for {} is still indexing; graph refresh requires READY.",
                workspace_root.display()
            ),
        )
    } else {
        CliError::new(
            "RUNTIME_NOT_READY",
            format!(
                "No READY semantic runtime is available for {}.",
                workspace_root.display()
            ),
        )
    })
}

fn raw_rpc_session_with_auto_start(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
    auto_start: bool,
) -> Result<RawRpcSession> {
    let workspace_root = workspace_root(requested_workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    let ensure = workspace_ensure(RuntimeArgs {
        workspace_root: Some(workspace_root),
        backend_name,
        idea_home: None,
        wait_timeout_ms: crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
        accept_indexing: Some(true),
        no_auto_start: Some(!auto_start),
        socket_path: None,
        module_name: None,
        source_roots: None,
        classpath: None,
        request_timeout_ms: None,
        max_results: None,
        max_concurrent_requests: None,
        profile: false,
        profile_modes: None,
        profile_duration: None,
        profile_otlp_endpoint: None,
    })?;
    Ok(raw_rpc_session_for_candidate(&config, &ensure.selected))
}

fn raw_rpc_session_for_candidate(
    config: &KastConfig,
    selected: &RuntimeCandidateStatus,
) -> RawRpcSession {
    let advertised_timeout_millis = selected
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.pointer("/limits/requestTimeoutMillis"))
        .and_then(Value::as_u64)
        .unwrap_or(0);
    RawRpcSession {
        socket_path: PathBuf::from(&selected.descriptor.socket_path),
        response_timeout: Duration::from_millis(
            config
                .server
                .request_timeout_millis
                .max(advertised_timeout_millis)
                .saturating_add(5_000)
                .max(1),
        ),
    }
}

pub fn raw_request_passthrough_in_session(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    session: &RawRpcSession,
) -> Result<String> {
    if let Some(response) = try_handle_local_raw_rpc(&raw_request, requested_workspace_root)? {
        return Ok(response);
    }
    rpc::raw_wait_for_close(
        Path::new(&session.socket_path),
        &raw_request,
        session.response_timeout,
    )
}

fn try_handle_local_raw_rpc(
    raw_request: &str,
    requested_workspace_root: Option<PathBuf>,
) -> Result<Option<String>> {
    if let Some(response) = crate::repository_intelligence::try_handle_raw_rpc(
        raw_request,
        requested_workspace_root.clone(),
    )? {
        return Ok(Some(response));
    }
    if let Some(response) =
        crate::metrics::try_handle_raw_rpc(raw_request, requested_workspace_root.clone())?
    {
        return Ok(Some(response));
    }
    crate::symbol_query::try_handle_raw_rpc(raw_request, requested_workspace_root)
}

pub fn capabilities(args: RuntimeArgs) -> Result<Value> {
    let ensure = workspace_ensure(args)?;
    ensure.selected.capabilities.ok_or_else(|| {
        CliError::new(
            "CAPABILITIES_UNAVAILABLE",
            "Runtime capabilities are unavailable",
        )
    })
}
