fn execute_agent_rename_symbol_preview(
    args: AgentRenameArgs,
    identity_request: Value,
) -> AgentEnvelope {
    let Some(symbol) = args.symbol.as_ref() else {
        return error_envelope(
            "agent/rename".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_INPUT",
                "A named rename preview requires --symbol.",
            ),
        );
    };
    let resolve_request = json_rpc_request(
        "symbol/resolve",
        drop_nulls(json!({
            "symbol": symbol,
            "kind": args.kind.map(|kind| kind.canonical()),
            "fileHint": args.file_hint,
            "containingType": args.containing_type,
            "includeDeclarationScope": false,
            "includeDocumentation": false,
            "includeSurroundingMembers": false,
        })),
    );
    let session = match runtime::raw_rpc_session(args.runtime.workspace_root.clone()) {
        Ok(session) => session,
        Err(error) => {
            return error_envelope(
                "agent/rename".to_string(),
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
            "agent/rename".to_string(),
            Some(identity_request),
            resolution.error.unwrap_or_else(|| {
                agent_error(
                    "INVALID_RENAME_RESOLUTION",
                    "Rename target resolution failed without a typed error.",
                )
            }),
        );
    }
    let resolved_value = match resolution.result {
        Some(result) => result,
        None => {
            return error_envelope(
                "agent/rename".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_RENAME_RESOLUTION",
                    "Rename target resolution returned no result.",
                ),
            );
        }
    };
    let resolved =
        match serde_json::from_value::<AgentCompilerResolveResponse>(resolved_value.clone()) {
            Ok(AgentCompilerResolveResponse::Resolved { symbol, .. }) => symbol,
            Ok(AgentCompilerResolveResponse::NotFound) => {
                return error_envelope(
                    "agent/rename".to_string(),
                    Some(identity_request),
                    agent_error(
                        "AGENT_RENAME_TARGET_NOT_FOUND",
                        "The requested rename target was not found.",
                    ),
                );
            }
            Ok(AgentCompilerResolveResponse::Ambiguous { candidates }) => {
                let mut error = agent_error(
                    "AGENT_RENAME_TARGET_AMBIGUOUS",
                    "The requested rename target was ambiguous.",
                );
                error
                    .details
                    .insert("candidates".to_string(), json!(candidates));
                return error_envelope("agent/rename".to_string(), Some(identity_request), error);
            }
            Ok(AgentCompilerResolveResponse::OperationalFailure) => {
                return error_envelope(
                    "agent/rename".to_string(),
                    Some(identity_request),
                    agent_error(
                        "AGENT_RENAME_RESOLUTION_FAILED",
                        "The compiler could not resolve the requested rename target.",
                    ),
                );
            }
            Err(error) => {
                return error_envelope(
                    "agent/rename".to_string(),
                    Some(identity_request),
                    agent_error(
                        "INVALID_RENAME_RESOLUTION",
                        format!("Rename target resolution violated its contract: {error}"),
                    ),
                );
            }
        };
    let Some(expected_target) = resolved.rename_target_identity() else {
        return error_envelope(
            "agent/rename".to_string(),
            Some(identity_request),
            agent_error(
                "AGENT_RENAME_IDENTITY_ANCHOR_UNAVAILABLE",
                "The compiler-resolved rename target had no usable file and offset anchor.",
            ),
        );
    };
    execute_agent_rename_preview_at_position(
        args,
        identity_request,
        &session,
        expected_target,
        "resolution",
        resolved_value,
        "Run `kast agent rename --symbol <fq-name> --new-name <name> --apply --workspace-root <repo>` to apply this verified rename plan.",
    )
}

fn execute_agent_rename_handle_preview(
    args: AgentRenameArgs,
    identity_request: Value,
    selector_handle: AgentSelectorHandle,
) -> AgentEnvelope {
    let selector_identity_request = json_rpc_request(
        "selector/identity",
        json!({
            "selectorHandle": selector_handle,
            "family": "RENAME",
        }),
    );
    let session = match runtime::raw_rpc_session(args.runtime.workspace_root.clone()) {
        Ok(session) => session,
        Err(error) => {
            return error_envelope(
                "agent/rename".to_string(),
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
            "agent/rename".to_string(),
            Some(identity_request),
            response.error.unwrap_or_else(|| {
                agent_error(
                    "RENAME_SELECTOR_IDENTITY_FAILED",
                    "Selector identity authentication failed without a typed error.",
                )
            }),
        );
    }
    let Some(result) = response.result else {
        return error_envelope(
            "agent/rename".to_string(),
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
                "agent/rename".to_string(),
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
                "The backend rejected the rename selector handle.",
            );
            error.details.insert("reason".to_string(), json!(reason));
            error
                .details
                .insert("recovery".to_string(), json!(recovery));
            return error_envelope("agent/rename".to_string(), Some(identity_request), error);
        }
        AgentSelectorIdentityResponseInput::SelectorHandleRejected { .. } => {
            return error_envelope(
                "agent/rename".to_string(),
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
            "agent/rename".to_string(),
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
            return error_envelope("agent/rename".to_string(), Some(identity_request), error);
        }
    };
    identity.declaration_file = match normalizer.normalize(&identity.declaration_file) {
        Ok(file) => file.into_rpc_path(),
        Err(error) => {
            return error_envelope("agent/rename".to_string(), Some(identity_request), error);
        }
    };
    if identity.declaration_start_offset > i32::MAX as u64 {
        return error_envelope(
            "agent/rename".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_IDENTITY",
                "Authenticated selector identity offset exceeded the semantic backend range.",
            ),
        );
    }
    let Some(expected_target) = AgentExactRenameSymbolIdentity::from_relation(&identity) else {
        return error_envelope(
            "agent/rename".to_string(),
            Some(identity_request),
            agent_error(
                "INVALID_SELECTOR_IDENTITY",
                "Authenticated selector identity could not become an exact rename target.",
            ),
        );
    };
    let identity = match serde_json::to_value(identity) {
        Ok(identity) => identity,
        Err(error) => {
            return error_envelope(
                "agent/rename".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_SELECTOR_IDENTITY",
                    format!("Authenticated selector identity could not be projected: {error}"),
                ),
            );
        }
    };
    execute_agent_rename_preview_at_position(
        args,
        identity_request,
        &session,
        expected_target,
        "identity",
        identity,
        "Run `kast agent rename --selector-handle <handle> --new-name <name> --apply --idempotency-key <stable-key> --workspace-root <repo>` to submit this verified rename.",
    )
}

fn execute_agent_rename_preview_at_position(
    args: AgentRenameArgs,
    identity_request: Value,
    session: &runtime::RawRpcSession,
    expected_target: AgentExactRenameSymbolIdentity,
    evidence_name: &'static str,
    evidence: Value,
    help: &'static str,
) -> AgentEnvelope {
    let position = expected_target.position();
    let preview_request = json_rpc_request(
        "raw/rename",
        json!({
            "position": position,
            "newName": args.new_name,
            "dryRun": true,
        }),
    );
    let preview_envelope = execute_request_with_session(
        AgentRequest {
            method: "raw/rename".to_string(),
            request: preview_request,
            runtime: args.runtime,
            full_response: true,
            operation: AgentOperation::MutationPreview,
        },
        Some(session),
    );
    if !preview_envelope.ok {
        return error_envelope(
            "agent/rename".to_string(),
            Some(identity_request),
            preview_envelope.error.unwrap_or_else(|| {
                agent_error(
                    "INVALID_RENAME_PREVIEW",
                    "The backend rename preview failed without a typed error.",
                )
            }),
        );
    }
    let preview = match preview_envelope
        .result
        .and_then(|result| serde_json::from_value::<AgentRenamePreview>(result).ok())
    {
        Some(preview) => preview,
        None => {
            return error_envelope(
                "agent/rename".to_string(),
                Some(identity_request),
                agent_error(
                    "INVALID_RENAME_PREVIEW",
                    "The backend rename preview violated the typed edit-plan contract.",
                ),
            );
        }
    };
    if let Err(message) = preview.validate_for_target(&expected_target) {
        return error_envelope(
            "agent/rename".to_string(),
            Some(identity_request),
            agent_error("INVALID_RENAME_PREVIEW", message),
        );
    }
    let mut result = json!({
        "type": "KAST_AGENT_RENAME_PLAN",
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
        .expect("rename plan must be a JSON object")
        .insert(evidence_name.to_string(), evidence);
    result_envelope("agent/rename".to_string(), result)
}
