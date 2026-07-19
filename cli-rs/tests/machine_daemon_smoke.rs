mod support;

use support::*;

#[test]
fn machine_status_is_a_definitive_read_only_empty_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let status = kast(&home, &config_home)
        .args(["--output", "json", "machine", "status"])
        .output()
        .expect("machine status");
    assert!(
        status.status.success(),
        "missing machine install is a successful empty state: stdout={}, stderr={}",
        String::from_utf8_lossy(&status.stdout),
        String::from_utf8_lossy(&status.stderr),
    );
    let status: serde_json::Value =
        serde_json::from_slice(&status.stdout).expect("machine status JSON");
    assert_eq!(status["type"], "KAST_MACHINE_STATUS");
    assert_eq!(status["state"], "NOT_INSTALLED");
    assert!(
        status.get("daemon").is_none(),
        "machine authority must not invent a resident daemon lifecycle: {status:#}",
    );
    assert_eq!(status["schemaVersion"], 1);
}

#[test]
fn macos_workspace_leases_are_idea_only() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .args(["agent", "lease", "acquire", "--help"])
        .output()
        .expect("lease acquire help");
    assert!(help.status.success());
    let help = String::from_utf8_lossy(&help.stdout);
    assert!(
        !help.contains("--backend"),
        "workspace leases must bind IDEA plugin projects, not backend processes: {help}",
    );
}
