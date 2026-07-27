#[cfg(test)]
mod rpc_error_tests {
    use super::*;

    #[test]
    fn project_model_reason_is_decoded_from_the_typed_error_details_envelope() {
        for (reason, expected) in [
            (
                "RUNTIME_INDEXING",
                WorkspaceInventoryLimitationCode::RuntimeIndexing,
            ),
            (
                "LINKED_ROOT_UNASSOCIATED",
                WorkspaceInventoryLimitationCode::LinkedRootUnassociated,
            ),
        ] {
            let raw = serde_json::json!({
                "jsonrpc": "2.0",
                "id": 1,
                "error": {
                    "code": -32000,
                    "message": "Project model incomplete",
                    "data": {
                        "code": "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
                        "message": "Project model incomplete",
                        "details": {"reason": reason}
                    }
                }
            })
            .to_string();
            let failure = decode_rpc_response(&raw).expect_err("typed RPC error");

            assert_eq!(project_model_limitation(&failure), expected, "{failure:?}");
        }

        let misplaced = serde_json::json!({
            "jsonrpc": "2.0",
            "id": 1,
            "error": {
                "code": -32000,
                "message": "Project model incomplete",
                "data": {
                    "code": "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
                    "message": "Project model incomplete",
                    "reason": "RUNTIME_INDEXING"
                }
            }
        })
        .to_string();
        let failure = decode_rpc_response(&misplaced).expect_err("typed RPC error");
        assert_eq!(
            project_model_limitation(&failure),
            WorkspaceInventoryLimitationCode::ProjectModelUnavailable
        );
    }
}
