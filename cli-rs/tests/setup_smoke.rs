mod support;

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
    assert!(kast_home.join("current/bin/kast").is_file());
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("user command"),
        kast_home.join("current/bin/kast"),
    );
    assert!(plugins.join("kast/lib/plugin.jar").is_file());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(
        receipt["components"],
        serde_json::json!(["cli", "idea-plugin"])
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
fn current_plugin_does_not_require_a_running_ide_to_close() {
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
    let current = command("open");

    assert!(
        current.status.success(),
        "current plugin should remain usable while IDEA runs: {}",
        String::from_utf8_lossy(&current.stdout),
    );
    let result: serde_json::Value = serde_json::from_slice(&current.stdout).expect("setup result");
    assert_eq!(result["status"], "CURRENT");
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
fn setup_migrates_only_a_missing_recommended_launch_choice() {
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
                "setup",
                "--idea-plugin",
                plugin.to_str().expect("plugin path"),
            ])
            .output()
            .expect("kast setup")
    };
    assert!(run().status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(&config, "[runtime]\ndefaultBackend = \"idea\"\n")
        .expect("legacy recommended config");
    write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin revision 2");
    assert!(run().status.success());
    assert!(
        std::fs::read_to_string(&config)
            .expect("migrated config")
            .contains("[runtime.ideaLaunch]\nenabled = true"),
    );

    std::fs::write(
        &config,
        "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = false\n",
    )
    .expect("explicit launch choice");
    write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin revision 3");
    assert!(run().status.success());
    assert!(
        std::fs::read_to_string(config)
            .expect("preserved config")
            .contains("[runtime.ideaLaunch]\nenabled = false"),
    );
}

#[test]
fn setup_user_command_tracks_manifest_active_binary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let manifest_path = source.join("manifest.json");
    let active_binary = source.join("commands/kast");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary directory");
    std::fs::rename(source.join("bin/kast"), &active_binary).expect("custom active binary");
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&manifest_path).expect("bundle manifest"))
            .expect("manifest JSON");
    manifest["activation"]["cli"]["path"] = serde_json::json!("commands/kast");
    manifest["artifacts"][0]["path"] = serde_json::json!("commands/kast");
    std::fs::write(
        &manifest_path,
        serde_json::to_vec_pretty(&manifest).expect("manifest JSON"),
    )
    .expect("updated manifest");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("user command"),
        kast_home.join("current/commands/kast"),
    );
}

#[test]
fn doctor_rejects_drifted_user_command() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let setup_output = setup(&home, &kast_home, &source);
    assert!(setup_output.status.success(), "setup should succeed");
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("remove user command");
    std::os::unix::fs::symlink("/bin/sh", &user_command).expect("retarget user command");

    let doctor = kast_at(
        &kast_home.join("current/bin/kast"),
        &home,
        &kast_home.join("unused-config"),
    )
    .env_remove("KAST_CONFIG_HOME")
    .env("KAST_HOME", &kast_home)
    .args(["--output", "json", "doctor"])
    .output()
    .expect("kast doctor");

    assert!(
        !doctor.status.success(),
        "doctor should reject command drift"
    );
    let result: serde_json::Value = serde_json::from_slice(&doctor.stdout).expect("doctor JSON");
    assert!(
        result["issues"]
            .as_array()
            .expect("doctor issues")
            .iter()
            .any(|issue| issue
                .as_str()
                .is_some_and(|issue| issue.contains("Managed user command"))),
        "{result}"
    );
}

#[test]
fn setup_rolls_back_bundle_when_user_command_projection_fails() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v1.0.0");
    let first = setup(&home, &kast_home, &first_source);
    assert!(first.status.success(), "initial setup should succeed");
    let previous = std::fs::canonicalize(kast_home.join("current")).expect("active release");
    std::fs::remove_dir_all(home.join(".local/bin")).expect("remove user bin directory");
    std::fs::write(home.join(".local/bin"), "not a directory").expect("block user command");
    let second_source = write_install_bundle_source(temp.path(), "v2.0.0");

    let failed = setup(&home, &kast_home, &second_source);

    assert!(!failed.status.success(), "command projection should fail");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("rolled-back release"),
        previous,
    );
}

#[test]
fn setup_rolls_back_idea_activation_when_user_command_projection_fails() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = temp.path().join("idea-plugins");
    let first_plugin = write_idea_plugin_zip(temp.path(), "kast-idea-v1.zip", b"plugin-v1");
    let run_setup = |plugin: &Path| {
        kast(&home, &kast_home.join("unused-config"))
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
            .expect("kast setup")
    };
    let first = run_setup(&first_plugin);
    assert!(first.status.success(), "initial setup should succeed");
    let previous = std::fs::canonicalize(kast_home.join("current")).expect("active release");
    std::fs::remove_dir_all(home.join(".local/bin")).expect("remove user bin directory");
    std::fs::write(home.join(".local/bin"), "not a directory").expect("block user command");
    let second_plugin = write_idea_plugin_zip(temp.path(), "kast-idea-v2.zip", b"plugin-v2");

    let failed = run_setup(&second_plugin);

    assert!(!failed.status.success(), "command projection should fail");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("rolled-back release"),
        previous,
    );
    assert_eq!(
        std::fs::read(plugins.join("kast/lib/plugin.jar")).expect("rolled-back plugin"),
        b"plugin-v1",
    );
}

#[test]
fn setup_persists_selected_idea_defaults() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    let defaults = temp.path().join("defaults.toml");
    let expected = "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = true\n";
    std::fs::create_dir_all(&plugins).expect("IDEA profile");
    std::fs::write(&defaults, expected).expect("selected defaults");

    let output = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .args([
            "setup",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
            "--config-defaults",
            defaults.to_str().expect("defaults path"),
        ])
        .output()
        .expect("kast setup");

    assert!(
        output.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(kast_home.join("current/config/config.toml"))
            .expect("installed defaults"),
        expected,
    );
}

#[test]
fn setup_replaces_defaults_when_release_is_current() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    let defaults = temp.path().join("defaults.toml");
    let expected = "[runtime]\ndefaultBackend = \"auto\"\n";
    std::fs::create_dir_all(&plugins).expect("IDEA profile");

    let first = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .args([
            "setup",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
        ])
        .output()
        .expect("initial setup");
    assert!(first.status.success());
    std::fs::write(&defaults, expected).expect("selected defaults");

    let second = kast(&home, &kast_home.join("unused-config"))
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", &kast_home)
        .env("KAST_MACHINE_IDE_STATE", "closed")
        .args([
            "setup",
            "--idea-plugin",
            plugin.to_str().expect("plugin path"),
            "--config-defaults",
            defaults.to_str().expect("defaults path"),
        ])
        .output()
        .expect("reconfigured setup");

    assert!(
        second.status.success(),
        "reconfiguration should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(kast_home.join("current/config/config.toml"))
            .expect("updated defaults"),
        expected,
    );
}

#[test]
fn setup_replaces_incompatible_legacy_bundle_activation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    std::fs::create_dir_all(kast_home.join("current")).expect("legacy current");
    std::fs::write(kast_home.join("current/receipt.json"), "legacy").expect("legacy receipt");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "setup should replace the legacy activation: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).expect("setup JSON");
    assert_eq!(result["status"], "ACTIVATED");
    assert!(kast_home.join("current/manifest.json").is_file());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("replacement receipt"),
    )
    .expect("replacement receipt JSON");
    assert_eq!(receipt["activeVersion"], "v9.8.7");
}

#[test]
fn setup_replaces_incompatible_legacy_idea_activation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin");
    std::fs::create_dir_all(&plugins).expect("Android Studio profile");
    let run_setup = || {
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
    let first = run_setup();
    assert!(first.status.success(), "initial setup should succeed");
    let manifest_path = kast_home.join("current/manifest.json");
    let _ = std::fs::remove_file(&manifest_path);

    let replacement = run_setup();

    assert!(
        replacement.status.success(),
        "setup should replace the incompatible activation: stdout={}, stderr={}",
        String::from_utf8_lossy(&replacement.stdout),
        String::from_utf8_lossy(&replacement.stderr),
    );
    let result: serde_json::Value =
        serde_json::from_slice(&replacement.stdout).expect("setup JSON");
    assert_eq!(result["status"], "ACTIVATED");
    assert!(manifest_path.is_file());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("replacement receipt"),
    )
    .expect("replacement receipt JSON");
    assert_eq!(receipt["manifestDigest"], test_path_sha256(&manifest_path));
    let manifest: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&manifest_path).expect("replacement manifest"))
            .expect("replacement manifest JSON");
    assert_eq!(
        manifest["artifacts"][0]["sha256"],
        test_path_sha256(&kast_home.join("current/bin/kast"))
    );
}

#[test]
fn setup_activates_one_validated_release_and_converges_on_rerun() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(kast_home.join("current")).expect("legacy current");
    std::fs::write(kast_home.join("current/junk"), "legacy").expect("legacy current file");
    std::fs::write(kast_home.join("install.json"), "legacy").expect("legacy manifest");
    std::fs::create_dir_all(home.join(".local/bin")).expect("bin");
    std::fs::write(home.join(".local/bin/kast"), "legacy").expect("legacy command");

    let first = setup(&home, &kast_home, &source);
    assert!(
        first.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
    );
    let first: serde_json::Value = serde_json::from_slice(&first.stdout).expect("setup JSON");
    assert_eq!(first["type"], "KAST_SETUP");
    assert_eq!(first["status"], "ACTIVATED");
    assert_eq!(first["verified"], true);
    let release_digest = first["releaseDigest"].as_str().expect("release digest");
    assert_eq!(release_digest.len(), 64);
    let release = kast_home.join("releases").join(release_digest);
    assert!(release.join("manifest.json").is_file());
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("current release"),
        std::fs::canonicalize(&release).expect("release"),
    );
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current/bin/kast")).expect("active command"),
        std::fs::canonicalize(release.join("bin/kast")).expect("active binary"),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("user command"),
        kast_home.join("current/bin/kast"),
    );
    assert!(!kast_home.join("install.json").exists());
    assert!(!home.join(".config/kast").exists());

    let second = setup(&home, &kast_home, &source);
    assert!(
        second.status.success(),
        "repeated setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr),
    );
    let second: serde_json::Value = serde_json::from_slice(&second.stdout).expect("setup JSON");
    assert_eq!(second["status"], "CURRENT");
    assert_eq!(second["releaseDigest"], release_digest);
    assert_eq!(second["verified"], true);

    std::fs::write(kast_home.join("staging/junk"), "stale").expect("stale staging");
    let third = setup(&home, &kast_home, &source);
    assert!(third.status.success());
    assert!(!kast_home.join("staging/junk").exists());
}

#[test]
fn setup_rolls_back_when_the_new_release_fails_readiness() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    std::fs::create_dir_all(&home).expect("home");
    let good = write_install_bundle_source(temp.path(), "v1.0.0");
    let first = setup(&home, &kast_home, &good);
    assert!(first.status.success());
    let first: serde_json::Value = serde_json::from_slice(&first.stdout).expect("setup JSON");
    let active = std::fs::canonicalize(kast_home.join("current")).expect("active release");

    let broken = write_install_bundle_source(temp.path(), "v2.0.0");
    let broken_cli = broken.join("commands/kast");
    std::fs::create_dir_all(broken_cli.parent().expect("broken CLI parent"))
        .expect("broken CLI directory");
    std::fs::rename(broken.join("bin/kast"), &broken_cli).expect("custom CLI path");
    std::fs::write(&broken_cli, "#!/bin/sh\nexit 1\n").expect("broken CLI");
    set_executable_for_test(&broken_cli);
    let manifest_path = broken.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).expect("bundle manifest"))
            .expect("manifest JSON");
    manifest["activation"]["cli"]["path"] = serde_json::json!("commands/kast");
    manifest["artifacts"][0]["path"] = serde_json::json!("commands/kast");
    manifest["artifacts"][0]["sha256"] = serde_json::Value::String(test_path_sha256(&broken_cli));
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).expect("manifest JSON"),
    )
    .expect("updated manifest");
    let failed = setup(&home, &kast_home, &broken);
    assert!(!failed.status.success(), "broken release must fail setup");
    let failed: serde_json::Value = serde_json::from_slice(&failed.stdout).expect("failure JSON");
    assert_eq!(failed["code"], "SETUP_VERIFY_FAILED");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("rolled-back release"),
        active,
    );
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current/bin/kast")).expect("active command"),
        active.join("bin/kast"),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("user command"),
        kast_home.join("current/bin/kast"),
    );
    assert_eq!(
        first["releaseDigest"],
        std::fs::read_link(kast_home.join("previous"))
            .expect("previous release")
            .file_name()
            .and_then(|name| name.to_str())
            .expect("previous digest"),
    );
}

#[test]
fn concurrent_setup_serializes_on_one_release() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v3.0.0");
    std::fs::create_dir_all(&home).expect("home");

    let first = setup_command(&home, &kast_home, &source)
        .spawn()
        .expect("first setup");
    let second = setup_command(&home, &kast_home, &source)
        .spawn()
        .expect("second setup");
    let first = first.wait_with_output().expect("first output");
    let second = second.wait_with_output().expect("second output");
    assert!(
        first.status.success(),
        "first setup: {}",
        String::from_utf8_lossy(&first.stdout)
    );
    assert!(
        second.status.success(),
        "second setup: {}",
        String::from_utf8_lossy(&second.stdout)
    );
    let statuses = [first, second]
        .map(|output| {
            serde_json::from_slice::<serde_json::Value>(&output.stdout).expect("setup JSON")
        })
        .map(|value| value["status"].as_str().expect("status").to_string());
    assert!(statuses.contains(&"ACTIVATED".to_string()), "{statuses:?}");
    assert!(statuses.contains(&"CURRENT".to_string()), "{statuses:?}");
}

#[test]
fn setup_rejects_a_modified_artifact_before_activation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    std::fs::create_dir_all(&home).expect("home");
    let good = write_install_bundle_source(temp.path(), "v4.0.0");
    assert!(setup(&home, &kast_home, &good).status.success());
    let active = std::fs::canonicalize(kast_home.join("current")).expect("active release");

    let modified = write_install_bundle_source(temp.path(), "v4.1.0");
    std::fs::write(modified.join("plugins/kast.zip"), "modified").expect("drift");
    let rejected = setup(&home, &kast_home, &modified);
    assert!(!rejected.status.success());
    let rejected: serde_json::Value = serde_json::from_slice(&rejected.stdout).expect("error JSON");
    assert_eq!(rejected["code"], "BUNDLE_ARTIFACT_MISMATCH");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("unchanged release"),
        active,
    );
}

#[test]
fn setup_is_the_only_public_installation_mutator() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let root_help = kast(&home, &config)
        .arg("--help")
        .output()
        .expect("root help");
    assert!(root_help.status.success());
    let root_help = String::from_utf8_lossy(&root_help.stdout);
    assert!(root_help.contains("\n  setup "), "{root_help}");
    for retired in ["\n  repair ", "\n  machine "] {
        assert!(
            !root_help.contains(retired),
            "retired command remains: {root_help}"
        );
    }

    let setup_help = kast(&home, &config)
        .args(["setup", "--help"])
        .output()
        .expect("setup help");
    assert!(setup_help.status.success());
    let setup_help = String::from_utf8_lossy(&setup_help.stdout);
    assert!(setup_help.contains("--source"), "{setup_help}");
    for retired in ["--workspace-root", "--force", "--dry-run"] {
        assert!(
            !setup_help.contains(retired),
            "retired setup option remains: {setup_help}"
        );
    }

    let release_help = kast(&home, &config)
        .args(["developer", "release", "--help"])
        .output()
        .expect("release help");
    assert!(release_help.status.success());
    assert!(
        !String::from_utf8_lossy(&release_help.stdout).contains("\n  activate "),
        "direct bundle activation remains public",
    );
}
