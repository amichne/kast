#[allow(clippy::too_many_arguments)]
fn execute_agent_replacement_preview_for_target(
    runtime: AgentRuntimeArgs,
    identity_request: Value,
    session: &runtime::RawRpcSession,
    expected_target: AgentExactReplacementSymbolIdentity,
    proposed_declaration: String,
    evidence_name: &'static str,
    evidence: Value,
    help: &'static str,
) -> AgentEnvelope {
    let preview_request = json_rpc_request(
        "raw/plan-replacement",
        json!({
            "target": &expected_target,
            "proposedDeclaration": &proposed_declaration,
        }),
    );
    let preview_envelope = execute_request_with_session(
        AgentRequest {
            method: "raw/plan-replacement".to_string(),
            request: preview_request,
            runtime,
            full_response: true,
            operation: AgentOperation::MutationPreview,
        },
        Some(session),
    );
    if !preview_envelope.ok {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            preview_envelope.error.unwrap_or_else(|| {
                agent_error(
                    "INVALID_REPLACEMENT_PREVIEW",
                    "The backend replacement preview failed without a typed error.",
                )
            }),
        );
    }
    let preview = match preview_envelope.result {
        Some(result) => match serde_json::from_value::<AgentReplacementPlanResult>(result) {
            Ok(preview) => preview,
            Err(error) => {
                return error_envelope(
                    "agent/replace-declaration".to_string(),
                    Some(identity_request),
                    agent_error(
                        "INVALID_REPLACEMENT_PREVIEW",
                        format!(
                            "The backend replacement preview violated its closed typed contract: {error}"
                        ),
                    ),
                );
            }
        },
        None => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_REPLACEMENT_PREVIEW",
                    "The backend replacement preview returned no result.",
                ),
            );
        }
    };
    if let Err(message) = preview.validate_for_target(&expected_target, &proposed_declaration) {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error("INVALID_REPLACEMENT_PREVIEW", message),
        );
    }
    let mut result = json!({
        "type": "KAST_AGENT_REPLACEMENT_PLAN",
        "ok": true,
        "mutates": true,
        "applyRequired": true,
        "request": identity_request,
        "preview": preview,
        "help": [help],
        "schemaVersion": SCHEMA_VERSION,
    });
    result
        .as_object_mut()
        .expect("replacement plan must be a JSON object")
        .insert(evidence_name.to_string(), evidence);
    result_envelope("agent/replace-declaration".to_string(), result)
}

fn normalize_exact_replacement_target(
    runtime: &AgentRuntimeArgs,
    mut target: AgentExactReplacementSymbolIdentity,
) -> std::result::Result<AgentExactReplacementSymbolIdentity, AgentError> {
    let normalizer = AgentFilePathNormalizer::from_runtime(runtime)?;
    target.declaration_file = normalizer
        .normalize(&target.declaration_file)?
        .into_rpc_path();
    if !target.is_valid_replacement_target() {
        return Err(agent_error(
            "AGENT_REPLACEMENT_IDENTITY_ANCHOR_UNAVAILABLE",
            "The compiler-resolved replacement target was not a normalized function or property identity.",
        ));
    }
    Ok(target)
}

fn read_exact_replacement_content(path: &Path) -> std::result::Result<String, AgentError> {
    let metadata = std::fs::symlink_metadata(path).map_err(|error| {
        agent_error(
            "INVALID_REPLACEMENT_CONTENT",
            format!(
                "The proposed replacement content {} could not be inspected: {error}",
                path.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(agent_error(
            "INVALID_REPLACEMENT_CONTENT",
            "The proposed replacement content must be a regular non-symlink file.",
        ));
    }
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt as _;
        options.custom_flags(libc::O_NOFOLLOW);
    }
    let bytes = {
        use std::io::Read as _;

        let mut file = options.open(path).map_err(|error| {
            agent_error(
                "INVALID_REPLACEMENT_CONTENT",
                format!(
                    "The proposed replacement content {} could not be opened safely: {error}",
                    path.display()
                ),
            )
        })?;
        let opened = file.metadata().map_err(|error| {
            agent_error(
                "INVALID_REPLACEMENT_CONTENT",
                format!(
                    "The proposed replacement content {} could not be verified: {error}",
                    path.display()
                ),
            )
        })?;
        if !opened.is_file() {
            return Err(agent_error(
                "INVALID_REPLACEMENT_CONTENT",
                "The proposed replacement content changed while it was opened.",
            ));
        }
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).map_err(|error| {
            agent_error(
                "INVALID_REPLACEMENT_CONTENT",
                format!(
                    "The proposed replacement content {} could not be read: {error}",
                    path.display()
                ),
            )
        })?;
        bytes
    };
    let proposed = String::from_utf8(bytes).map_err(|_| {
        agent_error(
            "INVALID_REPLACEMENT_CONTENT",
            "The proposed replacement content must be exact UTF-8 text.",
        )
    })?;
    if proposed.trim().is_empty() {
        return Err(agent_error(
            "INVALID_REPLACEMENT_CONTENT",
            "The proposed replacement content must contain one Kotlin declaration.",
        ));
    }
    Ok(proposed)
}
