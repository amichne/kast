#[test]
fn agent_rename_without_apply_returns_identity_first_plan_without_applied_mutation_authority() {
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
    assert_eq!(requests[3]["method"], "runtime/status");
    assert_eq!(requests[4]["method"], "raw/rename");
    assert_eq!(requests[4]["params"]["dryRun"], true);
    assert_eq!(
        requests[4]["params"]["position"]["startOffset"],
        Value::Null
    );
    assert_eq!(requests[4]["params"]["position"]["offset"], 10);
    assert!(
        !stdout["result"]["request"].to_string().contains("offset"),
        "public identity request must not depend on a caller-provided offset: {stdout}"
    );
}

#[test]
fn agent_rename_preview_preserves_exact_compiler_proof() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("rename-proof.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-proof\"\n",
    )
    .expect("Gradle workspace marker");
    std::fs::write(
        workspace.join("Keywords.kt"),
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin declaration fixture");
    std::fs::write(
        workspace.join("Usage.kt"),
        "package io.example\nservice.process()\n",
    )
    .expect("Kotlin reference fixture");
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
            (
                "raw/rename",
                rename_preview_with_exact_reference(&workspace, "processSafely"),
            ),
        ],
    );

    let output = kast(&home, &config_home)
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
        ])
        .output()
        .expect("agent rename proof preview");

    assert!(
        output.status.success(),
        "rename proof preview should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("rename proof JSON");
    let proof = &stdout["result"]["plan"]["preview"]["proof"];
    assert_eq!(proof["target"]["fqName"], "io.example.OrderService.process");
    assert_eq!(proof["requiredGeneration"], 7);
    assert_eq!(proof["evidence"]["type"], "COMPLETE");
    assert_eq!(proof["evidence"]["cardinality"]["totalCount"], 1);
    assert_eq!(proof["occurrences"].as_array().map(Vec::len), Some(1));
    assert_eq!(proof["occurrences"][0]["resolvedTarget"], proof["target"]);
    assert_eq!(proof["occurrences"][0]["provenance"], "COMPILER");
    backend.join().expect("rename proof backend");
}

#[test]
fn agent_rename_preview_rejects_proof_for_another_resolved_target() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("rename-proof-mismatch.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-proof-mismatch\"\n",
    )
    .expect("Gradle workspace marker");
    std::fs::write(
        workspace.join("Keywords.kt"),
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin declaration fixture");
    let mut mismatched_preview = rename_preview(&workspace, "processSafely");
    mismatched_preview["proof"]["target"]["fqName"] = json!("io.example.OtherService.process");
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
            ("raw/rename", mismatched_preview),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent rename mismatched proof preview");

    assert!(!output.status.success(), "mismatched proof was accepted");
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("rename proof error JSON");
    assert_eq!(stdout["error"]["code"], "INVALID_RENAME_PREVIEW");
    assert!(
        stdout["error"]["message"]
            .as_str()
            .is_some_and(|message| message.contains("selected compiler identity")),
        "{stdout:#}"
    );
    backend.join().expect("mismatched rename proof backend");
}

#[path = "cases/rename_authority.rs"]
mod rename_authority;
