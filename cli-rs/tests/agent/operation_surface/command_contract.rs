use serde_json::{Value, json};
use support::*;

#[test]
fn applied_mutation_requires_idempotency_key_before_runtime_discovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path();
    let content_file = temp.path().join("Added.kt");
    let target = temp.path().join("Target.kt");
    std::fs::write(&content_file, "class Added\n").expect("content");
    let target = target.to_str().expect("target").to_string();
    let content = content_file.to_str().expect("content").to_string();
    let cases = [
        vec![
            "rename".to_string(),
            "--symbol".to_string(),
            "sample.Example".to_string(),
            "--new-name".to_string(),
            "Renamed".to_string(),
        ],
        vec![
            "add-file".to_string(),
            "--file-path".to_string(),
            target.clone(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-declaration".to_string(),
            "--inside-file".to_string(),
            target.clone(),
            "--at".to_string(),
            "file-bottom".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-implementation".to_string(),
            "--inside-scope".to_string(),
            "sample.Example".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-statement".to_string(),
            "--inside-scope".to_string(),
            "sample.Example.run".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "replace-declaration".to_string(),
            "--symbol".to_string(),
            "sample.Example".to_string(),
            "--content-file".to_string(),
            content,
        ],
    ];

    for args in cases {
        let output = kast(&home, &config_home)
            .args(["--output", "json", "agent"])
            .args(args)
            .args([
                "--workspace-root",
                workspace.to_str().expect("workspace root"),
            ])
            .arg("--apply")
            .output()
            .expect("applied mutation");

        assert!(!output.status.success(), "missing key must fail");
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("structured usage error");
        assert_eq!(stdout["error"]["code"], "AGENT_USAGE", "{stdout}");
        assert!(
            stdout["error"]["message"]
                .as_str()
                .is_some_and(|message| message.contains("--idempotency-key")),
            "{stdout}"
        );
    }
}

#[test]
fn asynchronous_operation_commands_are_absent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let output = kast(&home, &config_home)
        .args(["agent", "operation", "status"])
        .output()
        .expect("removed operation command");
    assert!(
        !output.status.success(),
        "removed operation command must fail"
    );
    let diagnostic = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        diagnostic.contains("unrecognized subcommand 'operation'"),
        "{diagnostic}"
    );
}

#[test]
fn applied_add_file_submits_typed_mutation_request() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let content_file = temp.path().join("Added.kt");
    let target = workspace.join("src/Added.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"operation-fixture\"\n",
    )
    .expect("settings");
    std::fs::write(&content_file, "class Added\n").expect("content");
    let binary = write_active_kast_for_test(&home, &config_home);
    let canonical_target = workspace
        .canonicalize()
        .expect("canonical workspace")
        .join("src/Added.kt");
    let backend = spawn_operation_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        Some(mutation_result(false)),
        false,
    );
    let lease_id = acquire_workspace_lease(&binary, &home, &config_home, &workspace);

    let output = kast_at(&binary, &home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--file-path",
            target.to_str().expect("target"),
            "--content-file",
            content_file.to_str().expect("content"),
            "--apply",
            "--idempotency-key",
            "issue-333-add-file",
            "--lease-id",
            &lease_id,
        ])
        .output()
        .expect("submit mutation");

    assert!(
        output.status.success(),
        "submit should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("terminal mutation result");
    assert_eq!(stdout["result"]["execution"]["outcome"], "SUCCEEDED");
    assert_eq!(stdout["result"]["execution"]["deduplicated"], false);
    let requests = backend.join().expect("backend");
    let submit = requests
        .iter()
        .find(|request| request["method"] == "mutation/submit")
        .expect("mutation submit request");
    assert_eq!(submit["params"]["type"], "ADD_FILE", "{submit}");
    assert_eq!(
        submit["params"]["idempotencyKey"], "issue-333-add-file",
        "{submit}"
    );
    assert_eq!(
        submit["params"]["request"]["filePath"],
        canonical_target.to_str().unwrap()
    );
    assert_eq!(
        submit["params"]["request"]["contentFile"],
        content_file.to_str().unwrap()
    );
}
