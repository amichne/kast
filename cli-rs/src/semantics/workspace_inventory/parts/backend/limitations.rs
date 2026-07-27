fn project_model_limitation(failure: &BackendRpcFailure) -> WorkspaceInventoryLimitationCode {
    match failure {
        BackendRpcFailure::Api { reason, .. } => match reason.as_deref() {
            Some("RUNTIME_INDEXING") => WorkspaceInventoryLimitationCode::RuntimeIndexing,
            Some("LINKED_ROOT_UNASSOCIATED") => {
                WorkspaceInventoryLimitationCode::LinkedRootUnassociated
            }
            Some("PROJECT_MODEL_UNAVAILABLE") | None | Some(_) => {
                WorkspaceInventoryLimitationCode::ProjectModelUnavailable
            }
        },
        BackendRpcFailure::Transport(_)
        | BackendRpcFailure::InvalidResponse(_)
        | BackendRpcFailure::Containment { .. } => {
            WorkspaceInventoryLimitationCode::ProjectModelUnavailable
        }
    }
}

fn is_stale(failure: &BackendRpcFailure) -> bool {
    matches!(failure, BackendRpcFailure::Api { code, .. } if code == "STALE_WORKSPACE_INVENTORY")
}

fn is_project_model_incomplete(failure: &BackendRpcFailure) -> bool {
    matches!(failure, BackendRpcFailure::Api { code, .. } if code == "WORKSPACE_PROJECT_MODEL_INCOMPLETE")
}

fn workspace_request(params: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "raw/workspace-files",
        "params": params,
    })
}

fn kind_domain_wire(kind_domain: WorkspaceRequestedKindDomain) -> &'static str {
    match kind_domain {
        WorkspaceRequestedKindDomain::SourceOnly => "SOURCE_ONLY",
        WorkspaceRequestedKindDomain::ScriptOnly => "SCRIPT_ONLY",
        WorkspaceRequestedKindDomain::Mixed => "MIXED",
    }
}
