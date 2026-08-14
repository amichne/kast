use super::*;

#[test]
fn agent_add_file_apply_cannot_bypass_the_verified_change_workflow() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content_file = temp.path().join("Added.kt");
    std::fs::write(&content_file, "class Added\n").expect("proposed file");

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--file-path",
            target.to_str().expect("target"),
            "--content-file",
            content_file.to_str().expect("content"),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--apply",
            "--idempotency-key",
            "must-not-bypass",
        ])
        .output()
        .expect("retired add-file apply");
    assert!(!output.status.success(), "{output:?}");
    let output: Value = serde_json::from_slice(&output.stdout).expect("error JSON");
    assert_eq!(
        output["error"]["code"],
        "KAST_VERIFIED_ADD_FILE_WORKFLOW_REQUIRED",
    );
}
