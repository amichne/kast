fn exact_rename_plan_result() -> Value {
    let declaration_preimage = "\u{feff}a\r\nfun greet() = \"😀\"\nc\rd".as_bytes();
    let declaration_postimage = "\u{feff}a\r\nfun welcome() = \"😀\"\nc\rd".as_bytes();
    let usage_preimage = b"fun use() = greet()\n";
    let usage_postimage = b"fun use() = welcome()\n";
    let target = json!({
        "fqName": "sample.greet",
        "kind": "FUNCTION",
        "declarationFile": "/workspace/src/Sample.kt",
        "declarationStartOffset": 6
    });
    json!({
        "type": "KAST_AGENT_RENAME_PLAN",
        "applyRequired": true,
        "request": {
            "method": "symbol/rename",
            "params": {
                "type": "RENAME_BY_SYMBOL_REQUEST",
                "symbol": "sample.greet",
                "newName": "welcome"
            }
        },
        "preview": {
            "edits": [
                {
                    "filePath": "/workspace/src/Sample.kt",
                    "startOffset": 6,
                    "endOffset": 11,
                    "newText": "welcome"
                },
                {
                    "filePath": "/workspace/src/Usage.kt",
                    "startOffset": 12,
                    "endOffset": 17,
                    "newText": "welcome"
                }
            ],
            "fileHashes": [
                {
                    "filePath": "/workspace/src/Sample.kt",
                    "hash": exact_file_sha256(declaration_preimage)
                },
                {
                    "filePath": "/workspace/src/Usage.kt",
                    "hash": exact_file_sha256(usage_preimage)
                }
            ],
            "affectedFiles": [
                "/workspace/src/Sample.kt",
                "/workspace/src/Usage.kt"
            ],
            "proof": {
                "target": target,
                "requiredGeneration": 7,
                "evidence": {
                    "type": "COMPLETE",
                    "cardinality": {"type": "EXACT", "totalCount": 1},
                    "coverage": {
                        "type": "COMPLETE",
                        "identity": "COMPLETE",
                        "projectScope": "COMPLETE",
                        "sourceSetScope": "COMPLETE",
                        "indexFreshness": "COMPLETE",
                        "backend": "COMPLETE",
                        "requestedFamily": "COMPLETE",
                        "limitations": []
                    }
                },
                "occurrences": [{
                    "reference": {
                        "location": {
                            "filePath": "/workspace/src/Usage.kt",
                            "startOffset": 12,
                            "endOffset": 17,
                            "startLine": 3,
                            "startColumn": 9,
                            "preview": "greet()"
                        },
                        "containingSymbol": {"type": "TOP_LEVEL"}
                    },
                    "resolvedTarget": target,
                    "provenance": "COMPILER"
                }]
            },
            "fileImages": [
                {
                    "filePath": "/workspace/src/Sample.kt",
                    "preimage": {
                        "contentBase64": STANDARD_BASE64.encode(declaration_preimage),
                        "sha256": exact_file_sha256(declaration_preimage)
                    },
                    "postimage": {
                        "contentBase64": STANDARD_BASE64.encode(declaration_postimage),
                        "sha256": exact_file_sha256(declaration_postimage)
                    }
                },
                {
                    "filePath": "/workspace/src/Usage.kt",
                    "preimage": {
                        "contentBase64": STANDARD_BASE64.encode(usage_preimage),
                        "sha256": exact_file_sha256(usage_preimage)
                    },
                    "postimage": {
                        "contentBase64": STANDARD_BASE64.encode(usage_postimage),
                        "sha256": exact_file_sha256(usage_postimage)
                    }
                }
            ],
            "schemaVersion": 6
        }
    })
}

#[test]
fn rename_plan_projection_rejects_proofless_limited_malformed_and_inconsistent_evidence() {
    let valid = project_mutation_envelope(
        result_envelope("agent/rename".to_string(), exact_rename_plan_result()),
        AgentResultView::Compact,
    );
    let valid_result = valid.result.expect("valid rename projection");
    assert_eq!(
        valid_result["plan"]["preview"]["proof"]["requiredGeneration"],
        7
    );
    assert_eq!(
        valid_result["plan"]["preview"]["proof"]["occurrences"][0]["provenance"],
        "COMPILER"
    );

    let mut proofless = exact_rename_plan_result();
    proofless["preview"]
        .as_object_mut()
        .expect("preview object")
        .remove("proof");

    let mut limited = exact_rename_plan_result();
    limited["preview"]["proof"]["evidence"] = json!({
        "type": "LIMITED",
        "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 1},
        "coverage": {
            "type": "LIMITED",
            "identity": "COMPLETE",
            "projectScope": "COMPLETE",
            "sourceSetScope": "COMPLETE",
            "indexFreshness": "COMPLETE",
            "backend": "PARTIAL",
            "requestedFamily": "PARTIAL",
            "limitations": ["BACKEND_INCOMPLETE"]
        }
    });

    let mut limited_coverage = exact_rename_plan_result();
    limited_coverage["preview"]["proof"]["evidence"]["coverage"] = json!({
        "type": "LIMITED",
        "identity": "COMPLETE",
        "projectScope": "COMPLETE",
        "sourceSetScope": "COMPLETE",
        "indexFreshness": "COMPLETE",
        "backend": "PARTIAL",
        "requestedFamily": "PARTIAL",
        "limitations": ["BACKEND_INCOMPLETE"]
    });

    let mut malformed = exact_rename_plan_result();
    malformed["preview"]["proof"]["requiredGeneration"] = json!(-1);

    let mut unknown_nested_field = exact_rename_plan_result();
    unknown_nested_field["preview"]["proof"]["target"]["selectorHandle"] =
        json!("opaque-but-not-part-of-symbol-identity");

    let mut inconsistent_cardinality = exact_rename_plan_result();
    inconsistent_cardinality["preview"]["proof"]["evidence"]["cardinality"]["totalCount"] =
        json!(2);

    let mut inconsistent_target = exact_rename_plan_result();
    inconsistent_target["preview"]["proof"]["occurrences"][0]["resolvedTarget"]["fqName"] =
        json!("sample.other");

    let mut inconsistent_edits = exact_rename_plan_result();
    inconsistent_edits["preview"]["edits"][1]["startOffset"] = json!(32);

    let mut missing_file_images = exact_rename_plan_result();
    missing_file_images["preview"]
        .as_object_mut()
        .expect("preview object")
        .remove("fileImages");

    let mut malformed_base64 = exact_rename_plan_result();
    malformed_base64["preview"]["fileImages"][0]["preimage"]["contentBase64"] = json!("not base64");

    let mut duplicate_file_image = exact_rename_plan_result();
    let duplicate = duplicate_file_image["preview"]["fileImages"][0].clone();
    duplicate_file_image["preview"]["fileImages"]
        .as_array_mut()
        .expect("file images")
        .push(duplicate);

    let mut unknown_image_field = exact_rename_plan_result();
    unknown_image_field["preview"]["fileImages"][0]["source"] = json!("untrusted");

    let mut legacy_preimage_mismatch = exact_rename_plan_result();
    legacy_preimage_mismatch["preview"]["fileHashes"][0]["hash"] = json!("0".repeat(64));

    let mut unchanged_image = exact_rename_plan_result();
    let unchanged_preimage = unchanged_image["preview"]["fileImages"][0]["preimage"].clone();
    unchanged_image["preview"]["fileImages"][0]["postimage"] = unchanged_preimage;

    let mut valid_hash_but_inconsistent_postimage = exact_rename_plan_result();
    let unrelated = b"unrelated but internally hashed bytes\n";
    valid_hash_but_inconsistent_postimage["preview"]["fileImages"][0]["postimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(unrelated),
        "sha256": exact_file_sha256(unrelated)
    });

    for (case, result) in [
        ("proof-less", proofless),
        ("limited", limited),
        ("limited-coverage", limited_coverage),
        ("malformed", malformed),
        ("unknown-nested-field", unknown_nested_field),
        ("cardinality-inconsistent", inconsistent_cardinality),
        ("target-inconsistent", inconsistent_target),
        ("edit-inconsistent", inconsistent_edits),
        ("missing-file-images", missing_file_images),
        ("malformed-base64", malformed_base64),
        ("duplicate-file-image", duplicate_file_image),
        ("unknown-image-field", unknown_image_field),
        ("legacy-preimage-mismatch", legacy_preimage_mismatch),
        ("unchanged-image", unchanged_image),
        (
            "postimage-edit-inconsistent",
            valid_hash_but_inconsistent_postimage,
        ),
    ] {
        let projected = project_mutation_envelope(
            result_envelope("agent/rename".to_string(), result),
            AgentResultView::Compact,
        );

        assert!(!projected.ok, "{case} rename proof was accepted");
        assert_eq!(
            projected.error.as_ref().map(|error| error.code.as_str()),
            Some("AGENT_RESULT_INVALID"),
            "{case}"
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
    unselected["candidates"] = json!([repository_candidate("callable:sample.answer", "answer")]);
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
    empty_without_negative_eligibility["coverage"]["eligibleForCompleteNegative"] = json!(false);
    let mut empty_without_eligibility_proof = repository_result("resolve", "EMPTY");
    empty_without_eligibility_proof["coverage"]["eligibilityProven"] = json!(false);
    let mut truncated_empty = repository_result("resolve", "EMPTY");
    truncated_empty["truncated"] = json!(true);
    let mut qualified_empty_with_negative_eligibility =
        repository_result("resolve", "QUALIFIED_EMPTY");
    qualified_empty_with_negative_eligibility["coverage"]["eligibleForCompleteNegative"] =
        json!(true);
    let mut empty_with_qualification = repository_result("resolve", "EMPTY");
    empty_with_qualification["qualification"] = json!("not actually definitive");
    let mut qualified_empty_without_explanation = repository_result("resolve", "QUALIFIED_EMPTY");
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
        ("truncated EMPTY", truncated_empty),
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
