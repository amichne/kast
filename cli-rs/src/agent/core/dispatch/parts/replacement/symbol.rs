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
                    .insert("candidates".to_string(), json!(candidates));
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
