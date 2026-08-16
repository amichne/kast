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
                "Authenticated selector identity was not an exact function target.",
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
