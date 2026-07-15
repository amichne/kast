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
