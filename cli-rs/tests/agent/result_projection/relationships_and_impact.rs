#[cfg(any())]
fn symbol_relationships_bound_requests_and_compact_a_143k_token_result() {
    const RELATION_ITEMS: usize = 500;
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let verbose_socket_path = temp.path().join("indexer-verbose.sock");
    let noise = "high cardinality relationship evidence ".repeat(72);
    let references = (0..RELATION_ITEMS)
        .map(|index| {
            json!({
                "filePath": workspace.join(format!("src/Reference{index}.kt")).display().to_string(),
                "startOffset": index,
                "endOffset": index + 1,
                "preview": noise,
            })
        })
        .collect::<Vec<_>>();
    let callers = (0..RELATION_ITEMS)
        .map(|index| {
            json!({
                "symbol": {
                    "fqName": format!("sample.Caller{index}"),
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": workspace.join(format!("src/Caller{index}.kt")).display().to_string(),
                        "startOffset": index,
                        "endOffset": index + 1,
                        "preview": noise,
                    }
                },
                "callSite": {
                    "filePath": workspace.join(format!("src/Caller{index}.kt")).display().to_string(),
                    "startOffset": index,
                    "endOffset": index + 1,
                    "preview": noise,
                },
                "children": []
            })
        })
        .collect::<Vec<_>>();
    let verbose_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &verbose_socket_path,
        vec![
            ("symbol/resolve", oversized_symbol_result(&workspace)),
            (
                "symbol/references",
                json!({
                    "type": "REFERENCES_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxResults": RELATION_ITEMS},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "references": references,
                    "cardinality": {"type": "EXACT", "totalCount": RELATION_ITEMS},
                    "logFile": ""
                }),
            ),
            (
                "symbol/callers",
                json!({
                    "type": "CALLERS_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxTotalCalls": RELATION_ITEMS, "maxChildrenPerNode": RELATION_ITEMS},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "root": {
                        "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                        "children": callers
                    },
                    "stats": {
                        "totalNodes": RELATION_ITEMS + 1,
                        "totalEdges": RELATION_ITEMS,
                        "truncatedNodes": 0,
                        "maxDepthReached": 1,
                        "timeoutReached": false,
                        "maxTotalCallsReached": false,
                        "maxChildrenPerNodeReached": false,
                        "filesVisited": RELATION_ITEMS
                    },
                    "logFile": ""
                }),
            ),
        ],
    );
    let verbose_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Container.target",
            "--references",
            "--callers",
            "incoming",
            "--limit",
            "500",
            "--verbose",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("verbose high-cardinality symbol relationships");
    assert!(
        verbose_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&verbose_output.stdout),
        String::from_utf8_lossy(&verbose_output.stderr),
    );
    let verbose_requests = verbose_backend
        .join()
        .expect("verbose relationship backend");
    assert_eq!(verbose_requests[3]["params"]["maxResults"], RELATION_ITEMS);
    assert_eq!(
        verbose_requests[4]["params"]["maxTotalCalls"],
        RELATION_ITEMS
    );
    assert_eq!(
        verbose_requests[4]["params"]["maxChildrenPerNode"],
        RELATION_ITEMS
    );
    let verbose_raw = String::from_utf8(verbose_output.stdout).expect("verbose utf8 output");
    let verbose_tokens = cl100k_tokens(&verbose_raw);
    assert!(
        verbose_tokens >= 143_000,
        "verbose command must preserve the reviewed 143k-token scenario; measured {verbose_tokens}"
    );

    let compact_socket_path = temp.path().join("indexer-compact.sock");
    let compact_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &compact_socket_path,
        vec![
            ("symbol/resolve", oversized_symbol_result(&workspace)),
            (
                "symbol/references",
                json!({
                    "type": "REFERENCES_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxResults": 4},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "references": references.into_iter().take(4).collect::<Vec<_>>(),
                    "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 4},
                    "page": {
                        "truncated": true,
                        "nextPageToken": "00000000-0000-4000-8000-000000000337"
                    },
                    "logFile": ""
                }),
            ),
            (
                "symbol/callers",
                json!({
                    "type": "CALLERS_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxTotalCalls": 4, "maxChildrenPerNode": 4},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "root": {
                        "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                        "children": callers.into_iter().take(4).collect::<Vec<_>>()
                    },
                    "stats": {
                        "totalNodes": RELATION_ITEMS + 1,
                        "totalEdges": RELATION_ITEMS,
                        "truncatedNodes": RELATION_ITEMS - 4,
                        "maxDepthReached": 1,
                        "timeoutReached": false,
                        "maxTotalCallsReached": true,
                        "maxChildrenPerNodeReached": true,
                        "filesVisited": 4
                    },
                    "logFile": ""
                }),
            ),
        ],
    );
    let compact_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Container.target",
            "--references",
            "--callers",
            "incoming",
            "--limit",
            "500",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("compact high-cardinality symbol relationships");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr),
    );
    let requests = compact_backend
        .join()
        .expect("compact relationship backend");
    let raw = String::from_utf8(compact_output.stdout).expect("compact utf8 output");
    let stdout: Value = serde_json::from_str(&raw).expect("relationship json");
    let relationships = stdout["result"]["relationships"]
        .as_array()
        .expect("relationships");

    assert_eq!(requests[3]["params"]["maxResults"], 4);
    assert_eq!(requests[4]["params"]["maxTotalCalls"], 4);
    assert_eq!(requests[4]["params"]["maxChildrenPerNode"], 4);
    assert_eq!(relationships[0]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(relationships[0]["cardinality"]["knownMinimumCount"], 4);
    assert_eq!(relationships[0]["returnedCount"], 4);
    assert_eq!(relationships[0]["truncated"], true);
    assert_eq!(
        relationships[0]["nextPageToken"],
        "00000000-0000-4000-8000-000000000337"
    );
    assert_eq!(
        relationships[0]["items"]
            .as_array()
            .expect("references")
            .len(),
        4
    );
    assert_eq!(relationships[1]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(
        relationships[1]["cardinality"]["knownMinimumCount"],
        RELATION_ITEMS
    );
    assert_eq!(relationships[1]["returnedCount"], 4);
    assert_eq!(relationships[1]["truncated"], true);
    assert_eq!(
        relationships[1]["items"].as_array().expect("callers").len(),
        4
    );
    assert!(
        relationships[0]["items"][0]["location"]
            .get("preview")
            .is_none()
    );
    assert!(
        relationships[1]["items"][0]["location"]
            .get("preview")
            .is_none()
    );
    assert_output_budget(&raw, SYMBOL_LINE_BUDGET, SYMBOL_TOKEN_BUDGET);
}

#[test]
fn impact_default_is_typed_bounded_and_supports_selected_and_count_views() {
    const HIGH_CARDINALITY_IMPACT_NODES: usize = 500;
    const EXISTING_IMPACT_NODES: usize = 3;
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    seed_high_cardinality_impact(&workspace, "lib.Foo", HIGH_CARDINALITY_IMPACT_NODES);
    let canonical_workspace = std::fs::canonicalize(&workspace).expect("canonical workspace");
    let declaration_file =
        std::fs::canonicalize(workspace.join("lib/Foo.kt")).expect("canonical impact declaration");
    let resolved = json!({
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
    let run_index = std::cell::Cell::new(0usize);

    let run = |view: &[&str]| {
        let index = run_index.get();
        run_index.set(index + 1);
        let socket = temp.path().join(format!("impact-{index}.sock"));
        let backend = spawn_ready_scripted_indexer_backend_for_invocations(
            &home,
            &config_home,
            &workspace,
            &socket,
            1,
            vec![("raw/resolve", resolved.clone())],
        );
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
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
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .args(view)
            .output()
            .expect("agent impact");
        backend.join().expect("impact backend");
        output
    };

    let output = run(&[]);
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let raw = String::from_utf8(output.stdout).expect("impact utf8");
    let stdout: Value = serde_json::from_str(&raw).expect("impact json");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_IMPACT_RESULT");
    assert_eq!(stdout["result"]["query"]["symbol"], "lib.Foo");
    assert_eq!(stdout["result"]["query"]["depth"], 3);
    assert_eq!(stdout["result"]["query"]["limit"], 4);
    assert_eq!(
        stdout["result"]["query"]["workspaceRoot"],
        canonical_workspace.display().to_string()
    );
    assert_eq!(
        stdout["result"]["totalCount"],
        HIGH_CARDINALITY_IMPACT_NODES + EXISTING_IMPACT_NODES
    );
    assert_eq!(stdout["result"]["returnedCount"], 4);
    assert_eq!(stdout["result"]["truncated"], true);
    assert_eq!(
        stdout["result"]["nodes"].as_array().expect("nodes").len(),
        4
    );
    assert!(stdout["result"].get("confidence").is_some(), "{stdout}");
    assert_output_budget(&raw, IMPACT_LINE_BUDGET, IMPACT_TOKEN_BUDGET);

    let selected: Value = serde_json::from_slice(&run(&["--fields", "query,confidence"]).stdout)
        .expect("selected impact json");
    assert_eq!(selected["result"]["type"], "KAST_AGENT_IMPACT_SELECTION");
    assert!(selected["result"].get("query").is_some(), "{selected}");
    assert!(selected["result"].get("confidence").is_some(), "{selected}");
    assert!(selected["result"].get("nodes").is_none(), "{selected}");

    let count: Value =
        serde_json::from_slice(&run(&["--count"]).stdout).expect("count impact json");
    assert_eq!(
        count["result"]["type"], "KAST_AGENT_IMPACT_COUNT",
        "{count}"
    );
    assert_eq!(
        count["result"]["totalCount"],
        HIGH_CARDINALITY_IMPACT_NODES + EXISTING_IMPACT_NODES
    );
    assert_eq!(count["result"]["returnedCount"], 4);
    assert!(count["result"].get("nodes").is_none(), "{count}");

    for detailed_view in ["--verbose", "--explain"] {
        let detailed: Value =
            serde_json::from_slice(&run(&[detailed_view]).stdout).expect("detailed impact json");
        assert_eq!(detailed["result"]["type"], "KAST_AGENT_COMMAND");
        assert_eq!(
            detailed["result"]["steps"][0]["result"]["type"],
            "METRICS_SUCCESS"
        );
        assert_eq!(
            detailed["result"]["steps"][0]["result"]["totalCount"],
            HIGH_CARDINALITY_IMPACT_NODES + EXISTING_IMPACT_NODES
        );
    }
}
