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
                        "skippedFileCount": 0,
                        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
                        "cardinality": {"type": "EXACT", "totalCount": 1}
                    },
                    "error": null
                })],
            ),
            AgentResultView::Count,
            8,
        );
        let result = projected.result.expect("diagnostics count");

        assert_eq!(result["type"], "KAST_AGENT_DIAGNOSTICS_COUNT");
        assert_eq!(result["analysis"]["analyzedFileCount"], 1);
        assert_eq!(result["severityCounts"]["error"], 1);
        assert!(result.get("diagnostics").is_none(), "{result}");
    }

    #[test]
    fn incomplete_caller_enumeration_reports_known_minimum_cardinality() {
        let input = serde_json::from_value::<AgentSymbolRelationProjectionInput>(json!({
            "relation": "callers",
            "result": {
                "type": "CALLERS_SUCCESS",
                "root": {
                    "symbol": {"fqName": "sample.Target"},
                    "children": [{
                        "symbol": {"fqName": "sample.Caller"},
                        "children": []
                    }]
                },
                "stats": {
                    "totalEdges": 4,
                    "truncatedNodes": 3,
                    "timeoutReached": false,
                    "maxTotalCallsReached": true,
                    "maxChildrenPerNodeReached": false
                }
            }
        }))
        .expect("caller relationship input");

        let projected = AgentRelationshipProjection::try_from_input(input, 4)
            .expect("caller relationship projection");

        assert!(matches!(
            projected.cardinality,
            AgentResultCardinality::KnownMinimum {
                known_minimum_count: 4
            }
        ));
        assert!(projected.truncated);
    }

    #[test]
    fn aggregate_relationship_cardinality_overflow_is_a_typed_projection_error() {
        let relationship = |relation: &str, cardinality| AgentRelationshipProjection {
            relation: relation.to_string(),
            cardinality,
            returned_count: 0,
            truncated: true,
            next_page_token: None,
            items: Vec::new(),
        };
        let projection = AgentSymbolProjection {
            mode: AgentSymbolMode::Exact,
            outcome: "RESOLVED",
            ambiguous: false,
            source: "compiler".to_string(),
            query: None,
            identity: None,
            selector_handle: None,
            location: None,
            candidates: Vec::new(),
            relationships: vec![
                relationship(
                    "references",
                    AgentResultCardinality::Exact {
                        total_count: usize::MAX,
                    },
                ),
                relationship(
                    "callers",
                    AgentResultCardinality::KnownMinimum {
                        known_minimum_count: 1,
                    },
                ),
            ],
        };

        let error = AgentSymbolCountResult::try_from_projection(projection)
            .expect_err("overflowing relationship aggregate must fail closed");

        assert!(error.contains("overflowed usize"), "{error}");
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
    fn verify_final_capabilities_failure_retains_its_typed_error_and_details() {
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
                        "state": "READY", "backendName": "idea",
                        "backendVersion": "test", "workspaceRoot": "/workspace"
                    },
                    "error": null
                }),
                json!({
                    "name": "capabilities", "method": "capabilities", "mutates": false,
                    "ok": false, "result": null,
                    "error": {
                        "code": "CAPABILITIES_UNAVAILABLE",
                        "message": "Capabilities are not ready",
                        "details": {"backendName": "idea", "indexing": true}
                    }
                }),
            ],
        );
        envelope.ok = false;
        envelope.error = Some(agent_error("AGENT_COMMAND_FAILED", "Agent command failed."));

        let projected = project_verify_envelope(envelope, AgentResultView::Compact);
        let error = projected.error.expect("capabilities error");

        assert_eq!(error.code, "CAPABILITIES_UNAVAILABLE");
        assert_eq!(error.details["backendName"], "idea");
        assert_eq!(error.details["indexing"], true);
    }

    #[test]
    fn compact_top_level_error_retains_typed_details() {
        let mut error = agent_error("RUNTIME_TIMEOUT", "Backend timed out");
        error.details.insert(
            "workspaceRoot".to_string(),
            json!("/workspace"),
        );
        let projected = project_symbol_envelope(
            error_envelope("agent/symbol".to_string(), None, error),
            AgentResultView::Compact,
            10,
        );

        assert_eq!(
            projected.error.expect("symbol error").details["workspaceRoot"],
            "/workspace"
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

        let projected = project_diagnostics_envelope(envelope, AgentResultView::Compact, 8);

        assert!(!projected.ok);
        assert!(projected.result.is_none());
        assert_eq!(
            projected.error.expect("diagnostics error").code,
            "SEMANTIC_ANALYSIS_INVALID"
        );
    }

    #[test]
    fn diagnostics_refresh_failure_retains_the_typed_error_without_a_diagnostics_step() {
        let mut envelope = command_envelope(
            "agent/diagnostics",
            vec![json!({
                "name": "workspace-refresh", "method": "raw/workspace-refresh", "mutates": false,
                "ok": false, "result": null,
                "error": {
                    "code": "SEMANTIC_ANALYSIS_INCOMPLETE",
                    "message": "Indexing is still pending",
                    "details": {"filePath": "/workspace/App.kt"}
                }
            })],
        );
        envelope.ok = false;
        envelope.error = Some(agent_error("AGENT_COMMAND_FAILED", "Agent command failed."));

        let projected = project_diagnostics_envelope(envelope, AgentResultView::Compact, 8);
        let error = projected.error.expect("refresh error");

        assert_eq!(error.code, "SEMANTIC_ANALYSIS_INCOMPLETE");
        assert_eq!(error.details["filePath"], "/workspace/App.kt");
    }

    #[test]
    fn diagnostics_all_compact_views_retain_the_ordered_canonical_file_paths() {
        let envelope = || {
            let mut envelope = command_envelope(
                "agent/diagnostics",
                vec![json!({
                "name": "diagnostics", "method": "raw/diagnostics", "mutates": false,
                "ok": true,
                "result": {
                    "diagnostics": [],
                    "fileStatuses": [
                        {"filePath": "/workspace/B.kt", "state": "ANALYZED"},
                        {"filePath": "/workspace/A.kt", "state": "ANALYZED"}
                    ],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 2,
                    "analyzedFileCount": 2,
                    "skippedFileCount": 0,
                    "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
                    "cardinality": {"type": "EXACT", "totalCount": 0}
                },
                "error": null
                })],
            );
            envelope.result.as_mut().and_then(Value::as_object_mut).expect("command result")
                .insert("filePaths".to_string(), json!(["/workspace/B.kt", "/workspace/A.kt"]));
            envelope
        };

        let views = [
            AgentResultView::Compact,
            AgentResultView::Fields(vec![AgentDiagnosticsField::Analysis]),
            AgentResultView::Count,
        ];
        for view in views {
            let projected = project_diagnostics_envelope(envelope(), view, 8);
            let result = projected.result.expect("diagnostics result");

            assert_eq!(result["filePaths"], json!(["/workspace/B.kt", "/workspace/A.kt"]));
        }
    }

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
                        "state": "READY", "backendName": "idea",
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
            envelope.result.as_mut().and_then(Value::as_object_mut).expect("command result").insert(
                "semanticWorkspace".to_string(),
                json!({
                    "backendName": "idea",
                    "workspaceRoot": "/workspace",
                    "workspaceKind": "LINKED_WORKTREE",
                    "sourceModuleNames": ["analysis-api"],
                    "limitations": [],
                    "evidenceQuality": "COMPILER_BACKED",
                    "nextActions": []
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
            assert_eq!(result["semanticWorkspace"]["workspaceKind"], "LINKED_WORKTREE");
        }
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
                                "details": {
                                    "backendName": "idea",
                                    "operation": "rename"
                                }
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
        assert_eq!(
            result["operation"]["failure"]["requestId"],
            "request-337"
        );
        assert_eq!(
            result["operation"]["failure"]["details"]["backendName"],
            "idea"
        );
        assert_eq!(
            result["operation"]["failure"]["details"]["operation"],
            "rename"
        );
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
        assert_eq!(result["edits"][0]["newText"], "Renamed");
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
