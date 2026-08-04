#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::ReferenceCoverageState;
    use serde_json::json;

    #[test]
    fn readiness_requires_exact_ready_runtime_only() {
        let root = Path::new("/workspace");
        let mut status = RuntimeStatusResponse {
            state: RuntimeState::Ready,
            healthy: true,
            active: true,
            indexing: false,
            backend_name: "indexer".to_string(),
            backend_version: "test".to_string(),
            workspace_root: root.display().to_string(),
            message: None,
            warnings: Vec::new(),
            source_module_names: vec!["main".to_string()],
            dependent_module_names_by_source_module_name: serde_json::Map::new(),
            reference_index_ready: true,
            reference_coverage_state: ReferenceCoverageState::Complete,
            reference_coverage_limitations: vec![],
            published_workspace_generation: None,
            schema_version: crate::SCHEMA_VERSION,
        };

        assert!(semantic_status_ready(root, &status));
        status.reference_index_ready = false;
        assert!(semantic_status_ready(root, &status));
        status.reference_index_ready = true;
        status.source_module_names.clear();
        assert!(semantic_status_ready(root, &status));
        status.source_module_names.push("main".to_string());
        status.workspace_root = "/different".to_string();
        assert!(!semantic_status_ready(root, &status));
    }

    #[test]
    fn sanitizer_removes_protocol_cruft_but_preserves_nested_discriminants() {
        let result = sanitize_agent_result(
            json!({
                "type": "ROOT",
                "ok": true,
                "method": "agent/example",
                "schemaVersion": 1,
                "item": {
                    "type": "NESTED",
                    "ok": true,
                    "schemaVersion": 1
                }
            }),
            true,
        );

        assert_eq!(result, json!({"item": {"type": "NESTED"}}));
    }

    #[test]
    fn sanitizer_exposes_actionable_continuations_without_protocol_fields() {
        let result = sanitize_agent_result(
            json!({
                "type": "KAST_NATIVE_GRAPH_NODES",
                "afterId": 0,
                "nextAfterId": 42,
                "nextPageToken": "opaque",
                "page": {
                    "truncated": true,
                    "nextPageToken": "relation-page"
                },
                "nodes": []
            }),
            true,
        );

        assert_eq!(
            result,
            json!({
                "nodes": [],
                "page": {"truncated": true, "nextPage": "relation-page"},
                "truncated": true,
                "nextPage": "opaque"
            })
        );
    }
}
