#[test]
fn repository_context_empty_preserves_unresolved_references() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let question = "Resolve MissingContextSymbol context.";

    let compact_output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact empty context repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr)
    );
    let compact_raw =
        String::from_utf8(compact_output.stdout).expect("compact empty context UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact empty context TOON");
    assert_eq!(
        serde_json::json!({
            "status": compact["result"]["status"],
            "unresolvedReferences": compact["result"]["context"]["unresolvedReferences"]
        }),
        serde_json::json!({
            "status": "EMPTY",
            "unresolvedReferences": ["MissingContextSymbol"]
        }),
        "{compact:#}"
    );

    let selected_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
            "--fields",
            "context",
        ])
        .output()
        .expect("selected empty context repository");
    assert!(
        selected_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&selected_output.stdout),
        String::from_utf8_lossy(&selected_output.stderr)
    );
    let selected: serde_json::Value =
        serde_json::from_slice(&selected_output.stdout).expect("selected empty context JSON");
    assert_eq!(
        selected["result"]["context"]["unresolvedReferences"],
        serde_json::json!(["MissingContextSymbol"]),
        "{selected:#}"
    );
}

#[test]
fn repository_context_qualifies_empty_at_inferred_target_ceiling() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let mut connection = fixture.connection();
    let transaction = connection.transaction().expect("candidate transaction");
    for id in 1000..1201 {
        let name = format!("BetaSemanticGraphSha256Evidence{id}");
        transaction
            .execute(
                "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                  start_offset, end_offset, line)
                 VALUES (?, ?, 1, NULL, 'CLASS', ?, ?, NULL, ?, ?, ?)",
                params![
                    id,
                    format!("class:{name}"),
                    name,
                    format!("sample.{name}"),
                    id * 10,
                    id * 10 + 5,
                    id
                ],
            )
            .expect("higher-ranked context candidate");
    }
    transaction.commit().expect("candidate commit");
    std::fs::create_dir_all(workspace.join("docs")).expect("context fixture directory");
    std::fs::write(
        workspace.join("docs/compiler-evidence.md"),
        "# Compiler evidence\n\nSemanticGraphSha256 is the compiler-backed digest model.\n",
    )
    .expect("context fixture document");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "inferred-target-past-ceiling",
            "method": "repository/query",
            "params": {
                "question": "Which exact Kotlin model carries semantic graph hashing evidence?",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["markdown"]},
                "limits": {"depth": 6, "results": 10, "evidence": 1}
            }
        }),
    );

    assert_eq!(
        serde_json::json!({
            "commandSucceeded": status.success(),
            "status": response["result"]["status"],
            "coverageComplete": response["result"]["coverage"]["complete"],
            "truncated": response["result"]["truncated"],
            "contextRelations": response["result"]["contextRelations"],
            "qualified": response["result"]["qualification"]
                .as_str()
                .is_some_and(|qualification| qualification.contains("bounded"))
        }),
        serde_json::json!({
            "commandSucceeded": true,
            "status": "QUALIFIED_EMPTY",
            "coverageComplete": true,
            "truncated": true,
            "contextRelations": [],
            "qualified": true
        }),
        "{response:#}"
    );
}

#[test]
fn repository_context_rejects_substring_only_symbol_match() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
              start_offset, end_offset, line)
             VALUES
             (30, 'class:App', 1, NULL, 'CLASS', 'App', 'sample.App', NULL, 500, 510, 50)",
            [],
        )
        .expect("short-name context target");
    std::fs::create_dir_all(workspace.join("docs")).expect("context fixture directory");
    std::fs::write(
        workspace.join("docs/application.md"),
        "# Application\n\nApplication lifecycle documentation.\n",
    )
    .expect("context fixture document");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "substring-only-context",
            "method": "repository/query",
            "params": {
                "question": "Resolve App context.",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["markdown"]},
                "limits": {"depth": 6, "results": 10, "evidence": 1}
            }
        }),
    );

    assert_eq!(
        serde_json::json!({
            "commandSucceeded": status.success(),
            "status": response["result"]["status"],
            "contextRelations": response["result"]["contextRelations"]
        }),
        serde_json::json!({
            "commandSucceeded": true,
            "status": "EMPTY",
            "contextRelations": []
        }),
        "{response:#}"
    );
}
