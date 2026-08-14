#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedAddFilePlanRequest<'a> {
    workspace_root: &'a str,
    target_path: &'a str,
    proposed_content: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedAddFileApplyRequest<'a> {
    workspace_root: &'a str,
    plan_id: &'a str,
    expected_version: u64,
    approval_evidence: VerifiedAddFileApprovalEvidence,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedAddFileApprovalEvidence {
    approved_by: &'static str,
    evidence_sha256: String,
}

#[derive(Clone, Copy)]
enum VerifiedAddFileRpcOperation {
    Plan,
    Apply,
}

impl VerifiedAddFileRpcOperation {
    fn method(self) -> &'static str {
        match self {
            Self::Plan => "change/plan-add-file",
            Self::Apply => "change/apply-add-file",
        }
    }
}

fn verified_add_file_rpc(
    workspace_root: &Path,
    operation: VerifiedAddFileRpcOperation,
    params: &impl Serialize,
) -> Result<Value> {
    let route = match operation {
        VerifiedAddFileRpcOperation::Plan => {
            runtime::semantic_workspace_route(Some(workspace_root.to_path_buf()))?
        }
        VerifiedAddFileRpcOperation::Apply => {
            runtime::semantic_mutation_workspace_route(Some(workspace_root.to_path_buf()))?
        }
    };
    let admission = match route {
        runtime::SemanticWorkspaceRoute::Admitted(admission) => admission,
        runtime::SemanticWorkspaceRoute::Rejected(rejection) => {
            return Err(rejection.into_cli_error());
        }
    };
    let session = runtime::raw_rpc_session_for_admission(*admission);
    let request = json!({
        "jsonrpc": "2.0",
        "method": operation.method(),
        "params": params,
        "id": 1,
    });
    let response = runtime::raw_request_passthrough_in_session(
        serde_json::to_string(&request)?,
        Some(workspace_root.to_path_buf()),
        &session,
    )?;
    let response: Value = serde_json::from_str(&response)?;
    if let Some(rpc_error) = response.get("error") {
        let mut error = CliError::new(
            "KAST_VERIFIED_ADD_FILE_RPC_REJECTED",
            "The operation-specific add-file request was rejected by the admitted indexer.",
        );
        error
            .details
            .insert("rpcError".to_string(), rpc_error.to_string());
        return Err(error);
    }
    response.get("result").cloned().ok_or_else(|| {
        CliError::new(
            "KAST_VERIFIED_ADD_FILE_RPC_INVALID",
            "The operation-specific add-file response had no result.",
        )
    })
}
