fn workspace_files_resume_offset(
    continuation: &ValidatedWorkspaceFilesContinuation<'_>,
    matching: &[&WorkspaceInventoryFile],
) -> std::result::Result<usize, AgentError> {
    let state = continuation.state;
    let Some(last_index) = matching
        .iter()
        .position(|file| file.path().to_string() == state.last_relative_path)
    else {
        return Err(stale_workspace_files_page());
    };
    let offset = last_index.saturating_add(1);
    if offset != state.cumulative_returned_count {
        return Err(invalid_workspace_files_page(
            "Workspace-file continuation cumulative count is inconsistent.",
        ));
    }
    Ok(offset)
}

fn validate_workspace_files_resumed_snapshot<'a>(
    state: &'a WorkspaceFilesContinuationState,
    identity: &WorkspaceFilesContinuationIdentity,
    capability_evidence: &WorkspaceFilesCapabilityEvidence,
    snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot,
) -> std::result::Result<ValidatedWorkspaceFilesContinuation<'a>, AgentError> {
    if &state.identity != identity {
        return Err(invalid_workspace_files_page(
            "Workspace-file continuation identity does not match this query.",
        ));
    }
    if &state.capability_evidence != capability_evidence
        || state.composition_stamp_digest != snapshot.composition_digest()
        || !snapshot.continuation_allowed()
    {
        return Err(stale_workspace_files_page());
    }
    Ok(ValidatedWorkspaceFilesContinuation { state })
}

fn invalid_workspace_files_page(message: &str) -> AgentError {
    let mut error = agent_error("INVALID_WORKSPACE_FILES_PAGE_TOKEN", message);
    error.details.insert("status".to_string(), json!(400));
    error.details.insert("retryable".to_string(), json!(false));
    error
}

fn stale_workspace_files_page() -> AgentError {
    let mut error = agent_error(
        "STALE_WORKSPACE_FILES_PAGE",
        "Workspace-file evidence changed; start a new unpaged query.",
    );
    error.details.insert("status".to_string(), json!(409));
    error.details.insert("retryable".to_string(), json!(true));
    error
        .details
        .insert("restartFromFirstPage".to_string(), json!(true));
    error
}

fn workspace_files_index_evidence_complete(snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot) -> bool {
    ![
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
        WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete,
        WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending,
        WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable,
    ]
    .into_iter()
    .any(|code| snapshot.limitation_count(code) > 0)
}
