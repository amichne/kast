mod support;

use std::io::Write;
use support::*;

fn write_idea_plugin(path: &Path) {
    let file = std::fs::File::create(path).expect("plugin zip");
    let mut archive = zip::ZipWriter::new(file);
    archive
        .start_file(
            "backend-idea/lib/kast-plugin.jar",
            zip::write::SimpleFileOptions::default(),
        )
        .expect("plugin entry");
    archive.write_all(b"plugin").expect("plugin bytes");
    archive.finish().expect("finish plugin zip");
}

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
        !home
            .join("Library/LaunchAgents/io.github.amichne.kast.plist")
            .exists(),
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

#[test]
fn reconciliation_replaces_only_the_closed_ide_plugin_and_global_skill() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin = temp.path().join("kast-idea.zip");
    let plugins = temp.path().join("idea-profile/plugins");
    std::fs::create_dir_all(plugins.join("kast")).expect("old plugin");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(plugins.join("kast/old.jar"), b"old").expect("old plugin bytes");
    write_idea_plugin(&plugin);

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
        .expect("activation");
    assert!(activation.status.success());

    let reconciliation = kast(&home, &config_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .args([
            "--output",
            "json",
            "machine",
            "reconcile",
            "--idea-plugins-dir",
            plugins.to_str().expect("plugins path"),
        ])
        .output()
        .expect("reconciliation");
    assert!(
        reconciliation.status.success(),
        "reconciliation: stdout={}, stderr={}",
        String::from_utf8_lossy(&reconciliation.stdout),
        String::from_utf8_lossy(&reconciliation.stderr),
    );
    let reconciliation: serde_json::Value =
        serde_json::from_slice(&reconciliation.stdout).expect("reconciliation JSON");
    assert_eq!(reconciliation["type"], "KAST_MACHINE_RECONCILIATION");
    assert_eq!(reconciliation["state"], "RECONCILED");
    assert_eq!(
        std::fs::read(plugins.join("kast/lib/kast-plugin.jar")).expect("new plugin"),
        b"plugin",
    );
    assert!(!plugins.join("kast/old.jar").exists());
    let quarantine = PathBuf::from(
        reconciliation["quarantinedPlugin"]
            .as_str()
            .expect("quarantine path"),
    );
    assert_eq!(
        std::fs::read(quarantine.join("old.jar")).expect("quarantined plugin"),
        b"old",
    );
    assert_eq!(
        std::fs::read_link(home.join(".agents/skills/kast")).expect("global skill link"),
        home.join("Library/Application Support/Kast/machine/resources/kast-skill"),
    );
    assert!(!home.join("Library/LaunchAgents").exists());
}

#[cfg(target_os = "macos")]
#[test]
fn macos_rejects_headless_runtime_before_spawn() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let start = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "headless",
        ])
        .output()
        .expect("headless refusal");
    assert!(!start.status.success());
    let start: serde_json::Value =
        serde_json::from_slice(&start.stdout).expect("headless refusal JSON");
    assert_eq!(start["code"], "HEADLESS_LOCAL_UNSUPPORTED");
}
