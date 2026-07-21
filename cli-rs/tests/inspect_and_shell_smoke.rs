mod support;

use support::*;

#[test]
fn paths_report_resolves_active_setup_receipt() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let output = kast(&home, &config_home)
        .args(["--output", "json", "developer", "inspect", "paths"])
        .output()
        .expect("paths");
    assert!(output.status.success());
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).expect("paths JSON");
    assert!(
        report["configFiles"]
            .as_array()
            .expect("config files")
            .iter()
            .all(|file| file["scope"] != "macos-homebrew-receipt")
    );
}

#[test]
fn help_topic_renders_setup_reference() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .args(["help", "setup"])
        .output()
        .expect("setup topic");
    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    assert!(stdout.contains("--source"), "{stdout}");
}
