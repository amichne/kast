#[cfg(test)]
mod runtime_status_wire_tests {
    use super::*;

    fn status(extra: Value) -> Value {
        let mut status = serde_json::json!({
            "state": "READY",
            "healthy": true,
            "active": true,
            "indexing": false,
            "backendName": "indexer",
            "backendVersion": "test",
            "workspaceRoot": "/workspace"
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
    fn aggregate_not_ready_blocks_legacy_admission_without_discarding_status() {
        let wire: RuntimeStatusWireResponse = serde_json::from_value(status(serde_json::json!({
            "readiness": {"runtime": {"type": "READY"}},
            "ready": false
        })))
        .expect("runtime status wire response");

        let normalized = wire.into_status().expect("normalized runtime status");

        assert!(normalized.indexing);
        assert_eq!(normalized.state, RuntimeState::Ready);
    }

    #[test]
    fn aggregate_and_readiness_lanes_must_be_published_together() {
        let wire: RuntimeStatusWireResponse = serde_json::from_value(status(serde_json::json!({
            "ready": false
        })))
        .expect("runtime status wire response");

        let error = wire.into_status().expect_err("incomplete readiness wire evidence");

        assert_eq!(error.code, "RUNTIME_STATUS_INVALID");
    }

    #[test]
    fn legacy_runtime_status_remains_compatible() {
        let wire: RuntimeStatusWireResponse = serde_json::from_value(status(serde_json::json!({
            "sourceModuleNames": [":fixture"],
            "referenceIndexReady": true,
            "schemaVersion": 6
        })))
        .expect("legacy runtime status");

        assert!(!wire.into_status().expect("legacy status").indexing);
    }
}
