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
fn repair_migrates_exact_joint_receipt_once_to_revision_bound_schema_3() {
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
    assert_eq!(migrated["schemaVersion"], 3);
    assert_eq!(
        migrated["cli"]["releaseRevision"],
        env!("KAST_RELEASE_REVISION"),
    );
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
    assert_eq!(refreshed["schemaVersion"], 3);
    assert_eq!(refreshed["cli"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(
        refreshed["cli"]["releaseRevision"],
        env!("KAST_RELEASE_REVISION"),
    );
    assert_eq!(
        refreshed["cli"]["binary"],
        std::fs::canonicalize(&binary)
            .expect("canonical binary")
            .display()
            .to_string()
    );
    assert_eq!(
        refreshed["cli"]["formulaPrefix"],
        std::fs::canonicalize(formula_prefix)
            .expect("canonical formula prefix")
            .display()
            .to_string()
    );
    assert!(refreshed.get("plugin").is_none(), "{refreshed}");
}

#[cfg(target_os = "macos")]
#[test]
fn repair_plans_schema_2_recovery_without_mutation() {
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
    let original = serde_json::to_vec_pretty(&serde_json::json!({
        "schemaVersion": 2,
        "authority": "macos-homebrew",
        "cli": {
            "binary": binary.display().to_string(),
            "formulaPrefix": formula_prefix.display().to_string(),
            "version": env!("CARGO_PKG_VERSION"),
        },
        "updatedAt": "unix:1",
    }))
    .expect("stale receipt");
    std::fs::write(&receipt, &original).expect("receipt");

    let output = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine"])
        .env("PATH", "")
        .output()
        .expect("schema-2 repair plan");

    assert!(!output.status.success(), "authority is not active yet");
    let payload: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("repair plan JSON");
    assert_eq!(payload["type"], "KAST_REPAIR", "{payload:#}");
    assert_eq!(
        payload["ready"]["authorityResolution"]["state"], "RECOVERABLE",
        "{payload:#}",
    );
    assert_eq!(
        payload["ready"]["authorityResolution"]["plan"]["applyCommand"],
        format!(
            "'{}' repair --for machine --apply",
            binary.display().to_string().replace('\'', "'\\''")
        ),
        "the recovery plan must retain the proven Cellar binary: {payload:#}",
    );
    assert!(
        payload["repair"]["actions"]
            .as_array()
            .expect("repair actions")
            .iter()
            .any(|action| {
                action["kind"] == "establish-homebrew-cli-receipt" && action["status"] == "planned"
            }),
        "{payload:#}",
    );
    assert_eq!(
        std::fs::read(&receipt).expect("preserved receipt"),
        original
    );
}

#[cfg(target_os = "macos")]
#[test]
fn explicit_homebrew_receipt_reset_preserves_unknown_bytes_and_restores_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let binary = write_homebrew_kast_for_test(temp.path());
    let receipt = home.join("Library/Application Support/Kast/homebrew-install.json");
    std::fs::create_dir_all(receipt.parent().expect("receipt parent")).expect("receipt dir");
    let original = b"unknown receipt bytes\n";
    std::fs::write(&receipt, original).expect("unknown receipt");

    let ordinary = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("ordinary repair");
    assert!(
        !ordinary.status.success(),
        "ordinary repair must fail closed"
    );
    let ordinary_diagnostic = format!(
        "{}{}",
        String::from_utf8_lossy(&ordinary.stdout),
        String::from_utf8_lossy(&ordinary.stderr),
    );
    assert!(
        ordinary_diagnostic.contains("--reset-homebrew-receipt"),
        "blocked authority must name its explicit recovery path: {ordinary_diagnostic}"
    );
    assert_eq!(
        std::fs::read(&receipt).expect("preserved receipt"),
        original
    );

    let plan = kast_at(&binary, &home, &config_home)
        .args([
            "--output",
            "json",
            "repair",
            "--for",
            "machine",
            "--reset-homebrew-receipt",
        ])
        .output()
        .expect("explicit reset plan");
    assert!(
        !plan.status.success(),
        "a reset plan is not active authority"
    );
    let plan_payload: serde_json::Value =
        serde_json::from_slice(&plan.stdout).expect("reset plan JSON");
    let planned_reset = plan_payload["repair"]["actions"]
        .as_array()
        .expect("repair actions")
        .iter()
        .find(|action| action["kind"] == "reset-homebrew-cli-receipt")
        .unwrap_or_else(|| panic!("missing planned reset: {plan_payload:#}"));
    assert_eq!(planned_reset["status"], "planned", "{plan_payload:#}");
    assert_eq!(
        planned_reset["command"],
        format!(
            "'{}' repair --for machine --reset-homebrew-receipt --apply",
            binary.display().to_string().replace('\'', "'\\''")
        ),
        "{plan_payload:#}",
    );
    assert_eq!(
        std::fs::read(&receipt).expect("receipt after reset plan"),
        original
    );

    let reset = kast_at(&binary, &home, &config_home)
        .args([
            "--output",
            "json",
            "repair",
            "--for",
            "machine",
            "--reset-homebrew-receipt",
            "--apply",
        ])
        .output()
        .expect("explicit reset");
    assert!(
        reset.status.success(),
        "explicit reset should restore authority: stdout={}, stderr={}",
        String::from_utf8_lossy(&reset.stdout),
        String::from_utf8_lossy(&reset.stderr),
    );
    let payload: serde_json::Value =
        serde_json::from_slice(&reset.stdout).expect("reset result JSON");
    assert_eq!(payload["ready"]["authorityResolution"]["state"], "ACTIVE");
    let action = payload["repair"]["actions"]
        .as_array()
        .expect("repair actions")
        .iter()
        .find(|action| action["kind"] == "reset-homebrew-cli-receipt")
        .unwrap_or_else(|| panic!("missing reset action: {payload:#}"));
    assert_eq!(action["status"], "applied", "{payload:#}");
    let planned_kinds = plan_payload["repair"]["actions"]
        .as_array()
        .expect("planned actions")
        .iter()
        .map(|action| action["kind"].clone())
        .collect::<Vec<_>>();
    let applied_kinds = payload["repair"]["actions"]
        .as_array()
        .expect("applied actions")
        .iter()
        .map(|action| action["kind"].clone())
        .collect::<Vec<_>>();
    assert_eq!(
        applied_kinds, planned_kinds,
        "reset dry-run and apply must describe the same transaction"
    );
    let backup = payload["repair"]["backups"]
        .as_array()
        .expect("backups")
        .iter()
        .find_map(serde_json::Value::as_str)
        .expect("receipt backup");
    assert_eq!(std::fs::read(backup).expect("backup bytes"), original);
    let refreshed: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt).expect("refreshed receipt"))
            .expect("refreshed JSON");
    assert_eq!(refreshed["schemaVersion"], 3);
    assert_eq!(
        refreshed["cli"]["releaseRevision"],
        env!("KAST_RELEASE_REVISION")
    );
}

#[cfg(target_os = "macos")]
#[test]
fn repair_migrates_exact_schema_2_receipt_without_revision() {
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
    std::fs::write(
        &receipt,
        serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 2,
            "authority": "macos-homebrew",
            "cli": {
                "binary": binary.display().to_string(),
                "formulaPrefix": formula_prefix.display().to_string(),
                "version": env!("CARGO_PKG_VERSION"),
            },
            "updatedAt": "unix:1",
        }))
        .expect("stale receipt"),
    )
    .expect("receipt");
    let output = kast_at(&binary, &home, &config_home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .env("PATH", "")
        .output()
        .expect("schema-2 migration");

    assert!(
        output.status.success(),
        "schema-2 migration should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let refreshed: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt).expect("refreshed receipt"))
            .expect("refreshed JSON");
    assert_eq!(refreshed["schemaVersion"], 3);
    assert_eq!(refreshed["cli"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(
        refreshed["cli"]["releaseRevision"],
        env!("KAST_RELEASE_REVISION"),
    );
    assert_eq!(
        refreshed["cli"]["binary"],
        std::fs::canonicalize(&binary)
            .expect("canonical binary")
            .display()
            .to_string()
    );
    assert_eq!(
        refreshed["cli"]["formulaPrefix"],
        std::fs::canonicalize(formula_prefix)
            .expect("canonical formula prefix")
            .display()
            .to_string()
    );
    assert!(refreshed.get("plugin").is_none(), "{refreshed}");
}
