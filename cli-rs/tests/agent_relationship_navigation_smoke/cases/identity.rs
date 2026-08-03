#[test]
fn selector_handle_rejections_stay_distinct_and_actionable_in_cli_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let selector_handle = "ksh1.test-rejected-selector-handle";
    let cases = [
        (
            "references",
            "symbol/references",
            "TAMPERED",
            "RESOLVE_AGAIN",
        ),
        (
            "callers",
            "symbol/callers",
            "WRONG_WORKSPACE",
            "RESOLVE_IN_CURRENT_WORKSPACE",
        ),
        (
            "references",
            "symbol/references",
            "WRONG_BACKEND",
            "RESOLVE_WITH_ACTIVE_BACKEND",
        ),
        ("callers", "symbol/callers", "STALE", "RESOLVE_AGAIN"),
        (
            "references",
            "symbol/references",
            "FAMILY_NOT_ALLOWED",
            "CHOOSE_COMPATIBLE_OPERATION",
        ),
        (
            "callers",
            "symbol/callers",
            "UNAVAILABLE",
            "USE_EXPLICIT_SELECTOR",
        ),
    ];

    for (index, (command_name, method, reason, recovery)) in cases.into_iter().enumerate() {
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &temp
                .path()
                .join(format!("selector-handle-rejection-{index}.sock")),
            vec![(
                method,
                serde_json::json!({
                    "type": "SELECTOR_HANDLE_REJECTED",
                    "reason": reason,
                    "recovery": recovery,
                    "schemaVersion": api_schema_version()
                }),
            )],
        );
        let result = run_agent_json(
            &home,
            &config,
            [
                command_name,
                "--selector-handle",
                selector_handle,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ],
        );
        assert_eq!(result["result"]["outcome"], "SELECTOR_HANDLE_REJECTED");
        assert_eq!(result["result"]["reason"], reason);
        assert_eq!(result["result"]["recovery"], recovery);
        assert_eq!(result["result"]["ok"], true);
        assert!(result.get("error").is_none(), "projection={result}");

        let requests = backend.join().expect("rejection backend");
        assert_eq!(requests[2]["method"], method);
        assert_eq!(requests[2]["params"]["selectorHandle"], selector_handle);
    }
}

#[test]
fn exact_identity_drives_references_callers_continuation_and_impact_without_rediscovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    let declaration_file =
        std::fs::canonicalize(workspace.join("lib/Bar.kt")).expect("declaration file");
    let selector = relation_identity("lib.Bar", "FUNCTION", &declaration_file, 1);

    let resolve_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-resolve.sock"),
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "lib.Bar",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 1,
                        "endOffset": 2
                    }
                }
            }),
        )],
    );
    let resolved_json = run_agent_json(
        &home,
        &config,
        [
            "symbol",
            "--query",
            "lib.Bar",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(resolved_json["result"]["identity"], selector);
    let mut semantic_requests = resolve_backend.join().expect("resolve backend");

    let reference_handle = "00000000-0000-4000-8000-000000000337";
    let first_reference_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-references-first.sock"),
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "references": [{
                    "location": relation_location(&workspace.join("app/A.kt"), 30),
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "evidence": resumable_relationship_evidence(2),
                "page": {
                    "truncated": true,
                    "nextPageToken": reference_handle
                },
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    let references_json = run_agent_json(
        &home,
        &config,
        [
            "references",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(references_json["result"]["outcome"], "AVAILABLE");
    let reference_page_token = references_json["result"]["page"]["nextPageToken"]
        .as_str()
        .expect("reference page token")
        .to_string();
    semantic_requests.extend(
        first_reference_backend
            .join()
            .expect("first reference backend"),
    );

    let second_reference_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-references-second.sock"),
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "references": [],
                "evidence": complete_relationship_evidence(1),
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    run_agent_json(
        &home,
        &config,
        [
            "references",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--limit",
            "4",
            "--page-token",
            &reference_page_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    semantic_requests.extend(
        second_reference_backend
            .join()
            .expect("second reference backend"),
    );

    let caller_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-callers.sock"),
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "records": [{
                    "relation": "CALLER",
                    "relatedSymbol": relation_identity(
                        "app.A.render",
                        "FUNCTION",
                        &workspace.join("app/A.kt"),
                        10,
                    ),
                    "callSite": relation_location(&workspace.join("app/A.kt"), 30),
                    "depth": 1,
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "page": exact_relation_page(1),
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    let callers_json = run_agent_json(
        &home,
        &config,
        [
            "callers",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(callers_json["result"]["outcome"], "AVAILABLE");
    semantic_requests.extend(caller_backend.join().expect("callers backend"));

    let impact_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-impact.sock"),
        vec![(
            "raw/resolve",
            serde_json::json!({
                "symbol": {
                    "fqName": "lib.Bar",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 1,
                        "endOffset": 2
                    }
                }
            }),
        )],
    );
    let impact_json = run_agent_json(
        &home,
        &config,
        [
            "impact",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(impact_json["result"]["outcome"], "DEGRADED");
    assert_eq!(
        impact_json["result"]["reason"],
        "IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE"
    );
    semantic_requests.extend(impact_backend.join().expect("impact backend"));

    let public_methods = semantic_requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
        .collect::<Vec<_>>();
    assert_eq!(
        public_methods,
        [
            "symbol/resolve",
            "symbol/references",
            "symbol/references",
            "symbol/callers",
            "raw/resolve",
        ]
    );
    assert!(semantic_requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("symbol/query" | "workspace/search" | "workspace/symbols")
        )
    }));
    for request in semantic_requests.iter().filter(|request| {
        matches!(
            request["method"].as_str(),
            Some("symbol/references" | "symbol/callers")
        )
    }) {
        assert!(
            request["params"]["maxResults"]
                .as_u64()
                .is_some_and(|limit| limit <= 4)
        );
    }
}
