mod support;

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
        "schemaVersion": 3,
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
    let socket_path = temp.path().join("idea.sock");
    let handle = spawn_scripted_idea_backend(
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
        let socket_path = temp.path().join("idea.sock");
        let handle = spawn_scripted_idea_backend(
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
    let socket_path = temp.path().join("idea.sock");
    let handle = spawn_scripted_idea_backend(
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

fn assert_removed_agent_workflow(stdout: &serde_json::Value) {
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["method"], "agent/workflow", "{stdout}");
    assert_eq!(stdout["error"]["code"], "AGENT_COMMAND_REMOVED", "{stdout}");
    let replacements = stdout["error"]["details"]["replacements"]
        .as_array()
        .expect("workflow replacements");
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast agent verify --workspace-root <repo>"),
        "{stdout}"
    );
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast repair --apply"),
        "{stdout}"
    );
}

#[test]
fn removed_agent_workflow_package_verify_fails_closed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let workflow = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "package-verify",
            "--dry-run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent workflow package-verify");

    assert!(
        !workflow.status.success(),
        "removed workflow should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow removal json");
    assert_removed_agent_workflow(&stdout);
}

#[test]
fn removed_agent_workflow_write_validate_fails_before_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let workflow = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "write-validate",
            "--mode",
            "create",
            "--file-path",
            temp.path()
                .join("Example.kt")
                .to_str()
                .expect("example path"),
            "--content",
            "class Example",
        ])
        .output()
        .expect("agent workflow write-validate");

    assert!(
        !workflow.status.success(),
        "removed workflow should fail before mutation: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow removal json");
    assert_removed_agent_workflow(&stdout);
}

#[test]
fn agent_rename_without_apply_returns_identity_first_plan_without_applied_mutation_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-preview\"\n",
    )
    .expect("Gradle workspace marker");
    std::fs::write(
        workspace.join("Keywords.kt"),
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin rename fixture");
    let backend = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "symbol/resolve",
                symbol_result(&workspace, "io.example.OrderService.process"),
            ),
            ("raw/rename", rename_preview(&workspace, "processSafely")),
        ],
    );
    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--kind",
            "function",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--explain",
        ])
        .output()
        .expect("agent rename plan");

    assert!(
        plan.status.success(),
        "rename plan should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan json");
    assert_eq!(stdout["method"], "agent/rename", "{stdout}");
    assert_eq!(
        stdout["result"]["type"], "KAST_AGENT_RENAME_PLAN",
        "{stdout}"
    );
    assert_eq!(
        stdout["result"]["request"]["method"], "symbol/rename",
        "{stdout}"
    );
    assert_eq!(
        stdout["result"]["request"]["params"]["type"], "RENAME_BY_SYMBOL_REQUEST",
        "{stdout}"
    );
    assert_eq!(
        stdout["result"]["request"]["params"]["symbol"], "io.example.OrderService.process",
        "{stdout}"
    );
    assert_eq!(
        stdout["result"]["preview"]["edits"]
            .as_array()
            .map(Vec::len),
        Some(1)
    );
    assert_eq!(
        stdout["result"]["preview"]["affectedFiles"]
            .as_array()
            .map(Vec::len),
        Some(1)
    );
    assert_eq!(
        stdout["result"]["preview"]["edits"][0]["newText"],
        "processSafely"
    );
    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[3]["method"], "raw/rename");
    assert_eq!(requests[3]["params"]["dryRun"], true);
    assert_eq!(
        requests[3]["params"]["position"]["startOffset"],
        Value::Null
    );
    assert_eq!(requests[3]["params"]["position"]["offset"], 10);
    assert!(
        !stdout["result"]["request"].to_string().contains("offset"),
        "public identity request must not depend on a caller-provided offset: {stdout}"
    );
}

#[test]
fn selector_handle_rename_preserves_compact_plan_and_distinct_apply_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let plan_socket_path = temp.path().join("rename-handle-plan.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-handle\"\n",
    )
    .expect("Gradle workspace marker");
    let declaration_file = workspace.join("Keywords.kt");
    std::fs::write(
        &declaration_file,
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin rename fixture");
    let selector_handle = "ksh1.rename-handle";
    let plan_backend = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &plan_socket_path,
        vec![
            (
                "selector/identity",
                json!({
                    "type": "AVAILABLE",
                    "identity": {
                        "fqName": "io.example.OrderService.process",
                        "kind": "FUNCTION",
                        "declarationFile": declaration_file.display().to_string(),
                        "declarationStartOffset": 10,
                        "containingType": "io.example.OrderService"
                    },
                    "schemaVersion": 3
                }),
            ),
            ("raw/rename", rename_preview(&workspace, "processSafely")),
        ],
    );

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--selector-handle",
            selector_handle,
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--explain",
        ])
        .output()
        .expect("selector handle rename plan");

    assert!(
        plan.status.success(),
        "rename plan should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr),
    );
    let stdout: Value = serde_json::from_slice(&plan.stdout).expect("rename plan json");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_RENAME_PLAN");
    assert_eq!(
        stdout["result"]["request"]["params"]["type"],
        "RENAME_BY_SELECTOR_HANDLE_REQUEST",
    );
    assert_eq!(
        stdout["result"]["request"]["params"]["selectorHandle"],
        selector_handle,
    );
    assert_eq!(
        stdout["result"]["identity"]["fqName"],
        "io.example.OrderService.process",
    );
    assert!(
        stdout["result"].get("resolution").is_none(),
        "handle plan must not replay a resolve envelope: {stdout}",
    );
    let requests = plan_backend.join().expect("plan backend");
    let identity_request = requests
        .iter()
        .find(|request| request["method"] == "selector/identity")
        .expect("selector identity request");
    assert_eq!(
        identity_request["params"]["selectorHandle"],
        selector_handle
    );
    assert_eq!(identity_request["params"]["family"], "RENAME");
    assert!(
        requests
            .iter()
            .all(|request| request["method"] != "symbol/resolve"),
        "handle rename must not perform name resolution: {requests:?}",
    );
    let preview_request = requests
        .iter()
        .find(|request| request["method"] == "raw/rename")
        .expect("rename preview request");
    assert_eq!(preview_request["params"]["position"]["offset"], 10);
    assert_eq!(preview_request["params"]["dryRun"], true);

    let missing_key = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--selector-handle",
            selector_handle,
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--apply",
        ])
        .output()
        .expect("rename without idempotency key");
    assert!(
        !missing_key.status.success(),
        "apply must require authority"
    );
    let missing_key: Value =
        serde_json::from_slice(&missing_key.stdout).expect("missing key error json");
    assert_eq!(missing_key["error"]["code"], "AGENT_USAGE");

    let apply_socket_path = temp.path().join("rename-handle-apply.sock");
    let apply_backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &apply_socket_path,
        vec![(
            "mutation/submit",
            json!({
                "operation": {
                    "operationId": "00000000-0000-0000-0000-000000000392",
                    "idempotencyKey": "issue-392-rename",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "QUEUED",
                        "trace": {
                            "enteredStages": [],
                            "editApplicationState": "NOT_STARTED"
                        },
                        "cancellationRequested": false
                    }
                },
                "deduplicated": false
            }),
        )],
    );
    let apply = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--selector-handle",
            selector_handle,
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--apply",
            "--idempotency-key",
            "issue-392-rename",
        ])
        .output()
        .expect("authorized selector handle rename");
    assert!(
        apply.status.success(),
        "rename submission should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let requests = apply_backend.join().expect("apply backend");
    let submit = requests
        .iter()
        .find(|request| request["method"] == "mutation/submit")
        .expect("mutation submission");
    assert_eq!(submit["params"]["type"], "RENAME");
    assert_eq!(submit["params"]["idempotencyKey"], "issue-392-rename");
    assert_eq!(
        submit["params"]["request"]["type"],
        "RENAME_BY_SELECTOR_HANDLE_REQUEST",
    );
    assert_eq!(
        submit["params"]["request"]["selectorHandle"],
        selector_handle,
    );
}

#[test]
fn agent_rename_preview_rejects_duplicate_hash_rows_that_leave_an_affected_file_uncovered() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("headless.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-preview\"\n",
    )
    .expect("Gradle workspace marker");
    std::fs::write(
        workspace.join("Keywords.kt"),
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin rename fixture");
    let first_file = workspace.join("Keywords.kt").display().to_string();
    let second_file = workspace.join("Usage.kt").display().to_string();
    let duplicate_hash_preview = json!({
        "edits": [
            {
                "filePath": first_file,
                "startOffset": 10,
                "endOffset": 16,
                "newText": "processSafely",
            },
            {
                "filePath": second_file,
                "startOffset": 20,
                "endOffset": 26,
                "newText": "processSafely",
            },
        ],
        "fileHashes": [
            {"filePath": first_file, "hash": "a".repeat(64)},
            {"filePath": first_file, "hash": "b".repeat(64)},
        ],
        "affectedFiles": [first_file, second_file],
        "schemaVersion": 3,
    });
    let backend = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "symbol/resolve",
                symbol_result(&workspace, "io.example.OrderService.process"),
            ),
            ("raw/rename", duplicate_hash_preview),
        ],
    );

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--kind",
            "function",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--explain",
        ])
        .output()
        .expect("agent rename plan");

    assert!(
        !plan.status.success(),
        "duplicate hash rows must not satisfy exact affected-file coverage: {}",
        String::from_utf8_lossy(&plan.stdout),
    );
    let stdout: Value = serde_json::from_slice(&plan.stdout).expect("plan failure json");
    assert_eq!(
        stdout["error"]["code"], "INVALID_RENAME_PREVIEW",
        "{stdout}"
    );
    backend.join().expect("scripted backend");
}

#[test]
fn agent_scope_mutations_without_apply_return_typed_request_plans() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("snippet");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let target_file = workspace.join("Added.kt");

    let cases = [
        (
            "add-file",
            vec![
                "agent",
                "add-file",
                "--file-path",
                target_file.to_str().expect("target"),
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-file",
            "symbol/add-file",
        ),
        (
            "add-declaration",
            vec![
                "agent",
                "add-declaration",
                "--inside-file",
                target_file.to_str().expect("target"),
                "--at",
                "file-bottom",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-declaration",
            "symbol/add-declaration",
        ),
        (
            "add-implementation",
            vec![
                "agent",
                "add-implementation",
                "--inside-scope",
                "sample.Greeter",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-implementation",
            "symbol/add-implementation",
        ),
        (
            "add-statement",
            vec![
                "agent",
                "add-statement",
                "--inside-scope",
                "sample.greet",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-statement",
            "symbol/add-statement",
        ),
        (
            "replace-declaration",
            vec![
                "agent",
                "replace-declaration",
                "--symbol",
                "sample.greet",
                "--kind",
                "function",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/replace-declaration",
            "symbol/replace-declaration",
        ),
    ];

    for (name, args, agent_method, request_method) in cases {
        let plan = kast(&home, &config_home)
            .arg("--output")
            .arg("json")
            .args(args)
            .args(["--workspace-root", workspace.to_str().expect("workspace")])
            .output()
            .unwrap_or_else(|error| panic!("{name} plan failed to launch: {error}"));

        assert!(
            plan.status.success(),
            "{name} plan should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr)
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&plan.stdout).unwrap_or_else(|error| {
                panic!(
                    "{name} plan should emit json: {error}; stdout={}",
                    String::from_utf8_lossy(&plan.stdout)
                )
            });
        assert_eq!(stdout["method"], agent_method, "{stdout}");
        assert_eq!(
            stdout["result"]["type"], "KAST_AGENT_MUTATION_RESULT",
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["operation"]["state"], "PLANNED",
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["plan"]["method"], request_method,
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["plan"]["contentFile"],
            content_file.to_str().expect("snippet"),
            "{stdout}"
        );
    }
}

#[test]
fn relative_file_targets_are_canonical_in_mutation_plans() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let target_parent = workspace.join("src/generated");
    std::fs::create_dir_all(&target_parent).expect("target parent");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("snippet");
    let expected_target = target_parent
        .canonicalize()
        .expect("canonical target parent")
        .join("New File.kt")
        .display()
        .to_string();

    let cases = [
        (
            "add-file",
            vec![
                "agent",
                "add-file",
                "--file-path",
                "src/generated/New File.kt",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            &["filePath"][..],
        ),
        (
            "add-declaration",
            vec![
                "agent",
                "add-declaration",
                "--inside-file",
                "src/generated/New File.kt",
                "--at",
                "file-bottom",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            &["placement", "scope", "insideFile"][..],
        ),
        (
            "add-implementation",
            vec![
                "agent",
                "add-implementation",
                "--inside-file",
                "src/generated/New File.kt",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            &["placement", "scope", "insideFile"][..],
        ),
    ];

    for (name, args, target_path) in cases {
        let plan = kast(&home, &config_home)
            .args(["--output", "json"])
            .args(args)
            .args(["--workspace-root", workspace.to_str().expect("workspace")])
            .output()
            .unwrap_or_else(|error| panic!("{name} plan: {error}"));
        let document: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan JSON");
        let plan_result = &document["result"]["plan"];
        let target = target_path
            .iter()
            .fold(plan_result, |value, segment| &value[*segment]);

        assert!(
            plan.status.success(),
            "{name}: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr),
        );
        assert_eq!(target, &expected_target, "{name}: {document:#}");
        assert_eq!(
            plan_result["contentFile"],
            content_file.to_str().expect("snippet"),
            "{name}: {document:#}",
        );
    }
}

#[test]
fn relative_file_target_requires_explicit_workspace_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "class Added\n").expect("snippet");

    let plan = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--file-path",
            "Added.kt",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .output()
        .expect("relative add-file plan");
    let document: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan JSON");

    assert!(!plan.status.success(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE",
        "{document:#}",
    );
}

#[test]
fn agent_mutation_plans_preserve_scope_and_anchor_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src");
    std::fs::create_dir_all(&source_root).expect("source root");
    let file_path = source_root
        .canonicalize()
        .expect("canonical source root")
        .join("App.kt");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "println(\"added\")\n").expect("snippet");

    let declaration = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-declaration",
            "--inside-scope",
            "sample.Container",
            "--after-symbol",
            "sample.Container.existing",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("declaration plan");
    assert!(
        declaration.status.success(),
        "{}",
        String::from_utf8_lossy(&declaration.stdout)
    );
    let declaration: serde_json::Value = serde_json::from_slice(&declaration.stdout).expect("json");
    assert_eq!(
        declaration["result"]["plan"]["placement"],
        serde_json::json!({
            "scope": {"type": "NAMED_SCOPE", "insideScope": "sample.Container"},
            "anchor": {"type": "AFTER_SYMBOL", "symbol": "sample.Container.existing"}
        })
    );

    let file_anchor = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-declaration",
            "--inside-file",
            file_path.to_str().expect("file path"),
            "--at",
            "file-bottom",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("file anchor plan");
    assert!(
        file_anchor.status.success(),
        "{}",
        String::from_utf8_lossy(&file_anchor.stdout)
    );
    let file_anchor: serde_json::Value = serde_json::from_slice(&file_anchor.stdout).expect("json");
    assert_eq!(
        file_anchor["result"]["plan"]["placement"],
        serde_json::json!({
            "scope": {"type": "FILE_SCOPE", "insideFile": file_path},
            "anchor": {"type": "AT_ANCHOR", "anchor": "file-bottom"}
        })
    );

    let before_symbol = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-implementation",
            "--inside-scope",
            "sample.Container",
            "--before-symbol",
            "sample.Container.existing",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("before-symbol plan");
    assert!(
        before_symbol.status.success(),
        "{}",
        String::from_utf8_lossy(&before_symbol.stdout)
    );
    let before_symbol: serde_json::Value =
        serde_json::from_slice(&before_symbol.stdout).expect("json");
    assert_eq!(
        before_symbol["result"]["plan"]["placement"],
        serde_json::json!({
            "scope": {"type": "NAMED_SCOPE", "insideScope": "sample.Container"},
            "anchor": {"type": "BEFORE_SYMBOL", "symbol": "sample.Container.existing"}
        })
    );

    let statement = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-statement",
            "--inside-scope",
            "sample.Container.run",
            "--at",
            "body-end",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("statement plan");
    assert!(
        statement.status.success(),
        "{}",
        String::from_utf8_lossy(&statement.stdout)
    );
    let statement: serde_json::Value = serde_json::from_slice(&statement.stdout).expect("json");
    assert_eq!(
        statement["result"]["plan"]["insideScope"],
        "sample.Container.run"
    );
    assert_eq!(
        statement["result"]["plan"]["anchor"],
        serde_json::json!({"type": "AT_ANCHOR", "anchor": "body-end"})
    );
}

#[test]
fn ready_flags_installed_backend_below_embedded_minimum() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let install_dir = install_root.join("current/lib/backends/headless/headless-0.0.1");
    let runtime_libs = install_dir.join("runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::create_dir_all(
        install_manifest_path(&home)
            .parent()
            .expect("manifest parent"),
    )
    .expect("manifest parent");
    std::fs::write(
        install_manifest_path(&home),
        serde_json::to_string_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "test-install",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(&home).display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": env!("CARGO_BIN_EXE_kast"),
                "activeBinary": env!("CARGO_BIN_EXE_kast")
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["backend:headless"],
            "managedPaths": ["current/lib/backends/headless"],
            "backends": [{
                "name": "headless",
                "version": "0.0.1",
                "installDir": install_dir.display().to_string(),
                "runtimeLibsDir": runtime_libs.display().to_string()
            }],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("ready");
    let stdout = String::from_utf8_lossy(&ready.stdout);

    assert!(
        !ready.status.success(),
        "ready should fail for stale backend"
    );
    assert!(stdout.contains("\"ok\": false"), "{stdout}");
    assert!(stdout.contains("\"minimumBackendVersion\""), "{stdout}");
    assert!(stdout.contains("0.0.1"), "{stdout}");
    assert!(stdout.contains("older than required"), "{stdout}");
}
