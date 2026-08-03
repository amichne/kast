#[test]
fn handle_backed_stale_relationship_preserves_known_minimum_and_limitations() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.stale-relationship";
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("stale-evidence.sock"),
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "CURSOR_STALE",
                "selector": {
                    "fqName": "sample.Service.run",
                    "declarationFile": declaration_file,
                    "declarationStartOffset": 42,
                    "kind": "FUNCTION"
                },
                "reason": "GENERATION_CHANGED",
                "evidence": generation_changed_evidence(2),
                "schemaVersion": api_schema_version()
            }),
        )],
    );

    let stdout = run_agent_json(
        &home,
        &config,
        [
            "callers",
            "--selector-handle",
            selector_handle,
            "--count",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(stdout["result"]["outcome"], "CURSOR_STALE");
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 2})
    );
    assert_eq!(stdout["result"]["coverage"]["indexFreshness"], "STALE");
    assert_eq!(
        stdout["result"]["limitations"],
        serde_json::json!(["GENERATION_CHANGED"])
    );
    backend.join().expect("stale backend");
}

#[test]
fn impact_requires_the_reusable_exact_selector_and_bounded_controls() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");

    for args in [
        vec!["agent", "impact", "--symbol", "sample.Service"],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--limit",
            "0",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--limit",
            "201",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--depth",
            "0",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--depth",
            "9",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--page-token",
            "not-an-impact-token",
        ],
    ] {
        let output = kast(&home, &config)
            .args(args)
            .output()
            .expect("invalid impact command");
        assert_eq!(
            output.status.code(),
            Some(2),
            "stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[test]
fn selector_handle_drives_impact_without_position_resolution() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    seed_high_cardinality_impact(&workspace, "lib.Foo", 12);
    let declaration_file =
        std::fs::canonicalize(workspace.join("lib/Foo.kt")).expect("impact declaration");
    let selector_handle = "ksh1.test-impact-selector-handle";
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("selector-handle-impact.sock"),
        vec![(
            "selector/identity",
            serde_json::json!({
                "type": "AVAILABLE",
                "identity": relation_identity("lib.Foo", "CLASS", &declaration_file, 1),
                "schemaVersion": api_schema_version()
            }),
        )],
    );

    let result = run_agent_json(
        &home,
        &config,
        [
            "impact",
            "--selector-handle",
            selector_handle,
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(result["result"]["query"]["symbol"], "lib.Foo");
    assert_eq!(result["result"]["nodes"].as_array().map(Vec::len), Some(4));
    let page_token = result["result"]["nextPageToken"]
        .as_str()
        .expect("handle-bound impact page token")
        .to_string();

    let requests = backend.join().expect("impact identity backend");
    let identity_request = requests
        .iter()
        .find(|request| request["method"] == "selector/identity")
        .expect("selector identity request");
    assert_eq!(
        identity_request["params"]["selectorHandle"],
        selector_handle,
    );
    assert_eq!(identity_request["params"]["family"], "IMPACT");
    assert!(
        requests
            .iter()
            .all(|request| request["method"] != "raw/resolve"),
        "handle impact must not perform position resolution: {requests:?}",
    );

    let mismatched = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "impact",
            "--selector-handle",
            "ksh1.other-impact-selector-handle",
            "--page-token",
            &page_token,
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("mismatched handle impact token");
    assert_eq!(mismatched.status.code(), Some(1));
    let mismatch: serde_json::Value =
        serde_json::from_slice(&mismatched.stdout).expect("impact mismatch JSON");
    assert_eq!(mismatch["error"]["code"], "IMPACT_PAGE_TOKEN_MISMATCH");
}

#[test]
fn selector_handle_impact_preserves_rejection_before_sql() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("selector-handle-impact-rejected.sock"),
        vec![(
            "selector/identity",
            serde_json::json!({
                "type": "SELECTOR_HANDLE_REJECTED",
                "reason": "STALE",
                "recovery": "RESOLVE_AGAIN",
                "schemaVersion": api_schema_version()
            }),
        )],
    );

    let result = run_agent_json(
        &home,
        &config,
        [
            "impact",
            "--selector-handle",
            "ksh1.stale-impact-selector-handle",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(result["result"]["outcome"], "SELECTOR_HANDLE_REJECTED");
    assert_eq!(result["result"]["reason"], "STALE");
    assert_eq!(result["result"]["recovery"], "RESOLVE_AGAIN");
    backend.join().expect("impact rejection backend");
}

#[test]
fn impact_pages_are_query_bound_and_do_not_overlap() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    seed_high_cardinality_impact(&workspace, "lib.Foo", 500);
    let declaration_file =
        std::fs::canonicalize(workspace.join("lib/Foo.kt")).expect("impact declaration");
    let resolved = serde_json::json!({
        "symbol": {
            "fqName": "lib.Foo",
            "kind": "CLASS",
            "location": {
                "filePath": declaration_file,
                "startOffset": 1,
                "endOffset": 2
            }
        }
    });
    let run_page = |index: usize, page_token: Option<&str>| {
        let socket = temp.path().join(format!("impact-page-{index}.sock"));
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![("raw/resolve", resolved.clone())],
        );
        let mut args = vec![
            "impact",
            "--symbol",
            "lib.Foo",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "class",
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ];
        if let Some(token) = page_token {
            args.extend(["--page-token", token]);
        }
        let result = run_agent_json(&home, &config, args);
        let requests = backend.join().expect("impact backend");
        assert_eq!(
            requests.last().expect("resolve request")["method"],
            "raw/resolve"
        );
        assert_eq!(
            requests.last().expect("resolve request")["params"]["position"]["offset"],
            1
        );
        result
    };

    let first = run_page(1, None);
    let token = first["result"]["nextPageToken"]
        .as_str()
        .expect("first impact page token")
        .to_string();
    let second = run_page(2, Some(&token));
    let first_paths = first["result"]["nodes"]
        .as_array()
        .expect("first nodes")
        .iter()
        .map(|node| node["sourcePath"].as_str().expect("first path"))
        .collect::<std::collections::BTreeSet<_>>();
    let second_paths = second["result"]["nodes"]
        .as_array()
        .expect("second nodes")
        .iter()
        .map(|node| node["sourcePath"].as_str().expect("second path"))
        .collect::<std::collections::BTreeSet<_>>();
    assert!(first_paths.is_disjoint(&second_paths));
    assert_eq!(second["result"]["query"]["offset"], 4);

    let mismatch = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "impact",
            "--symbol",
            "lib.Target",
            "--declaration-file",
            workspace
                .join("lib/Target.kt")
                .to_str()
                .expect("target file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "class",
            "--page-token",
            &token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("mismatched impact token");
    assert_eq!(mismatch.status.code(), Some(1));
    let mismatch: serde_json::Value =
        serde_json::from_slice(&mismatch.stdout).expect("impact mismatch json");
    assert_eq!(mismatch["error"]["code"], "IMPACT_PAGE_TOKEN_MISMATCH");
}
