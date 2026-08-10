#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::{ReferenceCoverageState, RuntimeReadiness, RuntimeReadinessLane};
    use serde_json::json;

    #[test]
    fn readiness_requires_exact_ready_runtime_only() {
        let root = Path::new("/workspace");
        let mut status = RuntimeStatusResponse {
            state: RuntimeState::Ready,
            backend_name: "indexer".to_string(),
            backend_version: "test".to_string(),
            workspace_root: root.display().to_string(),
            message: None,
            warnings: Vec::new(),
            source_module_names: vec!["main".to_string()],
            dependent_module_names_by_source_module_name: serde_json::Map::new(),
            reference_coverage_state: ReferenceCoverageState::Complete,
            reference_coverage_limitations: vec![],
            published_workspace_generation: None,
            readiness: RuntimeReadiness::ready(),
            schema_version: crate::SCHEMA_VERSION,
        };

        assert!(semantic_status_ready(root, &status));
        status.readiness.references = RuntimeReadinessLane::Blocked;
        assert!(semantic_status_ready(root, &status));
        status.readiness.references = RuntimeReadinessLane::Ready;
        status.source_module_names.clear();
        assert!(semantic_status_ready(root, &status));
        status.source_module_names.push("main".to_string());
        status.workspace_root = "/different".to_string();
        assert!(!semantic_status_ready(root, &status));
    }

    #[test]
    fn file_projection_uses_one_workspace_relative_path_representation() {
        let result = public_file_collection(&json!([{
            "paths": [{
                "filePath": "/workspace/src/main/kotlin/Widget.kt",
                "relativePath": "src/main/kotlin/Widget.kt"
            }]
        }]))
        .expect("public file projection");

        assert_eq!(
            result,
            json!([{"paths": [{"path": "src/main/kotlin/Widget.kt"}]}])
        );
    }

    #[test]
    fn page_projection_preserves_cardinality_and_opaque_continuation() {
        let result = canonical_page(
            &json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 437}),
            200,
            Some("krp1.opaque"),
        )
        .expect("canonical page");
        assert_eq!(
            result,
            json!({
                "cardinality": {"type": "known-minimum", "count": 437},
                "returned": 200,
                "continuation": "krp1.opaque"
            })
        );
    }

    #[test]
    fn current_check_refreshes_only_typed_workspace_staleness() {
        let covered = json!({
            "ok": true,
            "result": {
                "semanticOutcome": "COMPLETE",
                "fileStatuses": [],
                "fileHashes": []
            }
        });
        let vfs_behind_disk = json!({
            "ok": true,
            "result": {
                "semanticOutcome": "INCOMPLETE",
                "fileStatuses": [{"state": "PENDING_INDEX"}],
                "fileHashes": []
            }
        });
        let stale = json!({
            "ok": false,
            "result": {
                "steps": [{
                    "name": "diagnostics",
                    "error": {
                        "code": "CONFLICT",
                        "details": {
                            "rpcError": {
                                "data": {
                                    "details": {"workspaceState": "Pending(source changed)"}
                                }
                            }
                        }
                    }
                }]
            }
        });
        let unrelated = json!({
            "ok": false,
            "result": {
                "steps": [{
                    "name": "diagnostics",
                    "error": {"code": "SEMANTIC_ANALYSIS_INVALID"}
                }]
            }
        });
        let runtime_indexing = json!({
            "ok": false,
            "error": {"code": "RUNTIME_NOT_READY"}
        });
        let published_movement = json!({
            "ok": false,
            "error": {"code": "PUBLISHED_WORKSPACE_MOVED"}
        });
        let generic_conflict = json!({
            "ok": false,
            "error": {"code": "CONFLICT"}
        });

        assert_eq!(
            CurrentCheckAttempt::derive(covered.clone()),
            CurrentCheckAttempt::Covered(covered)
        );
        assert_eq!(
            CurrentCheckAttempt::derive(vfs_behind_disk),
            CurrentCheckAttempt::RefreshRequired(WorkspaceStaleness::DiagnosticPublicationPending)
        );
        assert_eq!(
            CurrentCheckAttempt::derive(stale),
            CurrentCheckAttempt::RefreshRequired(WorkspaceStaleness::SemanticAdmissionMoved)
        );
        assert_eq!(
            CurrentCheckAttempt::derive(runtime_indexing),
            CurrentCheckAttempt::RefreshRequired(WorkspaceStaleness::RuntimeIndexing)
        );
        assert_eq!(
            CurrentCheckAttempt::derive(published_movement),
            CurrentCheckAttempt::RefreshRequired(WorkspaceStaleness::PublishedGenerationMoved)
        );
        assert!(matches!(
            CurrentCheckAttempt::derive(unrelated),
            CurrentCheckAttempt::Rejected(_)
        ));
        assert!(matches!(
            CurrentCheckAttempt::derive(generic_conflict),
            CurrentCheckAttempt::Rejected(_)
        ));
    }
}
