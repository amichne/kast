#[test]
fn exact_symbol_does_not_publish_a_partial_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("idea.sock");
    let backend = spawn_scripted_idea_backend(
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
                    "location": {
                        "filePath": workspace.join("Service.kt")
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
    assert_eq!(stdout["result"]["outcome"], "IDENTITY_ANCHOR_UNAVAILABLE");
    assert!(stdout["result"]["identity"].is_null(), "{stdout}");
    backend.join().expect("scripted backend");
}

#[test]
fn references_send_the_exact_anchor_and_project_occurrence_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("idea.sock");
    let declaration_file = workspace.join("src/Service.kt");
    std::fs::create_dir_all(declaration_file.parent().expect("source parent"))
        .expect("source directory");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let reference_file = workspace.join("src/Client.kt");
    let canonical_declaration_file =
        std::fs::canonicalize(&declaration_file).expect("canonical declaration file");
    let backend_token = "00000000-0000-4000-8000-000000000337";
    let backend = spawn_scripted_idea_backend(
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
                "references": [{
                    "location": {
                        "filePath": reference_file,
                        "startOffset": 20,
                        "endOffset": 27,
                        "startLine": 2,
                        "startColumn": 5
                    },
                    "containingSymbol": {
                        "type": "KNOWN",
                        "symbol": {
                            "fqName": "sample.Client.run",
                            "kind": "FUNCTION",
                            "declarationFile": reference_file,
                            "declarationStartOffset": 10,
                            "containingType": "sample.Client"
                        }
                    }
                }],
                "evidence": resumable_relationship_evidence(2),
                "page": {
                    "truncated": true,
                    "nextPageToken": backend_token
                },
                "schemaVersion": 5
            }),
        )],
    );

    let stdout = run_agent_json(
        &home,
        &config,
        [
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(stdout["result"]["outcome"], "AVAILABLE");
    assert_eq!(stdout["result"]["relation"], "references");
    assert_eq!(stdout["result"]["subject"]["fqName"], "sample.Service");
    assert_eq!(stdout["result"]["records"][0]["relation"], "REFERENCE");
    assert_eq!(
        stdout["result"]["records"][0]["containingSymbol"]["symbol"]["fqName"],
        "sample.Client.run"
    );
    let public_token = stdout["result"]["page"]["nextPageToken"]
        .as_str()
        .expect("public page token")
        .to_string();
    assert!(public_token.starts_with("krp1.references."));
    assert!(public_token.ends_with(&format!(".reference.{backend_token}")));

    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/references");
    assert_eq!(
        requests[2]["params"]["selector"],
        serde_json::json!({
            "fqName": "sample.Service",
            "declarationFile": canonical_declaration_file,
            "declarationStartOffset": 15,
            "kind": "CLASS"
        })
    );
    assert_eq!(requests[2]["params"]["includeDeclaration"], false);
    assert_eq!(requests[2]["params"]["maxResults"], 4);
    assert!(requests[2]["params"]["pageToken"].is_null());

    let continuation_socket = temp.path().join("idea-continuation.sock");
    let continuation_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &continuation_socket,
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
                "references": [],
                "evidence": complete_relationship_evidence(1),
                "schemaVersion": 5
            }),
        )],
    );
    run_agent_json(
        &home,
        &config,
        [
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--page-token",
            &public_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    let continuation_requests = continuation_backend.join().expect("continuation backend");
    assert_eq!(
        continuation_requests[2]["params"]["pageToken"],
        backend_token
    );

    let mismatch = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.OtherService",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--page-token",
            &public_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("reference token mismatch");
    assert_eq!(mismatch.status.code(), Some(1));
    let mismatch: serde_json::Value =
        serde_json::from_slice(&mismatch.stdout).expect("mismatch json");
    assert_eq!(mismatch["error"]["code"], "RELATION_PAGE_TOKEN_MISMATCH");
}

#[test]
fn references_preserve_a_zero_known_minimum_while_search_remains_resumable() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let backend_token = "00000000-0000-4000-8000-000000000337";
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("idea.sock"),
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
                "references": [],
                "evidence": resumable_relationship_evidence(0),
                "page": {
                    "truncated": true,
                    "nextPageToken": backend_token
                },
                "schemaVersion": 5
            }),
        )],
    );

    let stdout = run_agent_json(
        &home,
        &config,
        [
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(stdout["result"]["page"]["returnedCount"], 0);
    assert_eq!(
        stdout["result"]["page"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 0}),
    );
    assert_eq!(stdout["result"]["coverage"]["type"], "RESUMABLE");
    assert!(stdout["result"]["page"]["truncated"].as_bool().unwrap());
    backend.join().expect("scripted backend");
}
