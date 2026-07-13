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

fn raw_rpc_session_with_auto_start(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
    auto_start: bool,
) -> Result<RawRpcSession> {
    let workspace_root = workspace_root(requested_workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    let response_timeout = Duration::from_millis(
        config
            .server
            .request_timeout_millis
            .saturating_add(5_000)
            .max(1),
    );
    let ensure = workspace_ensure(RuntimeArgs {
        workspace_root: Some(workspace_root),
        backend_name,
        idea_home: None,
        wait_timeout_ms: 60_000,
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
    Ok(RawRpcSession {
        socket_path: PathBuf::from(&ensure.selected.descriptor.socket_path),
        response_timeout,
    })
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
