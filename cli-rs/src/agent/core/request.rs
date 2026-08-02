fn execute_request(request: AgentRequest) -> AgentEnvelope {
    execute_request_with_session(request, None)
}

fn execute_request_with_session(
    request: AgentRequest,
    session: Option<&runtime::RawRpcSession>,
) -> AgentEnvelope {
    let mutation_session = if request.operation == AgentOperation::AppliedMutation {
        let Some(lease_id) = request.runtime.lease_id.as_ref() else {
            return error_envelope(
                request.method,
                Some(request.request),
                agent_error(
                    "WORKSPACE_LEASE_REQUIRED",
                    "Applied semantic mutations require an authenticated exact-root workspace lease.",
                ),
            );
        };
        let validated_lease = match runtime::validate_workspace_lease_for_command(
            lease_id,
            request.runtime.workspace_root.as_deref(),
        ) {
            Ok(lease) => lease,
            Err(error) => return error_envelope(
                request.method,
                Some(request.request),
                AgentError::from_cli_error(error),
            ),
        };
        let admission = match runtime::semantic_mutation_workspace_route(
            request.runtime.workspace_root.clone(),
        ) {
            Ok(runtime::SemanticWorkspaceRoute::Admitted(admission)) => admission,
            Ok(runtime::SemanticWorkspaceRoute::Rejected(rejection)) => {
                let mut error = agent_error(rejection.code, rejection.message);
                error.details.insert(
                    "semanticWorkspace".to_string(),
                    json!(rejection.evidence),
                );
                return error_envelope(request.method, Some(request.request), error);
            }
            Err(error) => {
                return error_envelope(
                    request.method,
                    Some(request.request),
                    AgentError::from_cli_error(error),
                );
            }
        };
        let required_capability = applied_mutation_capability(&request.request);
        if !validated_lease.authorizes(&admission) {
            return error_envelope(
                request.method,
                Some(request.request),
                agent_error(
                    "WORKSPACE_LEASE_RUNTIME_REPLACED",
                    "The admitted indexer is not the exact runtime authenticated by the workspace lease.",
                ),
            );
        }
        if !admission.supports_mutation(required_capability) {
            return error_envelope(
                request.method,
                Some(request.request),
                agent_error(
                    "SEMANTIC_MUTATION_CAPABILITY_UNAVAILABLE",
                    "The admitted indexer did not advertise the required mutation capability.",
                ),
            );
        }
        Some(runtime::raw_rpc_session_for_admission(*admission))
    } else {
        None
    };
    let session = mutation_session.as_ref().or(session);
    let validation = validate_request(&request.method, &request.request);
    if let Err(error) = validation {
        return error_envelope(request.method, Some(request.request), error);
    }
    let raw_request = match serde_json::to_string(&request.request) {
        Ok(raw_request) => raw_request,
        Err(error) => {
            return error_envelope(
                request.method,
                Some(request.request),
                AgentError::from_cli_error(CliError::from(error)),
            );
        }
    };
    let response = match session {
        Some(session) => runtime::raw_request_passthrough_in_session(
            raw_request,
            request.runtime.workspace_root.clone(),
            session,
        ),
        None => runtime::raw_request_passthrough(
            raw_request,
            request.runtime.workspace_root.clone(),
        ),
    };
    match response {
        Ok(raw_response) => {
            let mut envelope = response_envelope(
                request.method,
                request.request,
                raw_response,
                request.full_response,
            );
            if let Some(failure) = envelope
                .result
                .as_ref()
                .filter(|result| result["type"] == "FAILED")
                .map(|result| &result["failure"])
            {
                envelope.ok = false;
                envelope.error = Some(AgentError {
                    code: failure["error"]["code"].as_str().unwrap_or("SEMANTIC_MUTATION_FAILED").to_string(),
                    message: failure["error"]["message"].as_str()
                        .or_else(|| failure["response"]["message"].as_str())
                        .unwrap_or("The semantic mutation failed.").to_string(),
                    details: BTreeMap::new(),
                });
            }
            envelope
        }
        Err(error) => error_envelope(
            request.method,
            Some(request.request),
            AgentError::from_cli_error(error),
        ),
    }
}

fn applied_mutation_capability(request: &Value) -> runtime::SemanticMutationCapability {
    if request.pointer("/params/type").and_then(Value::as_str) == Some("RENAME") {
        runtime::SemanticMutationCapability::Rename
    } else {
        runtime::SemanticMutationCapability::ApplyEdits
    }
}
