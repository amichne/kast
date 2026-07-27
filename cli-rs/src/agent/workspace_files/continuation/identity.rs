fn workspace_files_continuation_identity(
    query: &AdmittedWorkspaceFilesQueryIdentity,
) -> std::result::Result<WorkspaceFilesContinuationIdentity, AgentError> {
    let backend_name = query.backend.ok_or_else(|| {
        agent_error(
            "AGENT_WORKSPACE_INVALID",
            "Workspace-file continuation identity requires an admitted backend.",
        )
    })?;
    let normalized_query = serde_json::to_string(&json!({
        "filters": &query.filters,
        "kindDomain": query.kind_domain,
    }))
    .map_err(|error| agent_error("AGENT_RESULT_INVALID", error.to_string()))?;
    let projection = if query.view == "fields" {
        format!("fields:{}", query.ordered_fields.join(","))
    } else {
        query.view.to_string()
    };
    Ok(WorkspaceFilesContinuationIdentity {
        workspace_root: query.canonical_workspace_root.clone(),
        backend_name: backend_name.to_string(),
        normalized_query,
        projection,
        limit: query.limit,
    })
}

fn consume_workspace_files_continuation(
    backend: &mut dyn BackendWorkspaceRpc,
    identity: &WorkspaceFilesContinuationIdentity,
    token: &str,
) -> std::result::Result<WorkspaceFilesContinuationState, AgentError> {
    let result = backend
        .request(json_rpc_request(
            "raw/workspace-files-continuation",
            json!({
                "action": "CONSUME",
                "identity": identity,
                "pageToken": token,
            }),
        ))
        .map_err(workspace_files_continuation_failure)?;
    match serde_json::from_value::<WorkspaceFilesContinuationResult>(result) {
        Ok(WorkspaceFilesContinuationResult::Consumed { state }) => Ok(state),
        Ok(WorkspaceFilesContinuationResult::Issued { .. }) => Err(agent_error(
            "AGENT_RESULT_INVALID",
            "Workspace-file continuation consume returned an issue result.",
        )),
        Err(error) => Err(agent_error("AGENT_RESULT_INVALID", error.to_string())),
    }
}

fn issue_workspace_files_continuation(
    backend: &mut dyn BackendWorkspaceRpc,
    identity: &WorkspaceFilesContinuationIdentity,
    state: &WorkspaceFilesContinuationState,
) -> std::result::Result<String, AgentError> {
    let result = backend
        .request(json_rpc_request(
            "raw/workspace-files-continuation",
            json!({
                "action": "ISSUE",
                "identity": identity,
                "state": state,
            }),
        ))
        .map_err(workspace_files_continuation_failure)?;
    match serde_json::from_value::<WorkspaceFilesContinuationResult>(result) {
        Ok(WorkspaceFilesContinuationResult::Issued { page_token }) => page_token
            .parse::<WorkspaceFilesPublicPageToken>()
            .map(|token| token.canonical())
            .map_err(|error| agent_error("AGENT_RESULT_INVALID", error)),
        Ok(WorkspaceFilesContinuationResult::Consumed { .. }) => Err(agent_error(
            "AGENT_RESULT_INVALID",
            "Workspace-file continuation issue returned a consume result.",
        )),
        Err(error) => Err(agent_error("AGENT_RESULT_INVALID", error.to_string())),
    }
}

fn workspace_files_continuation_failure(failure: BackendRpcFailure) -> AgentError {
    match failure {
        BackendRpcFailure::Api { code, message, .. }
            if code == "INVALID_WORKSPACE_FILES_PAGE_TOKEN" =>
        {
            let mut error = agent_error(&code, message);
            error.details.insert("status".to_string(), json!(400));
            error.details.insert("retryable".to_string(), json!(false));
            error
        }
        failure => agent_error(
            "WORKSPACE_FILES_CONTINUATION_UNAVAILABLE",
            failure.to_string(),
        ),
    }
}
