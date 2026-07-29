fn run_external_refresh(workspace_root: PathBuf, failure_ids: Vec<String>) -> Result<i32> {
    let response = raw_workspace_refresh(&workspace_root, &[], &failure_ids)?;
    if let Some((code, message)) = rpc_failure(&response) {
        return print_failure(code, message);
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
            if failure_id != requested_id {
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
        output::print_structured(
            &json!({
                "external": external,
                "next": "Run `kast refresh <path>` for the affected file, then externalize the new failure ID."
            }),
            OutputFormat::Toon,
        )?;
        return Ok(1);
    }
    print_direct(&json!({"external": external}))
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
        None,
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

fn print_failure(code: &str, message: &str) -> Result<i32> {
    print_actionable_failure(
        code,
        message,
        "Run `kast --help` for valid commands and arguments.",
    )
}

fn print_actionable_failure(code: &str, message: &str, next: &str) -> Result<i32> {
    output::print_structured(
        &json!({"error": code, "message": message, "next": next}),
        OutputFormat::Toon,
    )?;
    Ok(1)
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
