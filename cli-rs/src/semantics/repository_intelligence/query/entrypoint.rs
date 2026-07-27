fn default_file_limit() -> usize {
    DEFAULT_FILE_LIMIT
}

pub(crate) fn try_handle_raw_rpc(
    raw_request: &str,
    workspace_root_arg: Option<PathBuf>,
) -> Result<Option<String>> {
    let request_value: Value = serde_json::from_str(raw_request)?;
    let Some(method) = request_value
        .get("method")
        .and_then(Value::as_str)
        .map(str::to_owned)
    else {
        return Ok(None);
    };
    if !matches!(method.as_str(), "graph/coverage" | "repository/query") {
        return Ok(None);
    }
    let invalid_code = match method.as_str() {
        "graph/coverage" => "INVALID_GRAPH_COVERAGE_REQUEST",
        "repository/query" => "INVALID_REPOSITORY_QUERY",
        _ => unreachable!("method checked above"),
    };
    let request = serde_json::from_value::<RepositoryRpcRequest>(request_value)
        .map_err(|error| CliError::new(invalid_code, error.to_string()))?;
    if request.jsonrpc != "2.0" || request.method != method {
        return Err(CliError::new(
            invalid_code,
            "repository RPC request must use jsonrpc=2.0 and the routed method",
        ));
    }
    let valid_id = match &request.id {
        Value::Null | Value::String(_) => true,
        Value::Number(number) => number.is_i64() || number.is_u64(),
        _ => false,
    };
    if !valid_id {
        return Err(CliError::new(
            invalid_code,
            "repository RPC id must be an integer, string, or null",
        ));
    }
    let result = match method.as_str() {
        "graph/coverage" => {
            let params = serde_json::from_value::<GraphCoverageParams>(request.params)
                .map_err(|error| CliError::new(invalid_code, error.to_string()))?;
            let workspace_root =
                repository_workspace_root(workspace_root_arg, params._workspace_root.as_deref())?;
            graph_coverage(workspace_root.as_path(), params)?
        }
        "repository/query" => {
            let params = serde_json::from_value::<RepositoryQueryParams>(request.params)
                .map_err(|error| CliError::new(invalid_code, error.to_string()))?;
            let workspace_root =
                repository_workspace_root(workspace_root_arg, params._workspace_root.as_deref())?;
            let params = params.validated()?;
            repository_query(&workspace_root, params)?
        }
        _ => unreachable!("method checked above"),
    };
    Ok(Some(serde_json::to_string(&json!({
        "jsonrpc": "2.0",
        "result": result,
        "id": request.id
    }))?))
}
