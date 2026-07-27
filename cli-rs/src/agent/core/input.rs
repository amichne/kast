fn json_rpc_request(method: &str, params: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": 1
    })
}

fn validate_request(method: &str, request: &Value) -> std::result::Result<(), AgentError> {
    let catalog = validate::embedded_catalog().map_err(AgentError::from_cli_error)?;
    let report =
        validate::validate_request(request, &catalog, None).map_err(AgentError::from_cli_error)?;
    if report.ok {
        return Ok(());
    }
    let mut error = agent_error(
        "AGENT_REQUEST_INVALID",
        format!("Request for `{method}` does not match the catalog schema."),
    );
    error.details.insert(
        "validation".to_string(),
        serde_json::to_value(report).unwrap_or(Value::Null),
    );
    Err(error)
}
