#[cfg(test)]
mod runtime_status_wire_tests {
    use super::*;

    fn status(extra: Value) -> Value {
        let mut status = serde_json::json!({
            "state": "READY",
            "backendName": "indexer",
            "backendVersion": "test",
            "workspaceRoot": "/workspace",
            "readiness": {
                "runtime": {"type": "READY"},
                "model": {"type": "READY"},
                "references": {"type": "READY"},
                "semanticGraph": {"type": "READY"},
                "mutation": {"type": "READY"}
            }
        });
        status.as_object_mut().expect("status object").extend(
            extra
                .as_object()
                .expect("extra status fields")
                .clone(),
        );
        status
    }

    #[test]
    fn tagged_lanes_are_the_only_runtime_state_authority() {
        let status: RuntimeStatusResponse = serde_json::from_value(status(serde_json::json!({})))
            .expect("runtime status response");

        assert!(status.healthy());
        assert!(status.active());
        assert!(!status.indexing());
    }

    #[test]
    fn legacy_boolean_state_is_rejected() {
        let result = serde_json::from_value::<RuntimeStatusResponse>(status(serde_json::json!({
            "healthy": true
        })));

        assert!(result.is_err());
    }

    #[test]
    fn absent_readiness_lanes_are_rejected() {
        let mut value = status(serde_json::json!({
            "sourceModuleNames": [":fixture"],
            "schemaVersion": 7
        }));
        value.as_object_mut().expect("status object").remove("readiness");

        assert!(serde_json::from_value::<RuntimeStatusResponse>(value).is_err());
    }

    #[test]
    fn workspace_transition_response_policy_covers_reconciliation_methods() {
        let policy = RpcResponseTimeoutPolicy::derive(Duration::from_secs(35));
        let diagnostics = serde_json::json!({"method": "raw/diagnostics"}).to_string();
        let complete_transition_dispatch = Duration::from_secs(60 * 60 + 2 * 35 + 5);

        for method in [
            "raw/semantic-graph",
            "raw/workspace-refresh",
            "raw/apply-edits",
            "raw/exact-file-image-cas",
            "raw/recover-mutation-scratch",
        ] {
            let request = serde_json::json!({"method": method}).to_string();
            assert_eq!(
                policy.for_request(&request).expect("transition policy"),
                complete_transition_dispatch,
                "method={method}"
            );
        }
        assert_eq!(
            policy.for_request(&diagnostics).expect("diagnostics policy"),
            Duration::from_secs(35)
        );
        assert_eq!(
            policy
                .for_request("{}")
                .expect("request without a method has closed ordinary authority"),
            Duration::from_secs(35)
        );
    }
}
