mod support;

use support::*;

fn rename_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-output\"\n",
    )
    .expect("Gradle marker");
    let file_path = workspace.join("OrderService.kt");
    std::fs::write(
        &file_path,
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin fixture");
    let file_path = file_path.display().to_string();
    spawn_scripted_idea_backend(
        home,
        config_home,
        workspace,
        socket_path,
        vec![
            (
                "symbol/resolve",
                serde_json::json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "symbol": {
                        "fqName": "io.example.OrderService.process",
                        "kind": "FUNCTION",
                        "location": {
                            "filePath": file_path,
                            "startOffset": 44,
                            "endOffset": 51,
                        },
                    },
                }),
            ),
            (
                "raw/rename",
                serde_json::json!({
                    "edits": [{
                        "filePath": file_path,
                        "startOffset": 44,
                        "endOffset": 51,
                        "newText": "processSafely",
                    }],
                    "fileHashes": [{
                        "filePath": file_path,
                        "hash": "a".repeat(64),
                    }],
                    "affectedFiles": [file_path],
                    "schemaVersion": 3,
                }),
            ),
        ],
    )
}

fn decode_toon(bytes: &[u8]) -> serde_json::Value {
    let output = std::str::from_utf8(bytes).expect("toon output should be utf-8");
    toon_format::decode_default(output.trim()).expect("toon output should decode")
}

#[test]
fn agent_rename_plan_default_toon_matches_explicit_json() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let json_backend = rename_backend(&home, &config_home, &workspace, &socket_path);

    let json = kast(&home, &config_home)
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
        .expect("agent rename json");
    assert!(
        json.status.success(),
        "agent rename json should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&json.stdout),
        String::from_utf8_lossy(&json.stderr)
    );
    assert_eq!(
        String::from_utf8(json.stderr.clone()).expect("agent rename stderr"),
        "warning: JSON output for `kast agent` is deprecated; omit `--output json` to use TOON.\n"
    );
    let json_value: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("agent rename json");
    json_backend.join().expect("JSON rename backend");
    std::fs::remove_file(&socket_path).expect("remove first socket");
    let toon_backend = rename_backend(&home, &config_home, &workspace, &socket_path);

    let toon = kast(&home, &config_home)
        .args([
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
        .expect("agent rename toon");
    assert!(
        toon.status.success(),
        "agent rename toon should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&toon.stdout),
        String::from_utf8_lossy(&toon.stderr)
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&toon.stdout).is_err(),
        "toon output should not be parseable as JSON"
    );
    let toon_value = decode_toon(&toon.stdout);
    toon_backend.join().expect("TOON rename backend");

    assert_eq!(toon_value, json_value);
    assert!(
        toon.stdout.len() < json.stdout.len(),
        "toon agent rename output should be smaller than pretty JSON: json={}, toon={}",
        json.stdout.len(),
        toon.stdout.len()
    );
}

#[test]
fn agent_call_removed_errors_can_emit_toon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let call = kast(&home, &config_home)
        .args(["agent", "call", "symbol/resolve"])
        .output()
        .expect("agent call toon removal");
    assert!(!call.status.success(), "removed agent call should fail");
    assert!(
        serde_json::from_slice::<serde_json::Value>(&call.stdout).is_err(),
        "toon validation output should not be parseable as JSON"
    );
    let output = decode_toon(&call.stdout);

    assert_eq!(output["ok"], false, "{output:#}");
    assert_eq!(output["method"], "agent/call", "{output:#}");
    assert_eq!(
        output["error"]["code"], "AGENT_COMMAND_REMOVED",
        "{output:#}"
    );
}

#[test]
fn agent_rename_plan_is_read_only_until_apply() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let backend = rename_backend(&home, &config_home, &workspace, &socket_path);
    let source_before = std::fs::read(workspace.join("OrderService.kt")).expect("source before");

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
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent rename plan");
    assert!(
        plan.status.success(),
        "rename plan should succeed through backend dry-run dispatch: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan json");
    assert_eq!(output["ok"], true, "{output:#}");
    assert_eq!(output["method"], "agent/rename", "{output:#}");
    assert_eq!(
        output["result"]["type"], "KAST_AGENT_MUTATION_RESULT",
        "{output:#}"
    );
    assert_eq!(
        output["result"]["execution"]["outcome"], "PLANNED_RENAME",
        "{output:#}"
    );
    assert_eq!(
        output["result"]["plan"]["method"], "symbol/rename",
        "{output:#}"
    );
    assert!(
        !output["result"]["plan"].to_string().contains("offset"),
        "{output:#}"
    );
    let requests = backend.join().expect("rename backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[3]["method"], "raw/rename");
    assert_eq!(requests[3]["params"]["dryRun"], true);
    assert_eq!(
        std::fs::read(workspace.join("OrderService.kt")).expect("source after"),
        source_before,
    );
}
