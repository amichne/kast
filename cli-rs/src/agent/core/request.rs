fn execute_request(request: AgentRequest) -> AgentEnvelope {
    execute_request_with_session::<runtime::lifecycle_typestate::CompilerCapability>(request, None)
}

fn execute_request_with_session<C: runtime::lifecycle_typestate::RequiredCapability>(
    request: AgentRequest,
    session: Option<&runtime::RawRpcSession<C>>,
) -> AgentEnvelope {
    if request.operation == AgentOperation::AppliedMutation {
        return execute_applied_mutation_request(request);
    }
    execute_request_with_optional_session(request, session)
}

fn execute_applied_mutation_request(request: AgentRequest) -> AgentEnvelope {
    let admission = match runtime::semantic_mutation_workspace_route(
        request.runtime.workspace_root.clone(),
    ) {
        Ok(runtime::SemanticWorkspaceRoute::Admitted(admission)) => admission,
        Ok(runtime::SemanticWorkspaceRoute::Rejected(rejection)) => {
            let mut error = agent_error(rejection.code, rejection.message);
            error
                .details
                .insert("semanticWorkspace".to_string(), json!(rejection.evidence));
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
    let session = runtime::raw_rpc_session_for_admission(*admission);
    execute_request_with_optional_session(request, Some(&session))
}

fn execute_request_with_optional_session<C: runtime::lifecycle_typestate::RequiredCapability>(
    request: AgentRequest,
    session: Option<&runtime::RawRpcSession<C>>,
) -> AgentEnvelope {
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
