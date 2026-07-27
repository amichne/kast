fn execute_request(request: AgentRequest) -> AgentEnvelope {
    execute_request_with_session(request, None)
}

fn execute_request_with_session(
    request: AgentRequest,
    session: Option<&runtime::RawRpcSession>,
) -> AgentEnvelope {
    if request.operation == AgentOperation::AppliedMutation {
        match runtime::semantic_mutation_workspace_route(
            request.runtime.workspace_root.clone(),
            request.runtime.backend_name,
        ) {
            Ok(runtime::SemanticWorkspaceRoute::Admitted(_)) => {}
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
        }
    }
    let owned_session = if request.operation == AgentOperation::AppliedMutation && session.is_none()
    {
        match runtime::raw_rpc_session(
            request.runtime.workspace_root.clone(),
            request.runtime.backend_name,
        ) {
            Ok(session) => Some(session),
            Err(error) => {
                return error_envelope(
                    request.method,
                    Some(request.request),
                    AgentError::from_cli_error(error),
                );
            }
        }
    } else {
        None
    };
    let session = session.or(owned_session.as_ref());
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
            request.runtime.backend_name,
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
