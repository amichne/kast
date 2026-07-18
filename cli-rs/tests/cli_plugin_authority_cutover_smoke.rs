mod support;

use support::*;

#[test]
fn retired_plugin_command_is_rejected_without_ide_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let jetbrains_root = temp.path().join("JetBrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&jetbrains_root).expect("JetBrains root");

    let output = kast(&home, &config_home)
        .args(["developer", "machine", "plugin"])
        .env("KAST_JETBRAINS_CONFIG_ROOT", &jetbrains_root)
        .output()
        .expect("retired command");

    assert!(!output.status.success());
    assert!(
        std::fs::read_dir(&jetbrains_root)
            .expect("JetBrains root")
            .next()
            .is_none(),
        "a rejected command must not create IDE profile state",
    );
}

#[cfg(target_os = "macos")]
#[test]
fn repair_migrates_exact_joint_receipt_once_to_cli_only_schema_2() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let binary = write_homebrew_kast_for_test(temp.path());
    let receipt = home.join("Library/Application Support/Kast/homebrew-install.json");
    std::fs::create_dir_all(receipt.parent().expect("receipt parent")).expect("receipt dir");
    std::fs::write(
        &receipt,
        serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 1,
            "authority": "macos-homebrew",
            "cli": {
                "binary": binary.display().to_string(),
                "formulaPrefix": binary.parent().expect("formula bin").parent().expect("formula prefix").display().to_string(),
                "version": env!("CARGO_PKG_VERSION"),
            },
            "plugin": {
                "caskToken": "amichne/kast/kast-plugin",
                "version": env!("CARGO_PKG_VERSION"),
            },
            "updatedAt": "unix:1",
        }))
        .expect("legacy receipt"),
    )
    .expect("receipt");

    let first = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("first repair");
    assert!(
        first.status.success(),
        "migration should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
    );
    let migrated: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt).expect("migrated receipt"))
            .expect("migrated JSON");
    assert_eq!(migrated["schemaVersion"], 2);
    assert!(migrated.get("plugin").is_none(), "{migrated}");

    let second = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("second repair");
    assert!(second.status.success());
    let payload: serde_json::Value =
        serde_json::from_slice(&second.stdout).expect("second repair JSON");
    assert!(
        payload["repair"]["actions"]
            .as_array()
            .expect("repair actions")
            .iter()
            .all(|action| action["kind"] != "establish-homebrew-cli-receipt"),
        "second repair must not remigrate the receipt: {payload}",
    );
}

#[cfg(target_os = "macos")]
#[test]
fn repair_recovers_exact_stale_joint_receipt_after_formula_upgrade() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let binary = write_homebrew_kast_for_test(temp.path());
    let formula_prefix = binary
        .parent()
        .expect("formula bin")
        .parent()
        .expect("formula prefix");
    let stale_version = "0.12.9";
    let stale_prefix = temp.path().join(format!("Cellar/kast/{stale_version}"));
    let receipt = home.join("Library/Application Support/Kast/homebrew-install.json");
    std::fs::create_dir_all(receipt.parent().expect("receipt parent")).expect("receipt dir");
    std::fs::write(
        &receipt,
        serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 1,
            "authority": "macos-homebrew",
            "cli": {
                "binary": stale_prefix.join("bin/kast").display().to_string(),
                "formulaPrefix": stale_prefix.display().to_string(),
                "version": stale_version,
            },
            "plugin": {
                "caskToken": "amichne/kast/kast-plugin",
                "version": stale_version,
            },
            "updatedAt": "unix:1",
        }))
        .expect("legacy receipt"),
    )
    .expect("receipt");

    let output = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .env("PATH", "")
        .output()
        .expect("repair after formula upgrade");

    assert!(
        output.status.success(),
        "stale joint receipt recovery should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let refreshed: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt).expect("refreshed receipt"))
            .expect("refreshed JSON");
    assert_eq!(refreshed["schemaVersion"], 2);
    assert_eq!(refreshed["cli"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(refreshed["cli"]["binary"], binary.display().to_string());
    assert_eq!(
        refreshed["cli"]["formulaPrefix"],
        formula_prefix.display().to_string()
    );
    assert!(refreshed.get("plugin").is_none(), "{refreshed}");
}

#[cfg(target_os = "macos")]
#[test]
fn repair_refreshes_exact_stale_schema_2_receipt_after_homebrew_upgrade() {
    use std::os::unix::fs::PermissionsExt;

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let binary = write_homebrew_kast_for_test(temp.path());
    let formula_prefix = binary
        .parent()
        .expect("formula bin")
        .parent()
        .expect("formula prefix");
    let receipt = home.join("Library/Application Support/Kast/homebrew-install.json");
    std::fs::create_dir_all(receipt.parent().expect("receipt parent")).expect("receipt dir");
    let stale_version = "0.12.9";
    let stale_prefix = temp.path().join(format!("Cellar/kast/{stale_version}"));
    std::fs::write(
        &receipt,
        serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 2,
            "authority": "macos-homebrew",
            "cli": {
                "binary": stale_prefix.join("bin/kast").display().to_string(),
                "formulaPrefix": stale_prefix.display().to_string(),
                "version": stale_version,
            },
            "updatedAt": "unix:1",
        }))
        .expect("stale receipt"),
    )
    .expect("receipt");
    let fake_bin = temp.path().join("fake-bin");
    std::fs::create_dir_all(&fake_bin).expect("fake bin");
    let brew = fake_bin.join("brew");
    std::fs::write(
        &brew,
        "#!/bin/sh\n[ \"$1\" = \"--prefix\" ] && [ \"$2\" = \"kast\" ] || exit 1\nprintf '%s\\n' \"$KAST_TEST_HOMEBREW_PREFIX\"\n",
    )
    .expect("fake brew");
    std::fs::set_permissions(&brew, std::fs::Permissions::from_mode(0o755)).expect("brew mode");
    let path = std::env::join_paths(std::iter::once(fake_bin).chain(std::env::split_paths(
        &std::env::var_os("PATH").unwrap_or_default(),
    )))
    .expect("PATH");

    let output = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .env("PATH", path)
        .env("KAST_TEST_HOMEBREW_PREFIX", formula_prefix)
        .output()
        .expect("repair after upgrade");

    assert!(
        output.status.success(),
        "receipt refresh should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let refreshed: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt).expect("refreshed receipt"))
            .expect("refreshed JSON");
    assert_eq!(refreshed["schemaVersion"], 2);
    assert_eq!(refreshed["cli"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(refreshed["cli"]["binary"], binary.display().to_string());
    assert_eq!(
        refreshed["cli"]["formulaPrefix"],
        formula_prefix.display().to_string()
    );
    assert!(refreshed.get("plugin").is_none(), "{refreshed}");
}
