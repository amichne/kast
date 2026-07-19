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

#[test]
fn activation_installs_one_processless_machine_bundle() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin = temp.path().join("kast-idea.zip");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&plugin, b"idea-plugin").expect("plugin fixture");

    let activation = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "machine",
            "activate",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
        ])
        .output()
        .expect("machine activation");
    assert!(
        activation.status.success(),
        "activation: stdout={}, stderr={}",
        String::from_utf8_lossy(&activation.stdout),
        String::from_utf8_lossy(&activation.stderr),
    );
    let activation: serde_json::Value =
        serde_json::from_slice(&activation.stdout).expect("activation JSON");
    assert_eq!(activation["type"], "KAST_MACHINE_ACTIVATION");
    assert_eq!(activation["state"], "ACTIVATED");

    let machine = home.join("Library/Application Support/Kast/machine");
    assert!(machine.join("bin/kast").is_file());
    assert_eq!(
        std::fs::read(machine.join("idea/kast.zip")).expect("installed plugin"),
        b"idea-plugin",
    );
    assert!(machine.join("resources/kast-skill/SKILL.md").is_file());
    assert!(machine.join("machine.json").is_file());
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("stable command"),
        machine.join("bin/kast"),
    );
    assert!(
        !home.join("Library/LaunchAgents/io.github.amichne.kast.plist").exists(),
        "processless activation must not install a LaunchAgent",
    );

    let status = kast(&home, &config_home)
        .args(["--output", "json", "machine", "status"])
        .output()
        .expect("installed status");
    assert!(status.status.success());
    let status: serde_json::Value =
        serde_json::from_slice(&status.stdout).expect("installed status JSON");
    assert_eq!(status["state"], "INSTALLED");
    assert!(status.get("daemon").is_none());
}
