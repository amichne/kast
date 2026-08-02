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
    admission: AdmittedHeadlessRuntime,
    socket_path: PathBuf,
    response_timeout: Duration,
}

pub fn raw_rpc_session(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<RawRpcSession> {
    raw_rpc_session_from_route(semantic_workspace_route(
        requested_workspace_root,
        backend_name,
    )?)
}

pub fn raw_rpc_session_ready(
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<RawRpcSession> {
    raw_rpc_session_from_route(semantic_workspace_route_ready(
        requested_workspace_root,
        backend_name,
    )?)
}

fn raw_rpc_session_from_route(route: SemanticWorkspaceRoute) -> Result<RawRpcSession> {
    match route {
        SemanticWorkspaceRoute::Admitted(admission) => {
            Ok(raw_rpc_session_for_admission(*admission))
        }
        SemanticWorkspaceRoute::Rejected(rejection) => {
            let mut error = CliError::new(rejection.code, rejection.message);
            error.details.insert(
                "semanticWorkspace".to_string(),
                serde_json::to_string(&rejection.evidence).unwrap_or_default(),
            );
            Err(error)
        }
    }
}

pub(crate) fn raw_rpc_session_for_admission(
    admission: AdmittedHeadlessRuntime,
) -> RawRpcSession {
    let advertised_timeout_millis = admission
        .candidate()
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.pointer("/limits/requestTimeoutMillis"))
        .and_then(Value::as_u64)
        .unwrap_or(0);
    RawRpcSession {
        socket_path: PathBuf::from(&admission.candidate().descriptor.socket_path),
        response_timeout: Duration::from_millis(
            admission
                .config()
                .server
                .request_timeout_millis
                .max(advertised_timeout_millis)
                .saturating_add(5_000)
                .max(1),
        ),
        admission,
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
    session.admission.validate_current()?;
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
