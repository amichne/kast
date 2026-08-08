pub(crate) fn run_external_refresh(
    workspace_root: PathBuf,
    failure_ids: Vec<agent::public_protocol::ExternalFailureId>,
    output_format: OutputFormat,
) -> Result<i32> {
    let transport_ids = failure_ids
        .iter()
        .map(|failure_id| failure_id.as_str().to_string())
        .collect::<Vec<_>>();
    let response = raw_workspace_refresh(&workspace_root, &[], &transport_ids)?;
    if let Some((code, message)) = rpc_failure(&response) {
        return print_failure(
            agent::public_protocol::OperationId::WorkspaceExternalize,
            code,
            message,
            output_format,
        );
    }
    let outcomes = response
        .get("result")
        .and_then(|result| result.get("externalFailureOutcomes"))
        .and_then(Value::as_array)
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "External graph-boundary refresh returned no outcomes.",
            )
        })?;
    if outcomes.len() != failure_ids.len() {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "External graph-boundary refresh returned the wrong number of outcomes.",
        ));
    }
    let external = outcomes
        .iter()
        .zip(&failure_ids)
        .map(|(outcome, requested_id)| {
            let failure_id = outcome
                .get("failureId")
                .and_then(Value::as_str)
                .ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "External graph-boundary refresh returned an outcome without a failure id.",
                    )
                })?;
            if failure_id != requested_id.as_str() {
                return Err(CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "External graph-boundary refresh returned outcomes out of order.",
                ));
            }
            let status = outcome
                .get("status")
                .and_then(Value::as_str)
                .filter(|status| {
                    matches!(*status, "EXTERNALIZED" | "ALREADY_EXTERNAL" | "NOT_FOUND")
                })
                .ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "External graph-boundary refresh returned an unknown status.",
                    )
                })?;
            Ok(json!({"failureId": failure_id, "status": status}))
        })
        .collect::<Result<Vec<_>>>()?;
    if external
        .iter()
        .any(|outcome| outcome["status"] == "NOT_FOUND")
    {
        return print_actionable_failure(
            agent::public_protocol::OperationId::WorkspaceExternalize,
            "EXTERNAL_FAILURE_NOT_FOUND",
            "One or more external failure IDs no longer identify current content.",
            "Run `kast workspace refresh --file <path>` for the affected file, then externalize the new failure ID.",
            output_format,
        );
    }
    print_public_value(
        agent::public_protocol::OperationId::WorkspaceExternalize,
        agent::public_protocol::OperationStatus::Complete,
        &json!({"external": external}),
        output_format,
    )
}

fn raw_workspace_refresh(
    workspace_root: &Path,
    file_paths: &[String],
    external_failure_ids: &[String],
) -> Result<Value> {
    let request = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "raw/workspace-refresh",
        "params": {
            "filePaths": file_paths,
            "externalFailureIds": external_failure_ids,
        }
    });
    let raw = runtime::raw_request_passthrough(
        serde_json::to_string(&request)?,
        Some(workspace_root.to_path_buf()),
    )?;
    serde_json::from_str(&raw).map_err(CliError::from)
}

fn rpc_failure(response: &Value) -> Option<(&str, &str)> {
    let error = response.get("error")?;
    let code = error
        .get("data")
        .and_then(|data| data.get("code"))
        .or_else(|| error.get("code"))
        .and_then(Value::as_str)
        .unwrap_or("RPC_ERROR");
    let message = error
        .get("data")
        .and_then(|data| data.get("message"))
        .or_else(|| error.get("message"))
        .and_then(Value::as_str)
        .unwrap_or("Workspace refresh failed.");
    Some((code, message))
}

fn print_failure(
    operation: agent::public_protocol::OperationId,
    code: &str,
    message: &str,
    output_format: OutputFormat,
) -> Result<i32> {
    print_actionable_failure(
        operation,
        code,
        message,
        "Run `kast --help` for valid commands and arguments.",
        output_format,
    )
}

fn string_array_field(result: &Value, field: &str) -> Result<Vec<String>> {
    required_field(result, field)?
        .as_array()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                format!("The typed operation returned a non-array `{field}` field."),
            )
        })?
        .iter()
        .map(|value| {
            value.as_str().map(str::to_string).ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    format!("The typed operation returned a non-string `{field}` entry."),
                )
            })
        })
        .collect()
}
