fn execute_request(request: AgentRequest) -> AgentEnvelope {
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
    let response = runtime::rpc_passthrough(RpcArgs {
        request: Some(raw_request),
        request_file: None,
        workspace_root: request.runtime.workspace_root,
        backend_name: request.runtime.backend_name,
    });
    match response {
        Ok(raw_response) => response_envelope(request.method, request.request, raw_response),
        Err(error) => error_envelope(
            request.method,
            Some(request.request),
            AgentError::from_cli_error(error),
        ),
    }
}
