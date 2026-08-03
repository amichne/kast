#[test]
fn impact_stops_before_sql_for_mismatched_and_unsupported_subjects() {
    for (index, kind, resolved_offset, expected_outcome) in [
        (0usize, "CLASS", 16u64, "SUBJECT_IDENTITY_MISMATCH"),
        (1usize, "PARAMETER", 15u64, "UNSUPPORTED_SUBJECT_KIND"),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let declaration_file = workspace.join("Service.kt");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
        let canonical = std::fs::canonicalize(&declaration_file).expect("canonical source");
        let socket = temp.path().join(format!("impact-closed-{index}.sock"));
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![(
                "raw/resolve",
                serde_json::json!({
                    "symbol": {
                        "fqName": "sample.Service",
                        "kind": kind,
                        "location": {
                            "filePath": canonical,
                            "startOffset": resolved_offset,
                            "endOffset": resolved_offset + 1
                        }
                    }
                }),
            )],
        );
        let result = run_agent_json(
            &home,
            &config,
            [
                "impact",
                "--symbol",
                "sample.Service",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ],
        );
        backend.join().expect("closed impact backend");
        assert_eq!(result["result"]["outcome"], expected_outcome, "{result}");
    }
}

#[test]
fn traversal_tokens_reject_wrong_query_or_relation_before_runtime_io() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let handle = "rth1_callers_00000000-0000-4000-8000-000000000339";

    for token in [
        format!("krp1.callers.000000000000000000000000.traversal.{handle}"),
        format!("krp1.callees.000000000000000000000000.traversal.{handle}"),
    ] {
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args([
                "--output",
                "json",
                "agent",
                "callers",
                "--symbol",
                "sample.Service",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--kind",
                "class",
                "--page-token",
                &token,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("mismatched traversal token");
        assert_eq!(output.status.code(), Some(1));
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("mismatch json");
        assert_eq!(
            stdout["error"]["code"], "RELATION_PAGE_TOKEN_MISMATCH",
            "token={token} output={stdout}",
        );
    }
}

#[test]
fn exact_symbol_returns_one_reusable_anchored_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("indexer.sock");
    let declaration_file = workspace.join("Service.kt");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "sample.Service.run",
                    "kind": "FUNCTION",
                    "containingType": "sample.Service",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 42,
                        "endOffset": 45,
                        "startLine": 3,
                        "startColumn": 5
                    }
                }
            }),
        )],
    );

    let stdout = run_agent_json(
        &home,
        &config,
        [
            "symbol",
            "--query",
            "sample.Service.run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(
        stdout["result"]["identity"],
        serde_json::json!({
            "fqName": "sample.Service.run",
            "kind": "FUNCTION",
            "declarationFile": declaration_file,
            "declarationStartOffset": 42,
            "containingType": "sample.Service"
        })
    );
    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
}

#[test]
fn selector_handle_resolves_once_and_reuses_identity_for_references() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.test-issued-selector-handle";
    let selector = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);

    let resolve_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("selector-handle-resolve.sock"),
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "selectorHandle": selector_handle,
                "symbol": {
                    "fqName": "sample.Service.run",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 42,
                        "endOffset": 45
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
            "sample.Service.run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(resolved_json["result"]["identity"], selector);
    assert_eq!(
        resolved_json["result"]["selectorHandle"], selector_handle,
        "compact exact lookup must expose the backend-issued opaque handle",
    );
    let mut requests = resolve_backend.join().expect("resolve backend");

    let references_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("selector-handle-references.sock"),
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "references": [],
                "evidence": complete_relationship_evidence(0),
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    run_agent_json(
        &home,
        &config,
        [
            "references",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    requests.extend(references_backend.join().expect("references backend"));

    let semantic_requests = requests
        .iter()
        .filter(|request| {
            request["method"]
                .as_str()
                .is_some_and(|method| method.starts_with("symbol/"))
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec!["symbol/resolve", "symbol/references"],
        "selector reuse must not perform fuzzy or exact rediscovery",
    );
    assert_eq!(
        semantic_requests[1]["params"]["selectorHandle"],
        selector_handle,
    );
    assert!(semantic_requests[1]["params"].get("selector").is_none());
}

#[test]
fn selector_handle_drives_all_relationship_commands_without_explicit_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.test-issued-relationship-selector-handle";
    let function = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let interface = relation_identity("sample.Service", "INTERFACE", &declaration_file, 10);
    let cases = vec![
        (
            "callers",
            "symbol/callers",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": function,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": api_schema_version()
            }),
        ),
        (
            "callees",
            "symbol/callers",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": function,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": api_schema_version()
            }),
        ),
        (
            "implementations",
            "symbol/implementations",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": interface,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": api_schema_version()
            }),
        ),
        (
            "hierarchy",
            "symbol/hierarchy",
            vec!["--direction", "both"],
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": interface,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": api_schema_version()
            }),
        ),
    ];

    for (index, (command_name, method, extra_args, response)) in cases.into_iter().enumerate() {
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &temp
                .path()
                .join(format!("selector-handle-{command_name}-{index}.sock")),
            vec![(method, response)],
        );
        let mut args = vec![command_name, "--selector-handle", selector_handle];
        args.extend(extra_args);
        args.extend(["--workspace-root", workspace.to_str().expect("workspace")]);
        run_agent_json(&home, &config, args);
        let requests = backend.join().expect("relationship backend");
        assert_eq!(requests[2]["method"], method);
        assert_eq!(requests[2]["params"]["selectorHandle"], selector_handle);
        assert!(requests[2]["params"].get("selector").is_none());
    }
}
