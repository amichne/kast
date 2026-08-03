pub(crate) fn projected_value(command: AgentCommand) -> Result<Value> {
    serde_json::to_value(agent::execute_projected(command)).map_err(CliError::from)
}

pub(crate) fn print_projected_value(envelope: Value) -> Result<i32> {
    let ok = envelope.get("ok").and_then(Value::as_bool).ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The typed operation returned no success state.",
        )
    })?;
    if !ok {
        let error = envelope.get("error").ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The typed operation failed without an actionable error.",
            )
        })?;
        let code = error
            .get("code")
            .and_then(Value::as_str)
            .unwrap_or("KAST_OPERATION_FAILED");
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("The typed operation failed.");
        output::print_structured(
            &ProjectedError {
                error: code.to_string(),
                message: message.to_string(),
                next: "Run `kast --help` for valid commands and arguments.",
            },
            OutputFormat::Toon,
        )?;
        return Ok(1);
    }
    let result = envelope.get("result").cloned().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The typed operation completed without a result.",
        )
    })?;
    print_direct(&sanitize_agent_result(result, true))
}

fn print_direct(value: &impl Serialize) -> Result<i32> {
    output::print_structured(value, OutputFormat::Toon)?;
    Ok(0)
}

fn sanitize_agent_result(value: Value, root: bool) -> Value {
    match value {
        Value::Object(fields) => {
            let nodes_truncated = fields.get("nextAfterId").map(|next| !next.is_null());
            let next_page = fields
                .get("nextPageToken")
                .filter(|next| !next.is_null())
                .cloned();
            let mut sanitized = fields
                .into_iter()
                .filter_map(|(key, value)| {
                    let protocol_cruft = matches!(
                        key.as_str(),
                        "ok" | "method"
                            | "schemaVersion"
                            | "pageToken"
                            | "nextPageToken"
                            | "afterId"
                            | "nextAfterId"
                    );
                    (!(protocol_cruft || root && key == "type"))
                        .then(|| (key, sanitize_agent_result(value, false)))
                })
                .collect::<serde_json::Map<_, _>>();
            if let Some(truncated) = nodes_truncated {
                sanitized.insert("truncated".to_string(), Value::Bool(truncated));
            }
            if let Some(next_page) = next_page {
                sanitized.insert(
                    "nextPage".to_string(),
                    sanitize_agent_result(next_page, false),
                );
            }
            Value::Object(sanitized)
        }
        Value::Array(items) => Value::Array(
            items
                .into_iter()
                .map(|item| sanitize_agent_result(item, false))
                .collect(),
        ),
        scalar => scalar,
    }
}

pub(crate) fn agent_runtime(workspace_root: PathBuf) -> AgentRuntimeArgs {
    AgentRuntimeArgs {
        workspace_root: Some(workspace_root),
        ..Default::default()
    }
}

fn projected_result(envelope: &Value) -> Result<&Value> {
    envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The typed operation completed without a result.",
        )
    })
}

fn required_field<'a>(result: &'a Value, field: &str) -> Result<&'a Value> {
    result.get(field).ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            format!("The typed operation returned no `{field}` field."),
        )
    })
}

fn diagnostic_cardinality(result: &Value) -> Result<Value> {
    let cardinality = required_field(result, "cardinality")?;
    Ok(json!({
        "totalCount": required_field(cardinality, "totalCount")?,
        "returnedCount": required_field(cardinality, "returnedCount")?,
        "truncated": required_field(cardinality, "truncated")?,
    }))
}
