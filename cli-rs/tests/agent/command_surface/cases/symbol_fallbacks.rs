use super::*;

#[test]
fn agent_symbol_discovery_requests_lexical_mode_explicitly() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Foo",
            "--mode",
            "discovery",
            "--explain",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("discovery");

    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("discovery json");
    assert_eq!(stdout["result"]["mode"], "discovery");
    assert_eq!(stdout["result"]["outcome"]["type"], "DISCOVERED");
    assert_eq!(stdout["result"]["outcome"]["source"], "fuzzy");
    assert_eq!(
        stdout["result"]["request"]["params"]["modes"],
        json!(["lexical"])
    );
}

#[test]
fn agent_symbol_uses_indexed_exact_only_when_compiler_is_unavailable() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Parser",
            "--explain",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("indexed exact fallback");

    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("fallback json");
    assert_eq!(stdout["result"]["outcome"]["type"], "AMBIGUOUS");
    assert_eq!(stdout["result"]["outcome"]["source"], "indexed-exact");
    assert_eq!(
        stdout["result"]["request"]["params"]["modes"],
        json!(["exact"])
    );
    assert_eq!(
        stdout["result"]["request"]["params"]["includeEvidence"],
        true
    );
    assert_eq!(
        stdout["result"]["outcome"]["candidates"]
            .as_array()
            .expect("candidates")
            .len(),
        2
    );
    assert!(
        stdout["result"]["outcome"]["compilerFallback"]["code"]
            .as_str()
            .is_some_and(|code| !code.is_empty()),
        "{stdout}"
    );
}

#[test]
fn agent_symbol_indexed_exact_cardinality_ignores_presentation_limit() {
    for limit in ["0", "1"] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&home).expect("home");
        seed_source_index(&workspace);
        support::metrics::seed_exact_lookup_symbols(&workspace);

        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "symbol",
                "--query",
                "Parser",
                "--limit",
                limit,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("indexed exact fallback");

        assert!(
            output.status.success(),
            "limit={limit} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("fallback json");
        assert_eq!(stdout["result"]["outcome"], "AMBIGUOUS");
        assert_eq!(
            stdout["result"]["candidates"]
                .as_array()
                .expect("candidates")
                .len(),
            2
        );
    }
}

#[test]
fn agent_symbol_indexed_file_hint_is_literal_and_suffix_equivalent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    for (file_hint, expected_outcome) in [
        ("lib/AlphaParser.kt", "RESOLVED"),
        ("lib/*Parser.kt", "NOT_FOUND"),
    ] {
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "symbol",
                "--query",
                "Parser",
                "--file-hint",
                file_hint,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("indexed exact file hint");

        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("fallback json");
        assert_eq!(stdout["result"]["outcome"], expected_outcome);
    }
}

#[test]
fn agent_symbol_containing_type_never_weakens_to_indexed_exact() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Parser",
            "--containing-type",
            "sample.Container",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("containing type fail closed");

    assert!(!output.status.success());
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("failure json");
    assert!(stdout["error"]["code"].as_str().is_some());
    assert!(stdout["result"].is_null(), "{stdout}");
}

#[test]
fn agent_symbol_operational_resolve_failure_never_falls_back() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let handle = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![(
            "symbol/resolve",
            json!({"type":"RESOLVE_FAILURE","ok":false,"message":"compiler failed"}),
        )],
    );

    let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

    assert!(!output.status.success());
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("failure json");
    assert_eq!(stdout["error"]["code"], "RESOLVE_FAILURE");
    assert_eq!(handle.join().expect("scripted backend").len(), 3);
}
