#[cfg(test)]
mod result_projection_tests {
    use super::*;

    #[test]
    fn diagnostics_count_view_retains_completeness_and_severity_counts() {
        let projected = project_diagnostics_envelope(
            command_envelope(
                "agent/diagnostics",
                vec![json!({
                    "name": "diagnostics",
                    "method": "raw/diagnostics",
                    "mutates": false,
                    "ok": true,
                    "result": {
                        "diagnostics": [{
                            "location": diagnostic_location(),
                            "severity": "ERROR",
                            "message": "Broken",
                            "code": "BROKEN"
                        }],
                        "fileStatuses": [{
                            "filePath": "/workspace/App.kt",
                            "state": "ANALYZED"
                        }],
                        "semanticOutcome": "COMPLETE",
                        "requestedFileCount": 1,
                        "analyzedFileCount": 1,
                        "skippedFileCount": 0
                    },
                    "error": null
                })],
            ),
            AgentResultView::Count,
        );
        let result = projected.result.expect("diagnostics count");

        assert_eq!(result["type"], "KAST_AGENT_DIAGNOSTICS_COUNT");
        assert_eq!(result["analysis"]["analyzedFileCount"], 1);
        assert_eq!(result["severityCounts"]["error"], 1);
        assert!(result.get("diagnostics").is_none(), "{result}");
    }

    #[test]
    fn verify_count_view_retains_check_and_capability_counts() {
        let projected = project_verify_envelope(
            command_envelope(
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
                            "state": "READY", "backendName": "idea",
                            "backendVersion": "test", "workspaceRoot": "/workspace"
                        },
                        "error": null
                    }),
                    json!({
                        "name": "capabilities", "method": "capabilities", "mutates": false,
                        "ok": true,
                        "result": {
                            "readCapabilities": ["symbol/resolve", "raw/diagnostics"],
                            "mutationCapabilities": ["mutation/submit"]
                        },
                        "error": null
                    }),
                ],
            ),
            AgentResultView::Count,
        );
        let result = projected.result.expect("verify count");

        assert_eq!(result["type"], "KAST_AGENT_VERIFY_COUNT");
        assert_eq!(result["checkCount"], 3);
        assert_eq!(result["passedCount"], 3);
        assert_eq!(result["readCapabilityCount"], 2);
        assert_eq!(result["mutationCapabilityCount"], 1);
    }

    #[test]
    fn verify_failure_retains_the_failed_step_error_without_raw_steps() {
        let mut envelope = command_envelope(
            "agent/verify",
            vec![json!({
                "name": "health", "method": "health", "mutates": false,
                "ok": false, "result": null,
                "error": {"code": "BACKEND_NOT_READY", "message": "Indexing"}
            })],
        );
        envelope.ok = false;
        envelope.error = Some(agent_error("AGENT_COMMAND_FAILED", "Agent command failed."));

        let projected = project_verify_envelope(envelope, AgentResultView::Compact);

        assert!(!projected.ok);
        assert!(projected.result.is_none());
        assert_eq!(
            projected.error.expect("verify error").code,
            "BACKEND_NOT_READY"
        );
    }

    #[test]
    fn diagnostics_failure_retains_the_failed_step_error_without_raw_steps() {
        let mut envelope = command_envelope(
            "agent/diagnostics",
            vec![json!({
                "name": "diagnostics", "method": "raw/diagnostics", "mutates": false,
                "ok": false, "result": null,
                "error": {
                    "code": "SEMANTIC_ANALYSIS_INVALID",
                    "message": "Evidence was malformed"
                }
            })],
        );
        envelope.ok = false;
        envelope.error = Some(agent_error("AGENT_COMMAND_FAILED", "Agent command failed."));

        let projected = project_diagnostics_envelope(envelope, AgentResultView::Compact);

        assert!(!projected.ok);
        assert!(projected.result.is_none());
        assert_eq!(
            projected.error.expect("diagnostics error").code,
            "SEMANTIC_ANALYSIS_INVALID"
        );
    }

    #[test]
    fn mutation_selected_view_emits_only_compatible_selected_fields() {
        let projected = project_mutation_envelope(
            result_envelope(
                "mutation/status".to_string(),
                json!({
                    "operationId": "00000000-0000-0000-0000-000000000337",
                    "idempotencyKey": "issue-337",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "COMPLETED",
                        "trace": {"editApplicationState": "COMPLETED"},
                        "cancellationRequested": false,
                        "result": {
                            "type": "SCOPE_MUTATION_RESULT",
                            "response": {
                                "editCount": 1,
                                "affectedFiles": ["/workspace/App.kt"],
                                "createdFiles": [],
                                "diagnostics": {"errorCount": 0, "warningCount": 0}
                            }
                        }
                    }
                }),
            ),
            AgentResultView::Fields(vec![
                AgentMutationField::State,
                AgentMutationField::Files,
            ]),
        );
        let result = projected.result.expect("mutation selection");

        assert_eq!(result["type"], "KAST_AGENT_MUTATION_SELECTION");
        assert_eq!(result["state"]["state"], "COMPLETED");
        assert_eq!(result["files"], json!(["/workspace/App.kt"]));
        assert!(result.get("operation").is_none(), "{result}");
        assert!(result.get("edits").is_none(), "{result}");
        assert!(result.get("diagnostics").is_none(), "{result}");
    }

    #[test]
    fn mutation_failure_retains_typed_failure_evidence_without_the_raw_snapshot() {
        let projected = project_mutation_envelope(
            result_envelope(
                "mutation/status".to_string(),
                json!({
                    "operationId": "00000000-0000-0000-0000-000000000337",
                    "idempotencyKey": "issue-337-failure",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "FAILED",
                        "trace": {"editApplicationState": "NOT_STARTED"},
                        "cancellationRequested": false,
                        "failure": {
                            "type": "THROWN_FAILURE",
                            "error": {
                                "requestId": "request-337",
                                "code": "MUTATION_BACKEND_FAILED",
                                "message": "Backend unavailable",
                                "retryable": true,
                                "details": {}
                            }
                        }
                    }
                }),
            ),
            AgentResultView::Compact,
        );
        let result = projected.result.expect("mutation failure result");

        assert_eq!(result["operation"]["state"], "FAILED");
        assert_eq!(
            result["operation"]["failure"]["kind"],
            "THROWN_FAILURE"
        );
        assert_eq!(
            result["operation"]["failure"]["code"],
            "MUTATION_BACKEND_FAILED"
        );
        assert_eq!(result["operation"]["failure"]["retryable"], true);
    }

    #[test]
    fn applied_invalid_mutation_retains_edits_files_and_diagnostic_counts() {
        let projected = project_mutation_envelope(
            result_envelope(
                "mutation/status".to_string(),
                json!({
                    "operationId": "00000000-0000-0000-0000-000000000338",
                    "idempotencyKey": "issue-337-invalid",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "FAILED",
                        "trace": {"editApplicationState": "COMPLETED"},
                        "cancellationRequested": false,
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
                    }
                }),
            ),
            AgentResultView::Compact,
        );
        let result = projected.result.expect("applied invalid result");

        assert_eq!(result["appliedEditCount"], 1);
        assert_eq!(result["edits"][0]["filePath"], "/workspace/App.kt");
        assert_eq!(result["files"], json!(["/workspace/App.kt"]));
        assert_eq!(result["diagnostics"]["error"], 2);
        assert_eq!(result["diagnostics"]["warning"], 1);
    }

    fn command_envelope(method: &str, steps: Vec<Value>) -> AgentEnvelope {
        result_envelope(
            method.to_string(),
            json!({
                "type": "KAST_AGENT_COMMAND",
                "ok": true,
                "steps": steps,
                "issues": [],
                "schemaVersion": SCHEMA_VERSION
            }),
        )
    }

    fn diagnostic_location() -> Value {
        json!({
            "filePath": "/workspace/App.kt",
            "startOffset": 0,
            "endOffset": 1,
            "startLine": 1,
            "startColumn": 1,
            "preview": "x"
        })
    }
}
