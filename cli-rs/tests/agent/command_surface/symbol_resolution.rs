use serde_json::{Value, json};
use support::metrics::seed_source_index;
use support::*;

fn symbol_result(workspace: &Path, fq_name: &str) -> Value {
    json!({
        "type": "RESOLVE_SUCCESS",
        "ok": true,
        "source": "compiler",
        "symbol": {
            "fqName": fq_name,
            "kind": "FUNCTION",
            "location": {
                "filePath": workspace.join("Keywords.kt").display().to_string(),
                "startOffset": 10,
                "endOffset": 16,
                "startLine": 1,
                "startColumn": 1,
                "preview": "fun when()"
            }
        }
    })
}

fn rename_preview(workspace: &Path, new_name: &str) -> Value {
    let file_path = workspace.join("Keywords.kt").display().to_string();
    json!({
        "edits": [{
            "filePath": file_path,
            "startOffset": 10,
            "endOffset": 16,
            "newText": new_name,
        }],
        "fileHashes": [{
            "filePath": file_path,
            "hash": "a".repeat(64),
        }],
        "affectedFiles": [file_path],
        "schemaVersion": 5,
    })
}
fn run_agent_symbol(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    extra_args: &[&str],
) -> std::process::Output {
    let mut command = kast(home, config_home);
    command.args([
        "--output",
        "json",
        "agent",
        "symbol",
        "--query",
        "`when`",
        "--workspace-root",
        workspace.to_str().expect("workspace"),
    ]);
    command.args(extra_args).output().expect("agent symbol")
}

#[test]
fn agent_symbol_defaults_to_exact_and_returns_compiler_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("headless.sock");
    let handle = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![("symbol/resolve", symbol_result(&workspace, "sample.when"))],
    );

    let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("symbol json");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_SYMBOL_RESULT");
    assert_eq!(stdout["result"]["mode"], "exact");
    assert_eq!(stdout["result"]["outcome"], "RESOLVED");
    assert_eq!(stdout["result"]["source"], "compiler");
    assert_eq!(stdout["result"]["identity"]["fqName"], "sample.when");
    let requests = handle.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[2]["params"]["symbol"], "`when`");
}

#[test]
fn agent_symbol_not_found_and_ambiguous_do_not_discover() {
    for result in [
        json!({"type":"RESOLVE_NOT_FOUND","ok":true,"source":"compiler"}),
        json!({
            "type":"RESOLVE_AMBIGUOUS",
            "ok":true,
            "source":"compiler",
            "candidates":[{"fqName":"alpha.Parser.parse"},{"fqName":"beta.Parser.parse"}]
        }),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("headless.sock");
        let handle = spawn_scripted_headless_backend(
            &home,
            &config_home,
            &workspace,
            &socket_path,
            vec![("symbol/resolve", result)],
        );

        let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("symbol json");
        assert!(matches!(
            stdout["result"]["outcome"].as_str(),
            Some("NOT_FOUND" | "AMBIGUOUS")
        ));
        let requests = handle.join().expect("scripted backend");
        assert_eq!(
            requests.len(),
            3,
            "expected only runtime probes plus resolve"
        );
        assert_eq!(requests[2]["method"], "symbol/resolve");
    }
}

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
    let socket_path = temp.path().join("headless.sock");
    let handle = spawn_scripted_headless_backend(
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
