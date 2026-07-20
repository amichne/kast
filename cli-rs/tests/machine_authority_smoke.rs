mod support;

use std::io::Write;
use std::process::Command;
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
fn machine_status_rejects_an_incompatible_manifest_with_a_typed_code() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let machine = home.join("Library/Application Support/Kast/machine");
    std::fs::create_dir_all(&machine).expect("machine root");
    std::fs::write(
        machine.join("machine.json"),
        serde_json::to_vec(&serde_json::json!({
            "type": "KAST_MACHINE_MANIFEST",
            "cliSha256": "0".repeat(64),
            "ideaPluginSha256": "1".repeat(64),
            "skillSha256": "2".repeat(64),
            "codexSha256": "3".repeat(64),
            "schemaVersion": 1
        }))
        .expect("legacy manifest JSON"),
    )
    .expect("legacy manifest");

    let status = kast(&home, &config_home)
        .args(["--output", "json", "machine", "status"])
        .output()
        .expect("machine status");
    assert!(!status.status.success());
    let status: serde_json::Value =
        serde_json::from_slice(&status.stdout).expect("invalid machine JSON");
    assert_eq!(status["code"], "MACHINE_INSTALL_INVALID", "{status:#}");
}

#[test]
fn macos_workspace_leases_are_idea_only() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    for command in ["acquire", "status", "release"] {
        let help = kast(&home, &config_home)
            .args(["agent", "lease", command, "--help"])
            .output()
            .unwrap_or_else(|error| panic!("lease {command} help: {error}"));
        assert!(help.status.success());
        let help = String::from_utf8_lossy(&help.stdout);
        assert!(
            !help.contains("--backend"),
            "workspace leases must bind IDEA plugin projects, not backend processes: {help}",
        );
    }
}

#[test]
fn developer_scope_has_no_local_headless_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .args(["developer", "--help"])
        .output()
        .expect("developer help");
    assert!(help.status.success());
    let help = String::from_utf8_lossy(&help.stdout);
    assert!(
        !help.contains("\n  local "),
        "developer machines must not expose a parallel local headless authority: {help}",
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
    assert_eq!(activation["schemaVersion"], 2);
    assert!(
        activation["taskLauncher"]
            .as_str()
            .is_some_and(|path| path.ends_with("/bin/kast-agent-task")),
        "{activation:#}",
    );

    let machine = home.join("Library/Application Support/Kast/machine");
    assert!(machine.join("bin/kast").is_file());
    assert!(machine.join("bin/kast-agent-task").is_file());
    assert_eq!(
        std::fs::read(machine.join("idea/kast.zip")).expect("installed plugin"),
        b"idea-plugin",
    );
    assert!(machine.join("resources/kast-skill/SKILL.md").is_file());
    assert!(
        machine
            .join("resources/agent-task/gradle-receipt.init.gradle")
            .is_file()
    );
    assert!(
        machine
            .join("resources/agent-task/workflow.schema.json")
            .is_file()
    );
    assert!(
        machine
            .join("resources/copilot-plugin/extensions/kast/extension.mjs")
            .is_file()
    );
    assert!(
        machine
            .join("resources/codex-marketplace/marketplace.json")
            .is_file()
    );
    assert!(
        machine
            .join("resources/codex-marketplace/plugins/kast/hooks/hooks.json")
            .is_file()
    );
    for executable in [
        machine.join("bin/kast"),
        machine.join("bin/kast-agent-task"),
        machine.join("resources/agent-task/kast-agent-task"),
        machine.join("resources/codex-marketplace/plugins/kast/scripts/kast-codex-hook"),
    ] {
        assert!(
            is_executable_for_test(&executable),
            "{}",
            executable.display()
        );
    }
    assert!(machine.join("machine.json").is_file());
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("stable command"),
        machine.join("bin/kast"),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast-agent-task")).expect("stable task launcher"),
        machine.join("bin/kast-agent-task"),
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
    assert_eq!(status["active"], false);
    assert!(status.get("daemon").is_none());

    let active_status = Command::new(machine.join("bin/kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args(["--output", "json", "machine", "status"])
        .output()
        .expect("active machine status");
    assert!(active_status.status.success());
    let active_status: serde_json::Value =
        serde_json::from_slice(&active_status.stdout).expect("active machine status JSON");
    assert_eq!(active_status["state"], "INSTALLED");
    assert_eq!(active_status["active"], true);

    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repository root");
    let readiness = Command::new(machine.join("bin/kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args([
            "--output",
            "json",
            "ready",
            "--for",
            "agent",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("active machine readiness");
    let readiness: serde_json::Value =
        serde_json::from_slice(&readiness.stdout).expect("active machine readiness JSON");
    assert_eq!(
        readiness["agentEnvironment"]["hookTrust"]["code"], "HOOK_TRUST_REQUIRED",
        "missing trust state must fail closed: {readiness:#}"
    );
}

#[cfg(unix)]
#[test]
fn active_machine_rejects_resource_executable_mode_drift() {
    use std::os::unix::fs::PermissionsExt;

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
    assert!(activation.status.success());

    let machine = home.join("Library/Application Support/Kast/machine");
    let launcher = machine.join("resources/agent-task/kast-agent-task");
    let mut permissions = std::fs::metadata(&launcher)
        .expect("launcher metadata")
        .permissions();
    permissions.set_mode(0o644);
    std::fs::set_permissions(&launcher, permissions).expect("remove executable mode");

    let status = Command::new(machine.join("bin/kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args(["--output", "json", "machine", "status"])
        .output()
        .expect("active machine status");
    assert!(!status.status.success());
    let status: serde_json::Value =
        serde_json::from_slice(&status.stdout).expect("invalid machine status JSON");
    assert_eq!(status["code"], "MACHINE_INSTALL_INVALID", "{status:#}");
}

#[test]
fn reconciliation_replaces_only_the_closed_ide_plugin_and_global_skill() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin = temp.path().join("kast-idea.zip");
    let codex_log = temp.path().join("codex.log");
    let fake_bin = temp.path().join("bin");
    let fake_codex = fake_bin.join("codex");
    let plugins = temp.path().join("idea-profile/plugins");
    std::fs::create_dir_all(plugins.join("kast")).expect("old plugin");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&fake_bin).expect("fake bin");
    std::fs::write(
        &fake_codex,
        "#!/bin/sh\nset -eu\nprintf '%s\\n' \"$*\" >>\"$KAST_TEST_CODEX_LOG\"\ncase \"$*\" in\n  'plugin list --json') printf '%s\\n' '{\"installed\":[]}' ;;\n  'plugin marketplace list --json') printf '%s\\n' '{\"marketplaces\":[]}' ;;\nesac\n",
    )
    .expect("fake codex");
    set_executable_for_test(&fake_codex);
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
        .env_remove("CODEX_HOME")
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .env("KAST_TEST_CODEX_LOG", &codex_log)
        .env(
            "PATH",
            format!(
                "{}:{}",
                fake_bin.display(),
                std::env::var("PATH").unwrap_or_default()
            ),
        )
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
    assert_eq!(reconciliation["hookTrust"]["code"], "HOOK_TRUST_REQUIRED");
    assert_eq!(reconciliation["hookTrust"]["trusted"], false);
    assert_eq!(reconciliation["schemaVersion"], 2);
    let hook_state: serde_json::Value = serde_json::from_slice(
        &std::fs::read(home.join("Library/Application Support/Kast/machine/state/codex-hook.json"))
            .expect("Codex hook trust state"),
    )
    .expect("Codex hook trust JSON");
    assert_eq!(hook_state["type"], "KAST_CODEX_HOOK_STATE");
    assert_eq!(hook_state["trusted"], false);
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
    let codex_calls = std::fs::read_to_string(codex_log).expect("Codex calls");
    assert!(codex_calls.contains("plugin list --json"), "{codex_calls}");
    assert!(
        codex_calls.contains("plugin marketplace list --json"),
        "{codex_calls}"
    );
    assert!(
        codex_calls.contains("plugin marketplace add ") && codex_calls.contains(" --json"),
        "{codex_calls}"
    );
    assert!(
        codex_calls.contains("plugin add kast@kast --json"),
        "{codex_calls}"
    );
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
