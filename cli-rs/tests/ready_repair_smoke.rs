mod support;

use support::*;

#[test]
fn ready_fix_writes_manifest_and_removes_install_owned_config() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = home.join(".local/share/kast");
    let stale_bin = temp.path().join("stale-bin");
    let stale_runtime_libs = temp.path().join("runtime-libs");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
    std::fs::write(stale_bin.join("kast"), b"old binary\n").expect("old binary");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"
runtimeDir = "{}"

[backends.headless]
runtimeLibsDir = "{}"
ideaHome = "{}"

[cli]
dynamicOutput = false
binaryPath = "{}"

[install]
components = []
installedAt = "unix:1"
managedPaths = []
platform = "macos-aarch64"
schemaVersion = 3
shellRcPatches = []
version = "0.7.35"
"#,
            install_root.display(),
            install_root.join("runtime").display(),
            stale_runtime_libs.display(),
            temp.path().join("idea").display(),
            stale_bin.join("kast").display(),
        ),
    )
    .expect("config");

    let read_only = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("ready");
    assert!(
        !read_only.status.success(),
        "plain ready should remain read-only and report missing manifest"
    );
    assert!(!install_manifest_path(&home).exists());
    assert!(
        std::fs::read_to_string(config_home.join("config.toml"))
            .expect("config after plain ready")
            .contains("[install]")
    );

    let repair = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--fix"])
        .output()
        .expect("ready fix");

    assert!(
        repair.status.success(),
        "ready --fix should repair stale state: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert_eq!(stdout["repair"]["applied"], true);
    assert!(
        stdout["repair"]["actions"]
            .as_array()
            .expect("actions")
            .iter()
            .any(|action| action["kind"] == "remove-install-owned-config"),
        "ready --fix should remove install-owned TOML keys: {stdout}"
    );
    assert_eq!(stdout["install"]["tool"], "kast");
    assert!(install_manifest_path(&home).is_file());
    let config_after =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after repair");
    assert!(!config_after.contains("[paths]"));
    assert!(config_after.contains("[cli]"));
    assert!(config_after.contains("dynamicOutput = false"));
    assert!(!config_after.contains("[install]"));
    assert!(!config_after.contains("binaryPath"));
    assert!(!config_after.contains("runtimeLibsDir"));
    assert!(!config_after.contains("ideaHome"));
}

#[test]
fn ready_for_targets_apply_task_specific_readiness_checks() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");

    let agent = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--fix"])
        .output()
        .expect("agent ready fix");
    assert!(
        agent.status.success(),
        "default agent readiness should converge with --fix: stdout={}, stderr={}",
        String::from_utf8_lossy(&agent.stdout),
        String::from_utf8_lossy(&agent.stderr)
    );
    let agent_stdout: serde_json::Value =
        serde_json::from_slice(&agent.stdout).expect("agent ready json");
    assert_eq!(agent_stdout["target"], "agent", "{agent_stdout}");
    assert_eq!(agent_stdout["ok"], true, "{agent_stdout}");

    let kotlin = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--for", "kotlin"])
        .output()
        .expect("kotlin ready");
    assert!(
        !kotlin.status.success(),
        "kotlin readiness should fail until a semantic backend is installed"
    );
    let kotlin_stdout: serde_json::Value =
        serde_json::from_slice(&kotlin.stdout).expect("kotlin ready json");
    assert_eq!(kotlin_stdout["target"], "kotlin", "{kotlin_stdout}");
    assert_eq!(kotlin_stdout["ok"], false, "{kotlin_stdout}");
    assert!(
        kotlin_stdout["issues"]
            .as_array()
            .expect("kotlin issues")
            .iter()
            .any(|issue| issue
                .as_str()
                .expect("kotlin issue")
                .contains("installed semantic backend")),
        "{kotlin_stdout}"
    );

    std::fs::write(
        config_home.join("config.toml"),
        format!(
            "[cli]\nbinaryPath = \"{}\"\n",
            temp.path().join("missing-kast").display()
        ),
    )
    .expect("machine config");
    let machine = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("machine ready");
    assert!(
        !machine.status.success(),
        "machine readiness should fail closed for a missing configured binary"
    );
    let machine_stdout: serde_json::Value =
        serde_json::from_slice(&machine.stdout).expect("machine ready json");
    assert_eq!(machine_stdout["target"], "machine", "{machine_stdout}");
    assert_eq!(machine_stdout["ok"], false, "{machine_stdout}");
    assert!(
        machine_stdout["issues"]
            .as_array()
            .expect("machine issues")
            .iter()
            .any(|issue| issue
                .as_str()
                .expect("machine issue")
                .contains("configured kast binary")),
        "{machine_stdout}"
    );
}

#[test]
fn ready_fix_recovers_malformed_global_config_with_backup() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(config_home.join("config.toml"), "[runtime\n").expect("malformed config");

    let read_only = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("read-only ready");

    assert!(
        !read_only.status.success(),
        "read-only ready should report malformed config without changing files"
    );
    assert_eq!(
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after read-only"),
        "[runtime\n"
    );

    let apply = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--fix"])
        .output()
        .expect("ready fix");

    assert!(
        apply.status.success(),
        "ready --fix should recover malformed config: stdout={}, stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr)
    );
    let apply_stdout: serde_json::Value =
        serde_json::from_slice(&apply.stdout).expect("apply json");
    assert_eq!(apply_stdout["repair"]["applied"], true);
    assert!(
        apply_stdout["repair"]["actions"]
            .as_array()
            .expect("actions")
            .iter()
            .any(|action| action["kind"] == "recover-invalid-config"),
        "apply should report config recovery: {apply_stdout}"
    );
    let backups = apply_stdout["repair"]["backups"]
        .as_array()
        .expect("backups");
    assert!(
        !backups.is_empty(),
        "apply should preserve the malformed config"
    );
    let backup =
        std::fs::read_to_string(backups[0].as_str().expect("backup path")).expect("backup content");
    assert_eq!(backup, "[runtime\n");
    let recovered =
        std::fs::read_to_string(config_home.join("config.toml")).expect("recovered config");
    assert!(!recovered.contains("[paths]"), "{recovered}");
    assert!(!recovered.contains("installRoot = "), "{recovered}");
    assert!(!recovered.contains("binDir = "), "{recovered}");
    assert!(!recovered.contains("binaryPath = "), "{recovered}");
    recovered
        .parse::<toml::Table>()
        .expect("recovered config should be valid TOML");
    assert!(!recovered.contains("[runtime\n"), "{recovered}");
    assert!(install_manifest_path(&home).is_file());
}
