use std::process::Stdio;
use support::*;

fn setup_command(home: &Path, kast_home: &Path, source: &Path) -> Command {
    let mut command = kast(home, &kast_home.join("unused-config"));
    command
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", kast_home)
        .args([
            "--output",
            "json",
            "setup",
            "--source",
            source.to_str().expect("bundle source"),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    command
}

fn setup(home: &Path, kast_home: &Path, source: &Path) -> std::process::Output {
    setup_command(home, kast_home, source)
        .output()
        .expect("kast setup")
}

#[test]
fn setup_installs_one_headless_release_without_a_public_plugin() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).expect("setup result");
    assert_eq!(
        result["artifacts"]
            .as_array()
            .expect("setup artifacts")
            .iter()
            .map(|artifact| artifact["role"].as_str().expect("artifact role"))
            .collect::<Vec<_>>(),
        vec!["cli", "agent-cli", "headless-backend"],
    );
    assert!(kast_home.join("current/libexec/kastctl").is_file());
    assert_eq!(
        std::fs::read(kast_home.join("current/libexec/kastctl")).expect("kastctl bytes"),
        std::fs::read(kast_home.join("current/bin/kast")).expect("kast bytes"),
    );
    assert!(!kast_home.join("current/plugins").exists());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(
        receipt["components"],
        serde_json::json!(["cli", "headless-backend", "manifest"]),
    );
    let config = std::fs::read_to_string(kast_home.join("current/config/config.toml"))
        .expect("installed config");
    assert!(config.contains("defaultBackend = \"headless\""));
    assert!(!config.contains("defaultBackend = \"idea\""));
}

#[test]
fn current_setup_archives_a_restored_unmanaged_user_command() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    assert!(setup(&home, &kast_home, &source).status.success());
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("managed user command");
    std::fs::write(&user_command, "unmanaged").expect("unmanaged user command");

    let current = setup(&home, &kast_home, &source);

    assert!(
        current.status.success(),
        "current setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&current.stdout),
        String::from_utf8_lossy(&current.stderr),
    );
    let result: serde_json::Value = serde_json::from_slice(&current.stdout).expect("setup result");
    let backup = kast_home.join("backups/legacy-local-bin-kast");
    assert_eq!(result["status"], "CURRENT");
    assert_eq!(result["backup"], backup.display().to_string());
    assert_eq!(
        std::fs::read_to_string(backup).expect("archived unmanaged command"),
        "unmanaged",
    );
    assert_eq!(
        std::fs::read_link(user_command).expect("restored managed user command"),
        kast_home.join("current/bin/kast"),
    );
}

#[test]
fn failed_current_setup_preserves_unrelated_legacy_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    assert!(setup(&home, &kast_home, &source).status.success());
    let local_bin = home.join(".local/bin");
    std::fs::remove_file(local_bin.join("kast")).expect("managed user command");
    std::fs::remove_dir(&local_bin).expect("empty command directory");
    std::fs::write(&local_bin, "blocks command projection").expect("blocking command path");
    let legacy_config = home.join(".config/kast/config.toml");
    std::fs::create_dir_all(legacy_config.parent().expect("legacy config parent"))
        .expect("legacy config directory");
    std::fs::write(&legacy_config, "legacy").expect("legacy config");

    let failed = setup(&home, &kast_home, &source);

    assert!(!failed.status.success(), "command projection should fail");
    assert_eq!(
        std::fs::read_to_string(legacy_config).expect("preserved legacy config"),
        "legacy",
    );
}

#[test]
fn ordinary_setup_removes_known_public_plugins_without_controlling_the_ide() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let idea_plugins =
        home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let android_plugins =
        home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    for plugins in [&idea_plugins, &android_plugins] {
        std::fs::create_dir_all(plugins.join("kast/lib")).expect("legacy public plugin");
        std::fs::write(plugins.join("kast/lib/plugin.jar"), "legacy").expect("legacy plugin");
        std::fs::create_dir_all(plugins.join("unrelated")).expect("unrelated plugin");
    }

    let output = setup_command(&home, &kast_home, &source)
        .env("KAST_MACHINE_IDE_STATE", "open")
        .output()
        .expect("kast setup");

    assert!(
        output.status.success(),
        "setup must not require foreground IDE control: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("setup migration result");
    assert_eq!(
        result["restartRequirement"]["code"],
        "FOREGROUND_IDE_RESTART_REQUIRED",
    );
    for plugins in [&idea_plugins, &android_plugins] {
        assert!(!plugins.join("kast").exists(), "legacy public plugin removed");
        assert!(plugins.join("unrelated").is_dir(), "unrelated plugin preserved");
    }
}

#[test]
fn ordinary_setup_persists_the_central_legacy_backend_patch() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "# keep this operator note\n[runtime]\ndefaultBackend = \"idea\"\n\n[server]\nmaxResults = 321\n",
    )
    .expect("legacy configuration");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "migration should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let migrated = std::fs::read_to_string(config).expect("migrated configuration");
    assert!(migrated.contains("defaultBackend = \"headless\""));
    assert!(!migrated.contains("defaultBackend = \"idea\""));
    assert!(migrated.contains("# keep this operator note"));
    assert!(migrated.contains("maxResults = 321"));
}

#[test]
fn setup_rejects_the_retired_idea_plugin_ingress() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugin = temp.path().join("kast-idea.zip");
    std::fs::write(&plugin, "retired").expect("retired plugin input");

    let output = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .args([
            "--output",
            "json",
            "setup",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
        ])
        .output()
        .expect("kast setup");

    assert!(!output.status.success());
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).expect("setup failure");
    assert_eq!(result["code"], "CLI_USAGE");
    assert!(result["message"].as_str().is_some_and(|message| {
        message.contains("unexpected argument '--idea-plugin'")
    }));
}
