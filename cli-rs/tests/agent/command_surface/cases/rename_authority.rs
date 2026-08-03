use super::*;

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
    let canonical_workspace = workspace.canonicalize().expect("canonical workspace");
    let plan_backend = spawn_scripted_indexer_backend(
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
                    "schemaVersion": api_schema_version()
                }),
            ),
            (
                "raw/rename",
                rename_preview(&canonical_workspace, "processSafely"),
            ),
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
        .expect("selector handle rename without workspace lease");
    assert!(
        !apply.status.success(),
        "rename submission must require a workspace lease: stdout={}, stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let apply: Value = serde_json::from_slice(&apply.stdout).expect("lease error json");
    assert_eq!(apply["error"]["code"], "WORKSPACE_LEASE_REQUIRED");
}

#[test]
fn agent_rename_preview_rejects_duplicate_hash_rows_that_leave_an_affected_file_uncovered() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
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
    let target = json!({
        "fqName": "io.example.OrderService.process",
        "kind": "FUNCTION",
        "declarationFile": first_file,
        "declarationStartOffset": 10,
        "containingType": "io.example.OrderService"
    });
    let occurrence = json!({
        "reference": {
            "location": {
                "filePath": second_file,
                "startOffset": 20,
                "endOffset": 26,
                "startLine": 2,
                "startColumn": 1,
                "preview": "process()"
            },
            "containingSymbol": {"type": "TOP_LEVEL"}
        },
        "resolvedTarget": target,
        "provenance": "COMPILER"
    });
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
            {"filePath": first_file, "hash": hex::encode(Sha256::digest(b"0123456789abcdef\n"))},
            {"filePath": first_file, "hash": hex::encode(Sha256::digest(b"01234567890123456789abcdef\n"))},
        ],
        "affectedFiles": [first_file, second_file],
        "proof": exact_rename_proof(&workspace, vec![occurrence]),
        "fileImages": [
            exact_file_image_value(
                &first_file,
                b"0123456789abcdef\n",
                b"0123456789processSafely\n",
            ),
            exact_file_image_value(
                &second_file,
                b"01234567890123456789abcdef\n",
                b"01234567890123456789processSafely\n",
            ),
        ],
        "schemaVersion": api_schema_version(),
    });
    let backend = spawn_scripted_indexer_backend(
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
