use std::io::Write;
use std::process::Stdio;
use support::*;

fn write_idea_plugin_zip(root: &Path, name: &str, contents: &[u8]) -> PathBuf {
    let archive = root.join(name);
    let file = std::fs::File::create(&archive).expect("plugin archive");
    let mut zip = zip::ZipWriter::new(file);
    zip.start_file(
        "kast/lib/plugin.jar",
        zip::write::SimpleFileOptions::default(),
    )
    .expect("plugin entry");
    zip.write_all(contents).expect("plugin contents");
    zip.finish().expect("plugin archive");
    archive
}

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
fn setup_installs_native_cli_and_idea_plugin() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(&plugins).expect("Android Studio profile");

    let output = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .args([
            "--output",
            "json",
            "setup",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
        ])
        .output()
        .expect("kast setup");

    assert!(
        output.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(kast_home.join("current/bin/_kastctl").is_file());
    assert_eq!(
        std::fs::read(kast_home.join("current/bin/_kastctl")).expect("_kastctl bytes"),
        std::fs::read(kast_home.join("current/bin/kast")).expect("kast bytes"),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("user command"),
        kast_home.join("current/bin/kast"),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/_kastctl")).expect("control user command"),
        kast_home.join("current/bin/_kastctl"),
    );
    assert!(plugins.join("kast/lib/plugin.jar").is_file());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(
        receipt["components"],
        serde_json::json!(["cli", "agent-cli", "idea-plugin"])
    );
    let platform = match std::env::consts::ARCH {
        "aarch64" => "macos-arm64".to_string(),
        "x86_64" => "macos-x64".to_string(),
        arch => format!("macos-{arch}"),
    };
    assert_eq!(receipt["platform"], platform);
    assert!(
        std::fs::read_to_string(kast_home.join("current/config/config.toml"))
            .expect("installed defaults")
            .contains("[runtime.ideaLaunch]\nenabled = true"),
    );
}

#[test]
fn current_idea_setup_archives_a_restored_unmanaged_user_command() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(&plugins).expect("IDEA profile");
    let run = || {
        kast(&home, &kast_home.join("unused-config"))
            .env_remove("KAST_CONFIG_HOME")
            .env("KAST_HOME", &kast_home)
            .env("KAST_MACHINE_IDE_STATE", "closed")
            .args([
                "--output",
                "json",
                "setup",
                "--idea-plugin",
                plugin.to_str().expect("plugin path"),
            ])
            .output()
            .expect("kast setup")
    };

    assert!(run().status.success(), "initial setup should succeed");
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("managed user command");
    std::fs::write(&user_command, "unmanaged").expect("unmanaged user command");

    let current = run();

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
fn failed_current_idea_setup_preserves_unrelated_legacy_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(&plugins).expect("IDEA profile");
    let run = || {
        kast(&home, &kast_home.join("unused-config"))
            .env_remove("KAST_CONFIG_HOME")
            .env("KAST_HOME", &kast_home)
            .env("KAST_MACHINE_IDE_STATE", "closed")
            .args([
                "--output",
                "json",
                "setup",
                "--idea-plugin",
                plugin.to_str().expect("plugin path"),
            ])
            .output()
            .expect("kast setup")
    };

    assert!(run().status.success(), "initial setup should succeed");
    let local_bin = home.join(".local/bin");
    std::fs::remove_file(local_bin.join("kast")).expect("managed user command");
    std::fs::remove_file(local_bin.join("_kastctl")).expect("managed control command");
    std::fs::remove_dir(&local_bin).expect("empty command directory");
    std::fs::write(&local_bin, "blocks command projection").expect("blocking command path");
    let legacy_config = home.join(".config/kast/config.toml");
    std::fs::create_dir_all(legacy_config.parent().expect("legacy config parent"))
        .expect("legacy config directory");
    std::fs::write(&legacy_config, "legacy").expect("legacy config");

    let failed = run();

    assert!(!failed.status.success(), "command projection should fail");
    assert_eq!(
        std::fs::read_to_string(legacy_config).expect("preserved legacy config"),
        "legacy",
    );
}

#[test]
fn setup_rejects_multiple_supported_plugin_profiles_without_selection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let idea = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let android = home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(idea).expect("IDEA profile");
    std::fs::create_dir_all(android).expect("Android Studio profile");

    let output = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
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
    assert_eq!(result["code"], "IDE_PROFILE_AMBIGUOUS");
}

#[test]
fn changed_cli_requires_a_running_ide_to_close() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(&plugins).expect("IDEA profile");
    let command = |state: &str| {
        kast(&home, &kast_home.join("unused-config"))
            .env_remove("KAST_CONFIG_HOME")
            .env("KAST_HOME", &kast_home)
            .env("KAST_MACHINE_IDE_STATE", state)
            .args([
                "--output",
                "json",
                "setup",
                "--idea-plugin",
                plugin.to_str().expect("plugin path"),
            ])
            .output()
            .expect("kast setup")
    };

    assert!(
        command("closed").status.success(),
        "initial setup should succeed",
    );
    std::fs::write(kast_home.join("current/bin/kast"), "drifted CLI").expect("drift active CLI");
    let blocked = command("open");

    assert!(!blocked.status.success());
    let result: serde_json::Value = serde_json::from_slice(&blocked.stdout).expect("setup failure");
    assert_eq!(result["code"], "IDE_RESTART_REQUIRED");
    assert_eq!(
        std::fs::read(plugins.join("kast/lib/plugin.jar")).expect("installed plugin"),
        b"plugin",
    );
}

#[test]
fn changed_plugin_requires_a_running_ide_to_close() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(&plugins).expect("Android Studio profile");
    let run = |state: &str| {
        kast(&home, &kast_home.join("unused-config"))
            .env_remove("KAST_CONFIG_HOME")
            .env("KAST_HOME", &kast_home)
            .env("KAST_MACHINE_IDE_STATE", state)
            .args([
                "--output",
                "json",
                "setup",
                "--idea-plugin",
                plugin.to_str().expect("plugin path"),
            ])
            .output()
            .expect("kast setup")
    };
    assert!(run("closed").status.success());
    write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"updated plugin");

    let blocked = run("open");

    assert!(!blocked.status.success());
    let result: serde_json::Value = serde_json::from_slice(&blocked.stdout).expect("setup failure");
    assert_eq!(result["code"], "IDE_RESTART_REQUIRED");
    assert_eq!(
        std::fs::read(plugins.join("kast/lib/plugin.jar")).expect("installed plugin"),
        b"plugin",
    );
}

#[test]
fn macos_idea_setup_closes_plugin_and_config_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let headless = write_install_bundle_source(temp.path(), "v9.8.7");
    let headless_setup = setup(&home, &kast_home, &headless);
    assert!(
        headless_setup.status.success(),
        "headless setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&headless_setup.stdout),
        String::from_utf8_lossy(&headless_setup.stderr),
    );
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    std::fs::create_dir_all(plugins.join("kast/lib")).expect("existing IDEA plugin");
    std::fs::write(plugins.join("kast/lib/plugin.jar"), b"old plugin")
        .expect("existing plugin contents");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"new plugin");

    let output = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .args([
            "setup",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
            "--idea-plugins-dir",
            plugins.to_str().expect("plugins path"),
        ])
        .output()
        .expect("kast setup");

    assert!(
        output.status.success(),
        "IDEA setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let mut plugin_backups = std::fs::read_dir(&plugins)
        .expect("plugins directory")
        .filter_map(std::result::Result::ok)
        .map(|entry| entry.file_name())
        .filter(|name| name.to_string_lossy().starts_with(".kast-backup-"))
        .collect::<Vec<_>>();
    plugin_backups.sort();
    let config = std::fs::read_to_string(kast_home.join("current/config/config.toml"))
        .expect("IDEA defaults");
    assert_eq!(
        (plugin_backups, config),
        (
            vec![],
            "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = true\n\n[backends.headless]\nenabled = false\n\n[backends.idea]\nenabled = true\n"
                .to_string(),
        ),
    );
}
