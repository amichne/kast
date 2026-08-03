fn execute_agent_add_file_preview(
    runtime: AgentRuntimeArgs,
    identity_request: Value,
    target_path: String,
    content_file: PathBuf,
) -> AgentEnvelope {
    let proposed_content = match read_exact_addition_content(&content_file, true) {
        Ok(content) => content,
        Err(error) => {
            return error_envelope(
                "agent/add-file".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    let raw_request = json_rpc_request(
        "raw/plan-add-file",
        json!({
            "targetPath": target_path,
            "proposedContent": proposed_content,
        }),
    );
    execute_agent_addition_preview(
        "agent/add-file",
        "ADD_FILE",
        identity_request,
        runtime,
        raw_request,
        |result| {
            let preview: AgentAddFilePlanResult = serde_json::from_value(result)
                .map_err(|error| format!("The add-file preview violated its closed typed contract: {error}"))?;
            preview.validate_for(&target_path, &proposed_content)?;
            serde_json::to_value(preview)
                .map_err(|error| format!("The add-file preview could not be projected: {error}"))
        },
    )
}

fn execute_agent_add_declaration_preview(
    runtime: AgentRuntimeArgs,
    identity_request: Value,
    target_path: String,
    content_file: PathBuf,
) -> AgentEnvelope {
    let proposed_declaration = match read_exact_addition_content(&content_file, false) {
        Ok(content) => content,
        Err(error) => {
            return error_envelope(
                "agent/add-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    let preimage = match read_exact_addition_target(&target_path) {
        Ok(preimage) => preimage,
        Err(error) => {
            return error_envelope(
                "agent/add-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    let expected_current_sha256 = exact_file_sha256(&preimage);
    let raw_request = json_rpc_request(
        "raw/plan-add-declaration",
        json!({
            "targetPath": target_path,
            "expectedCurrentSha256": expected_current_sha256,
            "proposedDeclaration": proposed_declaration,
        }),
    );
    execute_agent_addition_preview(
        "agent/add-declaration",
        "ADD_DECLARATION",
        identity_request,
        runtime,
        raw_request,
        |result| {
            let preview: AgentAddDeclarationPlanResult = serde_json::from_value(result)
                .map_err(|error| format!("The add-declaration preview violated its closed typed contract: {error}"))?;
            preview.validate_for(
                &target_path,
                &expected_current_sha256,
                &proposed_declaration,
            )?;
            serde_json::to_value(preview).map_err(|error| {
                format!("The add-declaration preview could not be projected: {error}")
            })
        },
    )
}

fn execute_agent_addition_preview<F>(
    agent_method: &'static str,
    plan_kind: &'static str,
    identity_request: Value,
    runtime: AgentRuntimeArgs,
    raw_request: Value,
    parse_preview: F,
) -> AgentEnvelope
where
    F: FnOnce(Value) -> std::result::Result<Value, String>,
{
    let raw_method = raw_request["method"]
        .as_str()
        .expect("addition raw request has one method")
        .to_string();
    let session = match runtime::raw_rpc_session(runtime.workspace_root.clone()) {
        Ok(session) => session,
        Err(error) => {
            return error_envelope(
                agent_method.to_string(),
                Some(identity_request),
                AgentError::from_cli_error(error),
            );
        }
    };
    let preview_envelope = execute_request_with_session(
        AgentRequest {
            method: raw_method,
            request: raw_request,
            runtime,
            full_response: true,
            operation: AgentOperation::MutationPreview,
        },
        Some(&session),
    );
    if !preview_envelope.ok {
        return error_envelope(
            agent_method.to_string(),
            Some(identity_request),
            preview_envelope.error.unwrap_or_else(|| {
                agent_error(
                    "INVALID_ADDITION_PREVIEW",
                    "The backend addition preview failed without a typed error.",
                )
            }),
        );
    }
    let Some(raw_preview) = preview_envelope.result else {
        return error_envelope(
            agent_method.to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_ADDITION_PREVIEW",
                "The backend addition preview returned no typed result.",
            ),
        );
    };
    let preview = match parse_preview(raw_preview) {
        Ok(preview) => preview,
        Err(message) => {
            return error_envelope(
                agent_method.to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_ADDITION_PREVIEW",
                    format!("The backend addition preview was invalid: {message}"),
                ),
            );
        }
    };
    result_envelope(
        agent_method.to_string(),
        json!({
            "type": "KAST_AGENT_ADDITION_PLAN",
            "ok": true,
            "mutates": true,
            "applyRequired": true,
            "planKind": plan_kind,
            "request": identity_request,
            "preview": preview,
            "schemaVersion": SCHEMA_VERSION,
        }),
    )
}

fn read_exact_addition_content(
    path: &Path,
    allow_final_lf: bool,
) -> std::result::Result<String, AgentError> {
    let metadata = std::fs::symlink_metadata(path).map_err(|error| {
        agent_error(
            "INVALID_ADDITION_CONTENT",
            format!(
                "The proposed addition content {} could not be inspected: {error}",
                path.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(agent_error(
            "INVALID_ADDITION_CONTENT",
            "The proposed addition content must be a regular non-symlink file.",
        ));
    }
    let bytes = std::fs::read(path).map_err(|error| {
        agent_error(
            "INVALID_ADDITION_CONTENT",
            format!(
                "The proposed addition content {} could not be read: {error}",
                path.display()
            ),
        )
    })?;
    let proposed = String::from_utf8(bytes).map_err(|_| {
        agent_error(
            "INVALID_ADDITION_CONTENT",
            "The proposed addition content must be exact UTF-8 text.",
        )
    })?;
    validate_strict_addition_text(&proposed, allow_final_lf)
        .map_err(|message| agent_error("INVALID_ADDITION_CONTENT", message))?;
    Ok(proposed)
}

fn read_exact_addition_target(path: &str) -> std::result::Result<Vec<u8>, AgentError> {
    let path = Path::new(path);
    let metadata = std::fs::symlink_metadata(path).map_err(|error| {
        agent_error(
            "INVALID_ADDITION_TARGET",
            format!(
                "The add-declaration target {} could not be inspected: {error}",
                path.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(agent_error(
            "INVALID_ADDITION_TARGET",
            "The add-declaration target must be one regular non-symlink file.",
        ));
    }
    std::fs::read(path).map_err(|error| {
        agent_error(
            "INVALID_ADDITION_TARGET",
            format!(
                "The add-declaration target {} could not be read exactly: {error}",
                path.display()
            ),
        )
    })
}
