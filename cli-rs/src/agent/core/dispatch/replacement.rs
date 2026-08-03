fn execute_agent_replacement_preview(
    args: AgentReplaceDeclarationArgs,
    params: Value,
) -> AgentEnvelope {
    let identity_request = json_rpc_request("symbol/replace-declaration", params);
    let proposed_declaration = match read_exact_replacement_content(&args.content_file) {
        Ok(content) => content,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    match (args.symbol.as_ref(), args.selector_handle.clone()) {
        (Some(_), None) => {
            execute_agent_replacement_symbol_preview(args, identity_request, proposed_declaration)
        }
        (None, Some(selector_handle)) => execute_agent_replacement_handle_preview(
            args,
            identity_request,
            selector_handle,
            proposed_declaration,
        ),
        _ => error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_INPUT",
                "Provide exactly one of --symbol or --selector-handle.",
            ),
        ),
    }
}

fn execute_agent_replacement_symbol_preview(
    args: AgentReplaceDeclarationArgs,
    identity_request: Value,
    proposed_declaration: String,
) -> AgentEnvelope {
    let Some(symbol) = args.symbol.as_ref() else {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_INPUT",
                "A named replacement preview requires --symbol.",
            ),
        );
    };
    let resolve_request = json_rpc_request(
        "symbol/resolve",
        drop_nulls(json!({
            "symbol": symbol,
            "kind": args.kind.map(|kind| kind.canonical()),
            "fileHint": args.file_hint.as_ref(),
            "containingType": args.containing_type.as_ref(),
            "includeDeclarationScope": false,
            "includeDocumentation": false,
            "includeSurroundingMembers": false,
        })),
    );
    let session = match runtime::raw_rpc_session(args.runtime.workspace_root.clone()) {
        Ok(session) => session,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                AgentError::from_cli_error(error),
            );
        }
    };
    let resolution = execute_request_with_session(
        AgentRequest {
            method: "symbol/resolve".to_string(),
            request: resolve_request,
            runtime: args.runtime.clone(),
            full_response: true,
            operation: AgentOperation::ReadOnly,
        },
        Some(&session),
    );
    if !resolution.ok {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            resolution.error.unwrap_or_else(|| {
                agent_error(
                    "INVALID_REPLACEMENT_RESOLUTION",
                    "Replacement target resolution failed without a typed error.",
                )
            }),
        );
    }
    let resolved_value = match resolution.result {
        Some(result) => result,
        None => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_REPLACEMENT_RESOLUTION",
                    "Replacement target resolution returned no result.",
                ),
            );
        }
    };
    let resolved =
        match serde_json::from_value::<AgentCompilerResolveResponse>(resolved_value.clone()) {
            Ok(AgentCompilerResolveResponse::Resolved { symbol, .. }) => symbol,
            Ok(AgentCompilerResolveResponse::NotFound) => {
                return error_envelope(
                    "agent/replace-declaration".to_string(),
                    Some(identity_request),
                    agent_error(
                        "AGENT_REPLACEMENT_TARGET_NOT_FOUND",
                        "The requested replacement target was not found.",
                    ),
                );
            }
            Ok(AgentCompilerResolveResponse::Ambiguous { candidates }) => {
                let mut error = agent_error(
                    "AGENT_REPLACEMENT_TARGET_AMBIGUOUS",
                    "The requested replacement target was ambiguous.",
                );
                error
                    .details
                    .insert("candidates".to_string(), Value::Array(candidates));
                return error_envelope(
                    "agent/replace-declaration".to_string(),
                    Some(identity_request),
                    error,
                );
            }
            Ok(AgentCompilerResolveResponse::OperationalFailure) => {
                return error_envelope(
                    "agent/replace-declaration".to_string(),
                    Some(identity_request),
                    agent_error(
                        "AGENT_REPLACEMENT_RESOLUTION_FAILED",
                        "The compiler could not resolve the requested replacement target.",
                    ),
                );
            }
            Err(error) => {
                return error_envelope(
                    "agent/replace-declaration".to_string(),
                    Some(identity_request),
                    agent_error(
                        "INVALID_REPLACEMENT_RESOLUTION",
                        format!("Replacement target resolution violated its contract: {error}"),
                    ),
                );
            }
        };
    let Some(target) = AgentExactReplacementSymbolIdentity::from_compiler(&resolved) else {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error(
                "AGENT_REPLACEMENT_IDENTITY_ANCHOR_UNAVAILABLE",
                "The compiler-resolved replacement target had no supported exact source identity.",
            ),
        );
    };
    let target = match normalize_exact_replacement_target(&args.runtime, target) {
        Ok(target) => target,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    execute_agent_replacement_preview_for_target(
        args.runtime,
        identity_request,
        &session,
        target,
        proposed_declaration,
        "resolution",
        resolved_value,
        "Run `kast agent replace-declaration --symbol <fq-name> --content-file <file> --apply --idempotency-key <stable-key> --workspace-root <repo>` to submit this verified replacement.",
    )
}

fn execute_agent_replacement_handle_preview(
    args: AgentReplaceDeclarationArgs,
    identity_request: Value,
    selector_handle: AgentSelectorHandle,
    proposed_declaration: String,
) -> AgentEnvelope {
    let selector_identity_request = json_rpc_request(
        "selector/identity",
        json!({
            "selectorHandle": selector_handle,
            "family": "REPLACE_DECLARATION",
        }),
    );
    let session = match runtime::raw_rpc_session(args.runtime.workspace_root.clone()) {
        Ok(session) => session,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                AgentError::from_cli_error(error),
            );
        }
    };
    let response = execute_request_with_session(
        AgentRequest {
            method: "selector/identity".to_string(),
            request: selector_identity_request,
            runtime: args.runtime.clone(),
            full_response: true,
            operation: AgentOperation::ReadOnly,
        },
        Some(&session),
    );
    if !response.ok {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            response.error.unwrap_or_else(|| {
                agent_error(
                    "REPLACEMENT_SELECTOR_IDENTITY_FAILED",
                    "Selector identity authentication failed without a typed error.",
                )
            }),
        );
    }
    let Some(result) = response.result else {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_IDENTITY",
                "Selector identity authentication returned no result.",
            ),
        );
    };
    let parsed = match serde_json::from_value::<AgentSelectorIdentityResponseInput>(result) {
        Ok(parsed) => parsed,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_SELECTOR_IDENTITY",
                    format!("Selector identity violated its closed response contract: {error}"),
                ),
            );
        }
    };
    let mut identity = match parsed {
        AgentSelectorIdentityResponseInput::Available { identity } => identity,
        AgentSelectorIdentityResponseInput::SelectorHandleRejected { reason, recovery }
            if reason.recovery() == recovery =>
        {
            let mut error = agent_error(
                "SELECTOR_HANDLE_REJECTED",
                "The backend rejected the replacement selector handle.",
            );
            error.details.insert("reason".to_string(), json!(reason));
            error
                .details
                .insert("recovery".to_string(), json!(recovery));
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
        AgentSelectorIdentityResponseInput::SelectorHandleRejected { .. } => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_SELECTOR_IDENTITY",
                    "Selector handle rejection named an invalid recovery action.",
                ),
            );
        }
    };
    if !identity.is_valid() {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_IDENTITY",
                "Authenticated selector identity was incomplete.",
            ),
        );
    }
    let normalizer = match AgentFilePathNormalizer::from_runtime(&args.runtime) {
        Ok(normalizer) => normalizer,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    identity.declaration_file = match normalizer.normalize(&identity.declaration_file) {
        Ok(file) => file.into_rpc_path(),
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                error,
            );
        }
    };
    let Some(target) = AgentExactReplacementSymbolIdentity::from_relation(&identity) else {
        return error_envelope(
            "agent/replace-declaration".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_IDENTITY",
                "Authenticated selector identity was not an exact function or property target.",
            ),
        );
    };
    let identity = match serde_json::to_value(identity) {
        Ok(identity) => identity,
        Err(error) => {
            return error_envelope(
                "agent/replace-declaration".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_SELECTOR_IDENTITY",
                    format!("Authenticated selector identity could not be projected: {error}"),
                ),
            );
        }
    };
    execute_agent_replacement_preview_for_target(
        args.runtime,
        identity_request,
        &session,
        target,
        proposed_declaration,
        "identity",
        identity,
        "Run `kast agent replace-declaration --selector-handle <handle> --content-file <file> --apply --idempotency-key <stable-key> --workspace-root <repo>` to submit this verified replacement.",
    )
}

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
