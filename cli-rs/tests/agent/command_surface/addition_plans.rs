#[test]
fn agent_add_file_requires_verified_change_workflow() {
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

    let result = kast(&home, &config_home)
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
        ])
        .output()
        .expect("retired add-file command");

    assert!(!result.status.success(), "{result:?}");
    let result: Value = serde_json::from_slice(&result.stdout).expect("retirement JSON");
    assert_eq!(
        result["error"]["code"],
        "KAST_VERIFIED_ADD_FILE_WORKFLOW_REQUIRED",
    );
}

#[test]
fn agent_add_declaration_requires_verified_change_workflow() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    std::fs::write(&target, b"class Existing\n").expect("existing file");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical target");
    let content_file = temp.path().join("declaration.kt");
    std::fs::write(&content_file, "class Added").expect("proposed declaration");

    let result = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-declaration",
            "--inside-file",
            target.to_str().expect("target"),
            "--at",
            "file-bottom",
            "--content-file",
            content_file.to_str().expect("content"),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("retired add-declaration command");

    assert!(!result.status.success(), "{result:?}");
    let result: Value = serde_json::from_slice(&result.stdout).expect("retirement JSON");
    assert_eq!(
        result["error"]["code"],
        "KAST_VERIFIED_ADD_DECLARATION_WORKFLOW_REQUIRED",
    );
}

#[path = "cases/addition_negative.rs"]
mod addition_negative;
