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
                        "fileHashes": [{
                            "filePath": "/workspace/App.kt",
                            "hash": "a".repeat(64)
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
        assert_eq!(result["fileHashes"][0]["filePath"], "/workspace/App.kt");
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
        error
            .details
            .insert("workspaceRoot".to_string(), json!("/workspace"));
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
                    "fileHashes": [
                        {"filePath": "/workspace/B.kt", "hash": "b".repeat(64)},
                        {"filePath": "/workspace/A.kt", "hash": "a".repeat(64)}
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
            envelope
                .result
                .as_mut()
                .and_then(Value::as_object_mut)
                .expect("command result")
                .insert(
                    "filePaths".to_string(),
                    json!(["/workspace/B.kt", "/workspace/A.kt"]),
                );
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

            assert_eq!(
                result["filePaths"],
                json!(["/workspace/B.kt", "/workspace/A.kt"])
            );
            assert_eq!(
                result["fileHashes"],
                json!([
                    {"filePath": "/workspace/B.kt", "hash": "b".repeat(64)},
                    {"filePath": "/workspace/A.kt", "hash": "a".repeat(64)}
                ])
            );
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
            envelope
                .result
                .as_mut()
                .and_then(Value::as_object_mut)
                .expect("command result")
                .insert(
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
                                "backendName": "idea",
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
            "idea"
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

    #[test]
    fn answered_and_ambiguous_projections_require_intent_evidence() {
        for intent in [
            "resolve",
            "path",
            "incoming_impact",
            "outgoing_impact",
            "architecture",
            "context_relationship",
        ] {
            let projected = project_repository_envelope(
                result_envelope(
                    "repository/query".to_string(),
                    repository_result(intent, "ANSWERED"),
                ),
                AgentResultView::Compact,
            );

            assert!(
                !projected.ok,
                "ANSWERED {intent} accepted no answer evidence"
            );
        }

        let mut unselected = repository_result("resolve", "ANSWERED");
        unselected["candidates"] =
            json!([repository_candidate("callable:sample.answer", "answer")]);
        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), unselected),
            AgentResultView::Compact,
        );
        assert!(
            !projected.ok,
            "ANSWERED resolve accepted candidates without a selected identity"
        );

        for intent in [
            "resolve",
            "path",
            "incoming_impact",
            "outgoing_impact",
            "architecture",
        ] {
            let projected = project_repository_envelope(
                result_envelope(
                    "repository/query".to_string(),
                    repository_result(intent, "AMBIGUOUS"),
                ),
                AgentResultView::Compact,
            );

            assert!(
                !projected.ok,
                "AMBIGUOUS {intent} accepted no disambiguation evidence"
            );
        }
    }

    #[test]
    fn definitive_empty_projection_rejects_answer_evidence() {
        let node = repository_node("callable:sample.answer", "answer");
        let cases = [
            ("resolve", "nodes", json!([node.clone()])),
            (
                "resolve",
                "candidates",
                json!([repository_candidate("callable:sample.answer", "answer")]),
            ),
            ("path", "edges", json!([repository_relationship()])),
            (
                "path",
                "paths",
                json!([{
                    "direction": "OUTGOING",
                    "relationKinds": ["CALLS"],
                    "nodes": [node.clone()]
                }]),
            ),
            (
                "incoming_impact",
                "edges",
                json!([repository_relationship()]),
            ),
            (
                "outgoing_impact",
                "edges",
                json!([repository_relationship()]),
            ),
            (
                "architecture",
                "findings",
                json!([repository_finding(node.clone())]),
            ),
            (
                "context_relationship",
                "contextRelations",
                json!([repository_context_relation()]),
            ),
        ];

        for status in ["EMPTY", "QUALIFIED_EMPTY"] {
            for (intent, field, evidence) in &cases {
                let mut result = repository_result(intent, status);
                result[*field] = evidence.clone();
                let projected = project_repository_envelope(
                    result_envelope("repository/query".to_string(), result),
                    AgentResultView::Compact,
                );

                assert!(
                    !projected.ok,
                    "{status} {intent} accepted affirmative {field} evidence"
                );
            }
        }
    }

    #[test]
    fn repository_projection_rejects_status_qualification_contradictions() {
        let mut empty_without_negative_eligibility = repository_result("resolve", "EMPTY");
        empty_without_negative_eligibility["coverage"]["eligibleForCompleteNegative"] =
            json!(false);
        let mut empty_without_eligibility_proof = repository_result("resolve", "EMPTY");
        empty_without_eligibility_proof["coverage"]["eligibilityProven"] = json!(false);
        let mut qualified_empty_with_negative_eligibility =
            repository_result("resolve", "QUALIFIED_EMPTY");
        qualified_empty_with_negative_eligibility["coverage"]["eligibleForCompleteNegative"] =
            json!(true);
        let mut empty_with_qualification = repository_result("resolve", "EMPTY");
        empty_with_qualification["qualification"] = json!("not actually definitive");
        let mut qualified_empty_without_explanation =
            repository_result("resolve", "QUALIFIED_EMPTY");
        qualified_empty_without_explanation["qualification"] = json!("");

        for (case, result) in [
            (
                "EMPTY without complete-negative eligibility",
                empty_without_negative_eligibility,
            ),
            (
                "EMPTY without eligibility proof",
                empty_without_eligibility_proof,
            ),
            (
                "QUALIFIED_EMPTY with complete-negative eligibility",
                qualified_empty_with_negative_eligibility,
            ),
            ("EMPTY with a qualification", empty_with_qualification),
            (
                "QUALIFIED_EMPTY without an explanation",
                qualified_empty_without_explanation,
            ),
        ] {
            for view in [
                AgentResultView::Compact,
                AgentResultView::Fields(vec![AgentRepositoryField::Summary]),
                AgentResultView::Count,
                AgentResultView::Verbose,
                AgentResultView::Explain,
            ] {
                let projected = project_repository_envelope(
                    result_envelope("repository/query".to_string(), result.clone()),
                    view.clone(),
                );

                assert!(!projected.ok, "{case} accepted by {view:?}");
            }
        }

        for status in ["EMPTY", "QUALIFIED_EMPTY"] {
            let projected = project_repository_envelope(
                result_envelope(
                    "repository/query".to_string(),
                    repository_result("resolve", status),
                ),
                AgentResultView::Compact,
            );

            assert!(projected.ok, "valid {status} projection was rejected");
        }
    }

    #[test]
    fn context_diagnostics_and_ambiguity_remain_valid_projection_evidence() {
        for status in ["EMPTY", "QUALIFIED_EMPTY"] {
            let mut result = repository_result("context_relationship", status);
            result["unresolvedReferences"] = json!(["MissingSymbol"]);
            result["contextFindings"] = json!([{
                "type": "PUBLIC_API_DOCUMENTATION_GAP",
                "targetKey": "callable:sample.missing",
                "targetName": "missing",
                "trigger": "no linked documentation",
                "evidenceClass": "derived"
            }]);
            let projected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result),
                AgentResultView::Compact,
            );

            assert!(projected.ok, "{status} rejected context diagnostics");
        }

        let mut ambiguous = repository_result("context_relationship", "AMBIGUOUS");
        ambiguous["ambiguousReferences"] = json!([{
            "reference": "parse",
            "candidates": [
                repository_node("callable:one.parse", "parse"),
                repository_node("callable:two.parse", "parse")
            ],
            "truncated": false
        }]);
        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), ambiguous),
            AgentResultView::Compact,
        );

        assert!(projected.ok, "rejected context ambiguity evidence");
    }

    #[test]
    fn repository_projection_preserves_derivations_and_every_continuation() {
        let derivation = json!({
            "rule": "gradle_project_dependency",
            "facts": {"sourceProject": ":app", "targetProject": ":core"}
        });
        let mut relation = repository_context_relation();
        relation["derivation"] = derivation.clone();
        let mut context_result = repository_result("context_relationship", "ANSWERED");
        context_result["contextRelations"] = json!([relation]);

        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), context_result),
            AgentResultView::Compact,
        )
        .result
        .expect("context projection");

        assert_eq!(
            projected["context"]["relations"][0]["derivation"],
            derivation
        );

        let relationship_with_continuation = |continuation| {
            let mut relationship = repository_relationship();
            relationship["evidenceTruncated"] = json!(true);
            relationship["evidenceContinuation"] = json!(continuation);
            relationship
        };
        let mut truncated_result = repository_result("outgoing_impact", "ANSWERED");
        truncated_result["truncated"] = json!(true);
        truncated_result["continuation"] = json!("traversal-next");
        truncated_result["edges"] = json!([
            relationship_with_continuation("evidence-b"),
            relationship_with_continuation("evidence-a"),
            relationship_with_continuation("evidence-b")
        ]);

        let compact = project_repository_envelope(
            result_envelope("repository/query".to_string(), truncated_result.clone()),
            AgentResultView::Compact,
        )
        .result
        .expect("compact continuation projection");
        assert_eq!(compact["continuation"], "traversal-next");
        assert_eq!(
            compact["continuations"],
            json!(["evidence-a", "evidence-b"])
        );

        let selected = project_repository_envelope(
            result_envelope("repository/query".to_string(), truncated_result.clone()),
            AgentResultView::Fields(vec![AgentRepositoryField::Continuation]),
        )
        .result
        .expect("selected continuation projection");
        assert_eq!(selected["continuation"], "traversal-next");
        assert_eq!(
            selected["continuations"],
            json!(["evidence-a", "evidence-b"])
        );
        assert!(selected.get("relationships").is_none(), "{selected}");
        assert!(selected.get("edges").is_none(), "{selected}");

        truncated_result["truncated"] = json!(false);
        let invalid = project_repository_envelope(
            result_envelope("repository/query".to_string(), truncated_result),
            AgentResultView::Compact,
        );
        assert!(!invalid.ok, "untruncated result accepted continuations");
    }

    fn repository_result(intent: &str, status: &str) -> Value {
        let complete = status != "QUALIFIED_EMPTY";
        json!({
            "type": "KAST_REPOSITORY_QUERY_RESULT",
            "canonicalResultModel": true,
            "status": status,
            "question": "repository question",
            "intent": intent,
            "queryPlan": {},
            "workspaceIdentity": {"canonicalRoot": "/workspace"},
            "generation": 1,
            "inventoryGeneration": 1,
            "graphGeneration": 1,
            "scope": {},
            "coverage": {
                "complete": complete,
                "eligibleForCompleteNegative": complete,
                "total": 1,
                "indexed": usize::from(complete),
                "excluded": usize::from(!complete),
                "failed": 0,
                "stale": 0,
                "accounted": 1,
                "eligibilityProven": true,
                "pendingUpdateCount": 0
            },
            "appliedFilters": {},
            "bounds": {"depth": 2, "results": 10, "evidence": 1},
            "ordering": "canonicalKey ascending",
            "truncated": false,
            "qualification": (!complete).then_some("scope coverage is incomplete"),
            "schemaVersion": SCHEMA_VERSION,
            "identityCollisions": 0
        })
    }

    fn repository_node(canonical_key: &str, name: &str) -> Value {
        json!({
            "canonicalKey": canonical_key,
            "kind": "FUNCTION",
            "name": name,
            "fqName": format!("sample.{name}"),
            "path": "src/main/kotlin/sample.kt",
            "gradleProjects": ["gradle:/workspace#:app"],
            "sourceSets": ["main"],
            "declarationRange": {"startOffset": 0, "endOffset": 1, "line": 1}
        })
    }

    fn repository_candidate(canonical_key: &str, name: &str) -> Value {
        let mut candidate = repository_node(canonical_key, name);
        candidate["rank"] = json!(1);
        candidate["matchScore"] = json!(1);
        candidate
    }

    fn repository_relationship() -> Value {
        json!({
            "sourceKey": "callable:sample.source",
            "sourceName": "source",
            "targetKey": "callable:sample.target",
            "targetName": "target",
            "kind": "CALLS",
            "direction": "OUTGOING",
            "context": "CALL",
            "occurrenceCount": 1,
            "occurrences": [],
            "evidenceClass": "compiler",
            "evidenceTruncated": false
        })
    }

    fn repository_finding(node: Value) -> Value {
        json!({
            "rank": 1,
            "type": "ARCHITECTURE_HUB",
            "name": "hub",
            "summary": "hub finding",
            "projection": "call_graph",
            "metric": "degree",
            "trigger": {},
            "graphGeneration": 1,
            "representativeSymbols": [node.clone()],
            "supportingSubgraph": {
                "nodes": [node],
                "edges": [],
                "truncated": false
            },
            "relationComposition": {},
            "evidenceClass": "derived",
            "derivation": {},
            "relationTypes": [],
            "scope": {}
        })
    }

    fn repository_context_relation() -> Value {
        json!({
            "sourcePath": "README.md",
            "sourceKind": "markdown",
            "targetKey": "callable:sample.answer",
            "targetName": "answer",
            "kind": "DOCUMENTS",
            "direction": "INCOMING",
            "sourceLocation": {"line": 1, "startOffset": 0, "endOffset": 1},
            "evidenceClass": "extracted"
        })
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
