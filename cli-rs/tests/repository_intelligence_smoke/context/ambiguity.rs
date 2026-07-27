#[test]
fn repository_context_ambiguity_preserves_exact_candidates() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                  start_offset, end_offset, line)
             VALUES
                 (10, 'callable:z.parse', 1, NULL, 'FUNCTION', 'parse', 'z.parse',
                  'z.parse|-||kotlin.String|0', 500, 510, 50)",
            [],
        )
        .expect("third colliding context target");
    let question = "Resolve parse context.";
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "ambiguous-context-target",
        "method": "repository/query",
        "params": {
            "question": question,
            "intent": "context_relationship",
            "scope": {"language": "kotlin", "sources": ["markdown"]},
            "limits": {"depth": 6, "results": 2, "evidence": 1}
        }
    });

    let (status, canonical) = rpc(&home, &config_home, &workspace, request);
    assert!(status.success(), "{canonical:#}");
    assert_eq!(canonical["result"]["status"], "AMBIGUOUS", "{canonical:#}");
    let canonical_ambiguity = &canonical["result"]["ambiguousReferences"][0];
    assert_eq!(canonical_ambiguity["reference"], "parse", "{canonical:#}");
    assert_eq!(
        canonical_ambiguity["candidates"]
            .as_array()
            .map(|candidates| {
                candidates
                    .iter()
                    .map(|candidate| candidate["canonicalKey"].as_str().expect("canonical key"))
                    .collect::<Vec<_>>()
            }),
        Some(vec![
            "callable:SemanticGraphSha256.parse",
            "callable:other.parse"
        ]),
        "{canonical:#}"
    );
    assert_eq!(canonical_ambiguity["truncated"], true, "{canonical:#}");
    assert_eq!(canonical["result"]["truncated"], true, "{canonical:#}");

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
            "2",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact ambiguous context repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr)
    );
    let compact_raw = String::from_utf8(compact_output.stdout).expect("compact context UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact context TOON");
    assert_eq!(compact["result"]["status"], "AMBIGUOUS", "{compact:#}");
    assert_eq!(
        compact["result"]["context"]["ambiguousReferences"][0]["candidates"]
            .as_array()
            .map(|candidates| {
                candidates
                    .iter()
                    .map(|candidate| candidate["canonicalKey"].as_str().expect("canonical key"))
                    .collect::<Vec<_>>()
            }),
        Some(vec![
            "callable:SemanticGraphSha256.parse",
            "callable:other.parse"
        ]),
        "{compact:#}"
    );
    assert_eq!(compact["result"]["truncated"], true, "{compact:#}");
}
