use super::*;

#[test]
fn verify_all_compact_views_retain_semantic_workspace_evidence() {
    let envelope = || {
        let mut envelope = command_envelope(
            "agent/verify",
            vec![
                json!({
                    "name": "health", "method": "health", "mutates": false,
                    "ok": true, "result": {"status": "READY"}, "error": null
                }),
                json!({
                    "name": "runtime-status", "method": "runtime/status", "mutates": false,
                    "ok": true,
                    "result": {
                        "state": "READY", "backendName": "indexer",
                        "backendVersion": "test", "workspaceRoot": "/workspace"
                    },
                    "error": null
                }),
                json!({
                    "name": "capabilities", "method": "capabilities", "mutates": false,
                    "ok": true,
                    "result": {"readCapabilities": [], "mutationCapabilities": []},
                    "error": null
                }),
            ],
        );
        envelope
            .result
            .as_mut()
            .and_then(Value::as_object_mut)
            .expect("command result")
            .insert(
                "semanticWorkspace".to_string(),
                json!({
                    "backendName": "indexer",
                    "workspaceRoot": "/workspace",
                    "workspaceKind": "LINKED_WORKTREE",
                    "sourceModuleNames": ["analysis-api"],
                    "limitations": [],
                    "evidenceQuality": "COMPILER_BACKED"
                }),
            );
        envelope
    };

    let views = [
        AgentResultView::Compact,
        AgentResultView::Fields(vec![AgentVerifyField::Health]),
        AgentResultView::Count,
    ];
    for view in views {
        let projected = project_verify_envelope(envelope(), view);
        let result = projected.result.expect("verify result");

        assert_eq!(result["semanticWorkspace"]["workspaceRoot"], "/workspace");
        assert_eq!(
            result["semanticWorkspace"]["workspaceKind"],
            "LINKED_WORKTREE"
        );
    }
}

#[test]
fn mutation_selected_view_emits_only_compatible_selected_fields() {
    let projected = project_mutation_envelope(
        result_envelope(
            "mutation/submit".to_string(),
            json!({
                "type": "SUCCEEDED",
                "deduplicated": false,
                "result": {
                    "type": "SCOPE_MUTATION_RESULT",
                    "response": {
                        "editCount": 1,
                        "affectedFiles": ["/workspace/App.kt"],
                        "createdFiles": [],
                        "diagnostics": {"errorCount": 0, "warningCount": 0}
                    }
                }
            }),
        ),
        AgentResultView::Fields(vec![AgentMutationField::Outcome, AgentMutationField::Files]),
    );
    let result = projected.result.expect("mutation selection");

    assert_eq!(result["type"], "KAST_AGENT_MUTATION_SELECTION");
    assert_eq!(result["outcome"], "SUCCEEDED");
    assert_eq!(result["files"], json!(["/workspace/App.kt"]));
    assert!(result.get("deduplicated").is_none(), "{result}");
    assert!(result.get("edits").is_none(), "{result}");
    assert!(result.get("diagnostics").is_none(), "{result}");
}

#[test]
fn mutation_failure_retains_typed_failure_evidence_without_the_raw_snapshot() {
    let projected = project_mutation_envelope(
        result_envelope(
            "mutation/submit".to_string(),
            json!({
                "type": "FAILED",
                "deduplicated": true,
                "failure": {
                    "type": "THROWN_FAILURE",
                    "error": {
                        "requestId": "request-337",
                        "code": "MUTATION_BACKEND_FAILED",
                        "message": "Backend unavailable",
                        "retryable": true,
                        "details": {
                            "backendName": "indexer",
                            "operation": "rename"
                        }
                    }
                }
            }),
        ),
        AgentResultView::Compact,
    );
    let result = projected.result.expect("mutation failure result");

    assert_eq!(result["execution"]["outcome"], "FAILED");
    assert_eq!(result["execution"]["deduplicated"], true);
    assert_eq!(result["execution"]["failure"]["kind"], "THROWN_FAILURE");
    assert_eq!(
        result["execution"]["failure"]["code"],
        "MUTATION_BACKEND_FAILED"
    );
    assert_eq!(result["execution"]["failure"]["retryable"], true);
    assert_eq!(result["execution"]["failure"]["requestId"], "request-337");
    assert_eq!(
        result["execution"]["failure"]["details"]["backendName"],
        "indexer"
    );
    assert_eq!(
        result["execution"]["failure"]["details"]["operation"],
        "rename"
    );
}

#[test]
fn applied_invalid_mutation_retains_edits_files_and_diagnostic_counts() {
    let projected = project_mutation_envelope(
        result_envelope(
            "mutation/submit".to_string(),
            json!({
                "type": "FAILED",
                "deduplicated": false,
                "failure": {
                    "type": "APPLIED_INVALID_RENAME",
                    "response": {
                        "editCount": 1,
                        "affectedFiles": ["/workspace/App.kt"],
                        "applyResult": {
                            "applied": [{
                                "filePath": "/workspace/App.kt",
                                "startOffset": 1,
                                "endOffset": 4,
                                "newText": "Renamed"
                            }],
                            "affectedFiles": ["/workspace/App.kt"]
                        },
                        "diagnostics": {
                            "errorCount": 2,
                            "warningCount": 1
                        }
                    }
                }
            }),
        ),
        AgentResultView::Compact,
    );
    let result = projected.result.expect("applied invalid result");

    assert_eq!(result["appliedEditCount"], 1);
    assert_eq!(result["edits"][0]["filePath"], "/workspace/App.kt");
    assert_eq!(result["edits"][0]["newText"], "Renamed");
    assert_eq!(result["files"], json!(["/workspace/App.kt"]));
    assert_eq!(result["diagnostics"]["error"], 2);
    assert_eq!(result["diagnostics"]["warning"], 1);
}
