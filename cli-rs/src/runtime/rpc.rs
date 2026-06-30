pub fn raw_request_passthrough(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
) -> Result<String> {
    if let Some(response) =
        crate::metrics::try_handle_raw_rpc(&raw_request, requested_workspace_root.clone())?
    {
        return Ok(response);
    }
    if let Some(response) =
        crate::symbol_query::try_handle_raw_rpc(&raw_request, requested_workspace_root.clone())?
    {
        return Ok(response);
    }
    let workspace_root = workspace_root(requested_workspace_root)?;
    let ensure = workspace_ensure(RuntimeArgs {
        workspace_root: Some(workspace_root),
        backend_name,
        idea_home: None,
        wait_timeout_ms: 60_000,
        accept_indexing: Some(true),
        no_auto_start: None,
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
    rpc::raw(
        Path::new(&ensure.selected.descriptor.socket_path),
        &raw_request,
    )
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
