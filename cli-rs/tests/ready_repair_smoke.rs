mod support;

use support::*;

#[cfg(target_os = "macos")]
#[test]
fn machine_ready_prefers_homebrew_receipt_without_local_manifest() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);

    let ready = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("machine ready");

    assert!(
        ready.status.success(),
        "Homebrew receipt should satisfy machine readiness: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("ready json");
    assert_eq!(stdout["installAuthority"], "macos-homebrew", "{stdout}");
    assert_eq!(
        stdout["homebrewInstall"]["cli"]["binary"],
        homebrew_binary.display().to_string(),
        "{stdout}"
    );
    assert_eq!(
        stdout["binary"]["configuredMatchesRunning"], true,
        "{stdout}"
    );

    let human = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "human", "ready", "--for", "machine"])
        .output()
        .expect("human machine ready");
    assert!(human.status.success(), "human readiness should succeed");
    let human_stdout = String::from_utf8_lossy(&human.stdout);
    assert!(
        human_stdout.contains("Install authority: macos-homebrew"),
        "{human_stdout}"
    );
    assert!(
        human_stdout.contains("macOS Homebrew authority"),
        "{human_stdout}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn machine_ready_rejects_a_stale_homebrew_receipt_with_a_stable_code() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    let receipt = write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    let mut document: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt).expect("receipt bytes"))
            .expect("receipt json");
    document["cli"]["version"] = "0.0.0".into();
    std::fs::write(
        &receipt,
        serde_json::to_vec_pretty(&document).expect("stale receipt json"),
    )
    .expect("stale receipt");

    let ready = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("machine ready");

    assert!(!ready.status.success(), "stale authority must fail closed");
    let stdout: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("error json");
    assert_eq!(
        stdout["code"], "MACOS_HOMEBREW_RECEIPT_VERSION_MISMATCH",
        "{stdout}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn machine_ready_and_repair_reject_same_version_receipt_for_another_binary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let running_binary = write_homebrew_kast_for_test(&temp.path().join("running"));
    let forged_binary = write_homebrew_kast_for_test(&temp.path().join("forged"));
    let receipt = write_macos_homebrew_receipt_for_test(&home, &forged_binary);
    let original = std::fs::read(&receipt).expect("receipt bytes");

    for arguments in [
        vec!["--output", "json", "ready", "--for", "machine"],
        vec!["--output", "json", "repair", "--for", "machine", "--apply"],
    ] {
        let output = kast_at(&running_binary, &home, &config_home)
            .args(arguments)
            .output()
            .expect("Kast command");
        assert!(
            !output.status.success(),
            "forged authority must fail closed"
        );
        let payload: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("error JSON");
        assert_eq!(
            payload["code"], "MACOS_HOMEBREW_RECEIPT_BINARY_MISMATCH",
            "{payload}",
        );
        assert_eq!(
            std::fs::read(&receipt).expect("preserved receipt"),
            original,
            "failed authority proof must not rewrite the receipt",
        );
    }
}

#[cfg(target_os = "macos")]
#[test]
fn machine_repair_does_not_create_managed_local_install_in_homebrew_mode() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);

    let repair = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("machine repair");

    assert!(
        repair.status.success(),
        "Homebrew repair should remain healthy: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr),
    );
    assert!(!install_manifest_path(&home).exists());
    assert!(!default_bin_dir(&home).join("kast").exists());
}

#[cfg(target_os = "macos")]
#[test]
fn machine_repair_backs_up_and_retires_confirmed_legacy_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    let legacy_shim = write_legacy_local_install_for_test(&home, &config_home);

    let repair = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("machine repair");

    assert!(
        repair.status.success(),
        "legacy cleanup should preserve Homebrew health: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert!(
        stdout["repair"]["actions"]
            .as_array()
            .expect("repair actions")
            .iter()
            .any(|action| action["kind"] == "retire-legacy-macos-install"),
        "{stdout}"
    );
    assert!(!legacy_shim.exists());
    assert!(!install_manifest_path(&home).exists());
    assert!(
        stdout["repair"]["backups"]
            .as_array()
            .expect("backups")
            .len()
            >= 2,
        "{stdout}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn machine_repair_preserves_an_unrecognized_legacy_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    let legacy_shim = write_legacy_local_install_for_test(&home, &config_home);
    std::fs::write(&legacy_shim, b"#!/bin/sh\nprintf 'user-owned kast\\n'\n")
        .expect("replace shim with unrecognized contents");

    let repair = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("machine repair");

    assert!(
        repair.status.success(),
        "unknown legacy state should remain a non-blocking warning: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&legacy_shim).expect("preserved shim"),
        "#!/bin/sh\nprintf 'user-owned kast\\n'\n"
    );
    assert!(
        install_manifest_path(&home).is_file(),
        "repair must preserve the manifest when it cannot prove the whole legacy identity is managed"
    );
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert!(
        stdout["repair"]["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .any(|warning| warning
                .as_str()
                .is_some_and(|warning| warning.contains("not a confirmed Kast-managed shim"))),
        "repair must report why it preserved the legacy identity: {stdout}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn machine_repair_warns_when_legacy_identity_is_not_writable() {
    use std::os::unix::fs::PermissionsExt;

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    let legacy_shim = write_legacy_local_install_for_test(&home, &config_home);
    let legacy_bin_dir = legacy_shim.parent().expect("legacy bin dir");
    let legacy_manifest = install_manifest_path(&home);
    let legacy_install_root = legacy_manifest.parent().expect("legacy install root");
    std::fs::set_permissions(legacy_bin_dir, std::fs::Permissions::from_mode(0o555))
        .expect("lock bin dir");
    std::fs::set_permissions(legacy_install_root, std::fs::Permissions::from_mode(0o555))
        .expect("lock install root");

    let repair = kast_at(&homebrew_binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("machine repair");

    std::fs::set_permissions(legacy_bin_dir, std::fs::Permissions::from_mode(0o755))
        .expect("unlock bin dir");
    std::fs::set_permissions(legacy_install_root, std::fs::Permissions::from_mode(0o755))
        .expect("unlock install root");
    assert!(
        repair.status.success(),
        "locked inactive legacy paths should not block Homebrew: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert!(
        stdout["repair"]["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .any(|warning| warning
                .as_str()
                .expect("warning")
                .contains("leaving inactive legacy path unchanged")),
        "{stdout}"
    );
    assert!(legacy_shim.exists());
    assert!(install_manifest_path(&home).exists());
}

#[cfg(target_os = "macos")]
#[test]
fn machine_ready_offers_cleanup_only_for_confirmed_legacy_shadow() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    let homebrew_binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &homebrew_binary);
    let legacy_shim = write_legacy_local_install_for_test(&home, &config_home);
    let path = std::env::join_paths([
        legacy_shim.parent().expect("legacy bin"),
        homebrew_binary.parent().expect("Homebrew bin"),
    ])
    .expect("PATH");

    let ready = kast_at(&homebrew_binary, &home, &config_home)
        .env("PATH", path)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("machine ready");

    assert!(ready.status.success(), "legacy shadow is non-blocking");
    let stdout: serde_json::Value = serde_json::from_slice(&ready.stdout).expect("ready json");
    assert_eq!(
        stdout["legacyShadow"]["path"],
        legacy_shim.display().to_string(),
        "{stdout}"
    );
    assert_eq!(stdout["legacyShadow"]["managed"], true, "{stdout}");
    assert_eq!(stdout["legacyShadow"]["writable"], true, "{stdout}");
    assert_eq!(stdout["legacyShadow"]["homebrewIsNext"], true, "{stdout}");
    assert_eq!(
        stdout["legacyShadow"]["cleanupCommand"],
        format!(
            "'{}' repair --for machine --apply && hash -r",
            homebrew_binary.display()
        ),
        "{stdout}"
    );
}

#[test]
fn repair_apply_writes_manifest_and_removes_install_owned_config() {
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

    let retired_fix = kast(&home, &config_home)
        .args(["--output", "json", "ready", "--fix"])
        .output()
        .expect("retired ready fix");
    assert!(
        !retired_fix.status.success(),
        "ready --fix should be rejected as a usage error"
    );
    assert!(!install_manifest_path(&home).exists());

    let repair = kast(&home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("repair apply");

    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert!(
        !repair.status.success(),
        "machine readiness should fail closed when the repaired shim differs from the running test binary"
    );
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert!(
        stdout["ready"]["issues"]
            .as_array()
            .expect("ready issues")
            .iter()
            .any(|issue| issue
                .as_str()
                .expect("ready issue")
                .contains("configured kast binary")),
        "{stdout}"
    );
    assert_eq!(stdout["repair"]["applied"], true);
    assert!(
        stdout["repair"]["actions"]
            .as_array()
            .expect("actions")
            .iter()
            .any(|action| action["kind"] == "remove-install-owned-config"),
        "repair --apply should remove install-owned TOML keys: {stdout}"
    );
    assert_eq!(stdout["ready"]["install"]["tool"], "kast");
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
        .args(["--output", "json", "repair", "--apply"])
        .output()
        .expect("agent repair apply");
    let agent_stdout: serde_json::Value =
        serde_json::from_slice(&agent.stdout).expect("agent repair json");
    assert_eq!(agent_stdout["target"], "agent", "{agent_stdout}");
    if cfg!(target_os = "macos") {
        assert!(
            !agent.status.success(),
            "macOS agent readiness should require plugin workspace metadata"
        );
        assert_eq!(agent_stdout["ok"], false, "{agent_stdout}");
        assert!(
            agent_stdout["ready"]["issues"]
                .as_array()
                .expect("agent issues")
                .iter()
                .any(|issue| issue
                    .as_str()
                    .expect("agent issue")
                    .contains("plugin-prepared workspace metadata")
                    || issue
                        .as_str()
                        .expect("agent issue")
                        .contains("workspace metadata")),
            "{agent_stdout}"
        );
    } else {
        assert!(
            agent.status.success(),
            "default agent readiness should converge with repair --apply: stdout={}, stderr={}",
            String::from_utf8_lossy(&agent.stdout),
            String::from_utf8_lossy(&agent.stderr)
        );
        assert_eq!(agent_stdout["ok"], true, "{agent_stdout}");
    }

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
fn repair_apply_recovers_malformed_global_config_with_backup() {
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
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("repair apply");

    let apply_stdout: serde_json::Value =
        serde_json::from_slice(&apply.stdout).expect("apply json");
    assert!(
        !apply.status.success(),
        "machine readiness should fail closed when the repaired shim differs from the running test binary"
    );
    assert_eq!(apply_stdout["ok"], false, "{apply_stdout}");
    assert!(
        apply_stdout["ready"]["issues"]
            .as_array()
            .expect("ready issues")
            .iter()
            .any(|issue| issue
                .as_str()
                .expect("ready issue")
                .contains("configured kast binary")),
        "{apply_stdout}"
    );
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
