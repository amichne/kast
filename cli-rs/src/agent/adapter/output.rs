pub(crate) fn projected_value(command: AgentCommand) -> Result<Value> {
    serde_json::to_value(agent::execute_projected(command)).map_err(CliError::from)
}

enum BackendOutcome {
    Complete(Value),
    Rejected(Box<agent::public_protocol::ProtocolEnvelope>),
}

fn backend_outcome(
    operation: agent::public_protocol::OperationId,
    envelope: Value,
) -> BackendOutcome {
    let Some(ok) = envelope.get("ok").and_then(Value::as_bool) else {
        return BackendOutcome::Rejected(Box::new(
            agent::public_protocol::ProtocolEnvelope::backend_rejected(
                operation,
                "KAST_INVALID_AGENT_RESULT",
                "The typed operation returned no success state.",
            ),
        ));
    };
    if !ok {
        let error = envelope.get("error");
        let code = error
            .and_then(|error| error.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("KAST_OPERATION_FAILED");
        let message = error
            .and_then(|error| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("The typed operation failed.");
        return BackendOutcome::Rejected(Box::new(
            agent::public_protocol::ProtocolEnvelope::backend_rejected(operation, code, message),
        ));
    }
    match envelope.get("result").cloned() {
        Some(result) => BackendOutcome::Complete(result),
        None => BackendOutcome::Rejected(Box::new(
            agent::public_protocol::ProtocolEnvelope::backend_rejected(
                operation,
                "KAST_INVALID_AGENT_RESULT",
                "The typed operation completed without a result.",
            ),
        )),
    }
}

pub(crate) fn print_backend_failure(
    operation: agent::public_protocol::OperationId,
    envelope: Value,
    output_format: OutputFormat,
) -> Result<i32> {
    let envelope = match backend_outcome(operation, envelope) {
        BackendOutcome::Rejected(envelope) => *envelope,
        BackendOutcome::Complete(_) => {
            agent::public_protocol::ProtocolEnvelope::backend_rejected(
                operation,
                "KAST_INVALID_AGENT_RESULT",
                "An expected backend failure returned a success value.",
            )
        }
    };
    print_protocol(envelope, output_format)
}

pub(crate) fn print_public_value(
    operation: agent::public_protocol::OperationId,
    status: agent::public_protocol::OperationStatus,
    value: &impl Serialize,
    output_format: OutputFormat,
) -> Result<i32> {
    let fields = serde_json::to_value(value)?
        .as_object()
        .cloned()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The public operation returned a non-object result.",
            )
        })?;
    print_protocol(
        agent::public_protocol::ProtocolEnvelope::projected(operation, status, fields),
        output_format,
    )
}

fn print_file_list(envelope: Value, output_format: OutputFormat) -> Result<i32> {
    use agent::public_protocol::{OperationId, OperationStatus};

    let result = match backend_outcome(OperationId::FileList, envelope) {
        BackendOutcome::Complete(result) => result,
        BackendOutcome::Rejected(envelope) => return print_protocol(*envelope, output_format),
    };
    let fields = result.as_object().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "File listing returned a non-object result.",
        )
    })?;
    let files = public_file_collection(required_field(&result, "files")?)?;
    let returned = required_field(&result, "returnedCount")?
        .as_u64()
        .and_then(|count| usize::try_from(count).ok())
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "File listing returned invalid cardinality.",
            )
        })?;
    let continuation = fields.get("nextPageToken").and_then(Value::as_str);
    let page = canonical_page(required_field(&result, "cardinality")?, returned, continuation)?;
    let limitations = required_field(&result, "limitations")?.clone();
    let status = if limitations.as_array().is_some_and(Vec::is_empty) {
        OperationStatus::Complete
    } else {
        OperationStatus::Qualified
    };
    let mut public = serde_json::Map::new();
    public.insert("files".to_string(), files);
    public.insert("page".to_string(), page);
    for field in [
        "capabilityEvidence",
        "coverage",
        "limitations",
        "backendPageCoverage",
        "classificationEvidence",
        "normalizedQuery",
        "compositionDigest",
    ] {
        if let Some(value) = fields.get(field) {
            public.insert(field.to_string(), value.clone());
        }
    }
    print_protocol(
        agent::public_protocol::ProtocolEnvelope::projected(
            OperationId::FileList,
            status,
            public,
        ),
        output_format,
    )
}

fn print_diagnostics(
    workspace_root: &Path,
    envelope: Value,
    output_format: OutputFormat,
) -> Result<i32> {
    use agent::public_protocol::{OperationId, OperationStatus};

    let result = match backend_outcome(OperationId::DiagnosticCheck, envelope) {
        BackendOutcome::Complete(result) => result,
        BackendOutcome::Rejected(envelope) => return print_protocol(*envelope, output_format),
    };
    result.as_object().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Diagnostic check returned a non-object result.",
        )
    })?;
    let cardinality = required_field(&result, "cardinality")?;
    let returned = required_field(cardinality, "returnedCount")?
        .as_u64()
        .and_then(|count| usize::try_from(count).ok())
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Diagnostic check returned invalid cardinality.",
            )
        })?;
    let page = canonical_page(cardinality, returned, None)?;
    let mut public = serde_json::Map::new();
    let files = required_field(&result, "filePaths")?
        .as_array()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Diagnostic check returned invalid file paths.",
            )
        })?
        .iter()
        .map(|path| {
            path.as_str()
                .ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "Diagnostic check returned a non-string file path.",
                    )
                })
                .and_then(|path| public_source_path(workspace_root, path))
                .map(Value::String)
        })
        .collect::<Result<Vec<_>>>()?;
    public.insert("files".to_string(), Value::Array(files));
    public.insert(
        "fileHashes".to_string(),
        public_file_hashes(workspace_root, required_field(&result, "fileHashes")?)?,
    );
    public.insert(
        "analysis".to_string(),
        required_field(&result, "analysis")?.clone(),
    );
    public.insert(
        "severityCounts".to_string(),
        required_field(&result, "severityCounts")?.clone(),
    );
    public.insert(
        "diagnostics".to_string(),
        public_diagnostics(workspace_root, required_field(&result, "diagnostics")?)?,
    );
    public.insert("page".to_string(), page);
    let complete = result
        .pointer("/analysis/semanticOutcome")
        .and_then(Value::as_str)
        == Some("COMPLETE")
        && cardinality.get("truncated").and_then(Value::as_bool) == Some(false);
    public.insert(
        "limitations".to_string(),
        if complete {
            Value::Array(Vec::new())
        } else {
            serde_json::json!(["compiler-diagnostics-incomplete"])
        },
    );
    print_protocol(
        agent::public_protocol::ProtocolEnvelope::projected(
            OperationId::DiagnosticCheck,
            if complete {
                OperationStatus::Complete
            } else {
                OperationStatus::Qualified
            },
            public,
        ),
        output_format,
    )
}

fn canonical_page(
    cardinality: &Value,
    returned: usize,
    continuation: Option<&str>,
) -> Result<Value> {
    let cardinality_type = required_field(cardinality, "type")?
        .as_str()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Cardinality returned a non-string type.",
            )
        })?;
    let canonical = match cardinality_type {
        "EXACT" => serde_json::json!({
            "type": "exact",
            "count": required_field(cardinality, "totalCount")?,
        }),
        "KNOWN_MINIMUM" => serde_json::json!({
            "type": "known-minimum",
            "count": required_field(cardinality, "knownMinimumCount")?,
        }),
        _ => {
            return Err(CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Cardinality returned an unknown type.",
            ));
        }
    };
    let mut page = serde_json::json!({
        "cardinality": canonical,
        "returned": returned,
    });
    if let Some(continuation) = continuation {
        page["continuation"] = Value::String(continuation.to_string());
    }
    Ok(page)
}

fn print_protocol(
    envelope: agent::public_protocol::ProtocolEnvelope,
    output_format: OutputFormat,
) -> Result<i32> {
    let exit_code = envelope.exit_code();
    output::print_structured(&envelope, output_format)?;
    Ok(exit_code)
}

pub(crate) fn print_actionable_failure(
    operation: agent::public_protocol::OperationId,
    code: &str,
    message: &str,
    next: &str,
    output_format: OutputFormat,
) -> Result<i32> {
    print_protocol(
        agent::public_protocol::ProtocolEnvelope::actionable_rejected(
            operation, code, message, next,
        ),
        output_format,
    )
}

pub(crate) fn agent_runtime(workspace_root: PathBuf) -> AgentRuntimeArgs {
    AgentRuntimeArgs {
        workspace_root: Some(workspace_root),
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
