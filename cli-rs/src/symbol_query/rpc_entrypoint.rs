pub(crate) fn try_handle_raw_rpc(
    raw_request: &str,
    workspace_root_arg: Option<PathBuf>,
) -> Result<Option<String>> {
    let request: Value = serde_json::from_str(raw_request)?;
    if request.get("method").and_then(Value::as_str) != Some("symbol/query") {
        return Ok(None);
    }

    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let params = request.get("params").cloned().unwrap_or_else(|| json!({}));
    let workspace_root = params
        .get("workspaceRoot")
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .or(workspace_root_arg);
    let workspace_root = config::resolve_workspace_root(workspace_root)?;
    let response = run_symbol_query(&workspace_root, params, id)?;
    Ok(Some(serde_json::to_string(&response)?))
}

fn run_symbol_query(workspace_root: &Path, params: Value, id: Value) -> Result<Value> {
    let request = match serde_json::from_value::<SymbolQueryRequest>(params) {
        Ok(request) => request,
        Err(error) => {
            return Ok(json_rpc_success(
                id,
                failure_result("", "INVALID_FILTER", error.to_string()),
            ));
        }
    };
    if request.query.trim().is_empty() && request.anchor.is_empty() {
        return Ok(json_rpc_success(
            id,
            failure_result(
                &request.query,
                "QUERY_TOO_BROAD",
                "query may be empty only when an anchor is provided",
            ),
        ));
    }

    let database = match config::workspace_database_path(workspace_root) {
        Ok(path) => path,
        Err(error) => {
            return Ok(json_rpc_success(
                id,
                failure_result(&request.query, "INDEX_UNAVAILABLE", error.message),
            ));
        }
    };
    if !database.is_file() {
        return Ok(json_rpc_success(
            id,
            failure_result(
                &request.query,
                "INDEX_UNAVAILABLE",
                format!("No source-index database exists at {}", database.display()),
            ),
        ));
    }

    let db = match SymbolQueryDatabase::open(workspace_root, &database) {
        Ok(db) => db,
        Err(error) => {
            return Ok(json_rpc_success(
                id,
                failure_result(&request.query, "INDEX_UNAVAILABLE", error.message),
            ));
        }
    };
    match db.query(request) {
        Ok(result) => Ok(json_rpc_success(id, serde_json::to_value(result)?)),
        Err(error) => Ok(json_rpc_success(
            id,
            failure_result("", "INVALID_FILTER", error.message),
        )),
    }
}

fn json_rpc_success(id: Value, result: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "result": result,
        "id": id
    })
}

fn failure_result(query: &str, reason: &str, message: impl Into<String>) -> Value {
    json!({
        "type": "SYMBOL_QUERY_FAILURE",
        "query": query,
        "reason": reason,
        "message": message.into()
    })
}
