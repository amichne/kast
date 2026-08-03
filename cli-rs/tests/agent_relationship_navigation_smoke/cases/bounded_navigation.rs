#[test]
fn compact_references_bound_high_cardinality_output() {
    const TOTAL_REFERENCES: usize = 500;
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let references = (0..4)
        .map(|index| {
            serde_json::json!({
                "location": {
                    "filePath": workspace.join(format!("Client{index}.kt")),
                    "startOffset": index * 10,
                    "endOffset": index * 10 + 7,
                    "startLine": index + 1,
                    "startColumn": 1,
                    "preview": "oversized semantic preview ".repeat(2_000)
                },
                "containingSymbol": {"type": "TOP_LEVEL"}
            })
        })
        .collect::<Vec<_>>();
    let socket = temp.path().join("indexer.sock");
    let backend_token = "00000000-0000-4000-8000-000000000337";
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": {
                    "fqName": "sample.Service",
                    "kind": "CLASS",
                    "declarationFile": declaration_file,
                    "declarationStartOffset": 15
                },
                "references": references,
                "evidence": resumable_relationship_evidence(TOTAL_REFERENCES),
                "page": {"truncated": true, "nextPageToken": backend_token},
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("compact high-cardinality references");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["params"]["maxResults"], 4);
    let raw = String::from_utf8(output.stdout).expect("references utf8");
    let stdout: serde_json::Value = serde_json::from_str(&raw).expect("references json");
    assert_eq!(
        stdout["result"]["records"]
            .as_array()
            .expect("reference records")
            .len(),
        4
    );
    assert_eq!(
        stdout["result"]["page"]["cardinality"]["knownMinimumCount"],
        TOTAL_REFERENCES
    );
    assert!(
        stdout["result"]["records"]
            .as_array()
            .expect("reference records")
            .iter()
            .all(|record| record["location"].get("preview").is_none())
    );
    assert!(raw.lines().count() <= 120, "{} lines", raw.lines().count());
    let tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k tokenizer")
        .encode_with_special_tokens(&raw)
        .len();
    assert!(tokens <= 1_500, "{tokens} compact reference tokens");
}

#[test]
fn remaining_relationship_commands_reach_bounded_compiler_engines() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let canonical_file = std::fs::canonicalize(&declaration_file).expect("canonical source");

    for (index, (command, expected_direction, expected_relation)) in [
        ("callers", "incoming", "CALLER"),
        ("callees", "outgoing", "CALLEE"),
    ]
    .into_iter()
    .enumerate()
    {
        let socket = temp.path().join(format!("indexer-call-{index}.sock"));
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![(
                "symbol/callers",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": relation_identity(
                        "sample.Service.run",
                        "FUNCTION",
                        &canonical_file,
                        15,
                    ),
                    "records": [{
                        "relation": expected_relation,
                        "relatedSymbol": relation_identity(
                            "sample.Client.call",
                            "FUNCTION",
                            &workspace.join("Client.kt"),
                            20,
                        ),
                        "callSite": relation_location(&workspace.join("Client.kt"), 30),
                        "depth": 1,
                        "containingSymbol": {"type": "TOP_LEVEL"}
                    }],
                    "page": exact_relation_page(1),
                    "schemaVersion": api_schema_version()
                }),
            )],
        );
        let stdout = run_agent_json(
            &home,
            &config,
            [
                command,
                "--symbol",
                "sample.Service.run",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--kind",
                "function",
                "--depth",
                "2",
                "--limit",
                "4",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ],
        );
        assert_eq!(stdout["result"]["outcome"], "AVAILABLE");
        assert_eq!(stdout["result"]["relation"], command);
        assert_eq!(
            stdout["result"]["records"][0]["relation"],
            expected_relation
        );
        let requests = backend.join().expect("call backend");
        assert_eq!(
            requests[2]["params"]["selector"]["declarationFile"],
            canonical_file.to_string_lossy().as_ref()
        );
        assert_eq!(
            requests[2]["params"]["selector"]["declarationStartOffset"],
            15
        );
        assert_eq!(requests[2]["params"]["direction"], expected_direction);
        assert_eq!(requests[2]["params"]["depth"], 2);
        assert_eq!(requests[2]["params"]["maxResults"], 4);
    }

    let implementations_socket = temp.path().join("indexer-implementations.sock");
    let implementations_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &implementations_socket,
        vec![(
            "symbol/implementations",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service",
                    "INTERFACE",
                    &canonical_file,
                    15,
                ),
                "records": [{
                    "relation": "IMPLEMENTATION",
                    "implementation": relation_identity(
                        "sample.RealService",
                        "CLASS",
                        &workspace.join("RealService.kt"),
                        10,
                    ),
                    "declarationLocation": relation_location(
                        &workspace.join("RealService.kt"),
                        10,
                    )
                }],
                "page": exact_relation_page(1),
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    let implementations_json = run_agent_json(
        &home,
        &config,
        [
            "implementations",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "interface",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(
        implementations_json["result"]["records"][0]["relation"],
        "IMPLEMENTATION"
    );
    let implementation_requests = implementations_backend
        .join()
        .expect("implementations backend");
    assert_eq!(implementation_requests[2]["params"]["maxResults"], 4);
    assert_eq!(
        implementation_requests[2]["params"]["selector"]["declarationFile"],
        canonical_file.to_string_lossy().as_ref()
    );

    let hierarchy_socket = temp.path().join("indexer-hierarchy.sock");
    let hierarchy_backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &hierarchy_socket,
        vec![(
            "symbol/hierarchy",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service",
                    "INTERFACE",
                    &canonical_file,
                    15,
                ),
                "records": [{
                    "relation": "SUBTYPE",
                    "relatedSymbol": relation_identity(
                        "sample.RealService",
                        "CLASS",
                        &workspace.join("RealService.kt"),
                        10,
                    ),
                    "declarationLocation": relation_location(
                        &workspace.join("RealService.kt"),
                        10,
                    ),
                    "depth": 1
                }],
                "page": exact_relation_page(1),
                "schemaVersion": api_schema_version()
            }),
        )],
    );
    let hierarchy_json = run_agent_json(
        &home,
        &config,
        [
            "hierarchy",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "interface",
            "--direction",
            "subtypes",
            "--depth",
            "2",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(
        hierarchy_json["result"]["records"][0]["relation"],
        "SUBTYPE"
    );
    let hierarchy_requests = hierarchy_backend.join().expect("hierarchy backend");
    assert_eq!(hierarchy_requests[2]["params"]["direction"], "SUBTYPES");
    assert_eq!(hierarchy_requests[2]["params"]["depth"], 2);
    assert_eq!(hierarchy_requests[2]["params"]["maxResults"], 4);
}
