#[path = "../support/mod.rs"]
mod support;

use std::os::unix::process::CommandExt as _;
use support::*;

#[test]
fn lifecycle_inspection_reports_absent_without_creating_runtime_state() {
    let temp = tempfile::tempdir().expect("lifecycle inspection fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    write_current_cli_install_manifest_for_test(&home, &config_home);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "lifecycle",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("lifecycle inspection");

    assert_success(&output, "absent lifecycle inspection");
    let inspection = output_json(&output);
    assert_eq!(inspection["state"], "Absent");
    assert_eq!(
        inspection["workspace_root"],
        workspace.display().to_string()
    );
    assert!(
        !default_install_root(&home)
            .join("state/runtime/services")
            .exists(),
        "read-only inspection created runtime state"
    );
}

#[test]
fn lifecycle_inspection_reports_invalid_root_as_a_closed_blocker() {
    let temp = tempfile::tempdir().expect("invalid root fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let missing = temp.path().join("missing-workspace");

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "lifecycle",
            "--workspace-root",
            missing.to_str().expect("workspace path"),
        ])
        .output()
        .expect("lifecycle inspection");

    assert_success(&output, "blocked lifecycle inspection");
    let inspection = output_json(&output);
    assert_eq!(inspection["state"], "Blocked");
    assert_eq!(inspection["blocker"]["code"], "WORKSPACE_ROOT_INVALID");
    assert!(
        !missing.exists(),
        "inspection created the missing workspace"
    );
}

#[test]
fn lifecycle_inspection_never_signals_an_ambiguous_live_process() {
    let temp = tempfile::tempdir().expect("runtime ownership fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("unresponsive.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    write_current_cli_install_manifest_for_test(&home, &config_home);
    std::fs::write(&socket_path, "not a socket").expect("unresponsive endpoint marker");
    let mut unrelated = Command::new("/bin/sleep")
        .arg("30")
        .spawn()
        .expect("unrelated process");
    let descriptor_dir = default_descriptor_dir(&home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor directory");
    let descriptor_registry = descriptor_dir.join("daemons.json");
    std::fs::write(
        &descriptor_registry,
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_process_test(
            &workspace,
            &socket_path,
            "indexer",
            "durable-ownership-test",
            unrelated.id(),
        )]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor registry");

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "lifecycle",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("lifecycle inspection");

    assert_success(&output, "ambiguous lifecycle inspection");
    assert_eq!(output_json(&output)["state"], "Blocked");
    assert!(
        unrelated.try_wait().expect("unrelated status").is_none(),
        "read-only inspection signaled an unproven process"
    );
    assert!(descriptor_registry.exists(), "inspection removed evidence");
    assert!(socket_path.exists(), "inspection removed the socket");

    unrelated.kill().expect("test process cleanup");
    unrelated.wait().expect("test process exit");
    std::fs::remove_file(socket_path).expect("test socket cleanup");
}

#[test]
fn explicit_lifecycle_commands_are_not_callable() {
    for arguments in [
        vec!["start", "--help"],
        vec!["status", "--help"],
        vec!["stop", "--help"],
        vec!["developer", "runtime", "repair", "--help"],
        vec!["developer", "runtime", "start", "--help"],
        vec!["agent", "lease", "acquire", "--help"],
    ] {
        let output = std::process::Command::new(env!("CARGO_BIN_EXE_kast"))
            .arg0("kastctl")
            .args(arguments)
            .output()
            .expect("retired lifecycle command");
        assert!(
            !output.status.success(),
            "retired lifecycle command remained callable"
        );
    }
}

fn assert_success(output: &std::process::Output, context: &str) {
    assert!(
        output.status.success(),
        "{context}: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}

fn output_json(output: &std::process::Output) -> serde_json::Value {
    serde_json::from_slice(&output.stdout).expect("command JSON")
}
