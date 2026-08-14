fn raw_local_request_passthrough_in_session<C: lifecycle_typestate::PersistedCapability>(
    raw_request: String,
    requested_workspace_root: Option<PathBuf>,
    session: &RawRpcSession<C>,
) -> Result<String> {
    validate_raw_rpc_workspace_root(requested_workspace_root.as_deref(), session)?;
    let read = session.semantic_read()?;
    let response = try_handle_local_raw_rpc(
        &raw_request,
        session.admission.workspace_root(),
        read.published(),
    )?
    .ok_or_else(|| {
        CliError::new(
            "RPC_LOCAL_DISPATCH_INVALID",
            "A local semantic RPC method had no local handler.",
        )
    })?;
    Ok(read.revalidate()?.finish(response))
}

fn try_handle_local_raw_rpc(
    raw_request: &str,
    workspace_root: &Path,
    published: &crate::published_workspace::PublishedWorkspaceDatabase,
) -> Result<Option<String>> {
    if let Some(response) = crate::repository_intelligence::try_handle_raw_rpc(
        raw_request,
        workspace_root,
        published,
    )? {
        return Ok(Some(response));
    }
    if let Some(response) =
        crate::metrics::try_handle_raw_rpc(raw_request, workspace_root, published)?
    {
        return Ok(Some(response));
    }
    crate::symbol_query::try_handle_raw_rpc(raw_request, workspace_root, published)
}

fn is_local_semantic_rpc(raw_request: &str) -> Result<bool> {
    let request: Value = serde_json::from_str(raw_request)?;
    Ok(matches!(
        request.get("method").and_then(Value::as_str),
        Some("graph/coverage" | "repository/query" | "database/metrics" | "symbol/query")
    ))
}
