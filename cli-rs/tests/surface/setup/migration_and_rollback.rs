#[test]
fn macos_idea_setup_repairs_headless_defaults_from_prior_broken_setup() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let plugins = home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    std::fs::create_dir_all(&plugins).expect("IDEA profile");
    let plugin = write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin revision 1");
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
    assert!(run().status.success(), "initial setup should succeed");
    std::fs::write(
        kast_home.join("current/config/config.toml"),
        "[runtime]\ndefaultBackend = \"headless\"\n\n[backends.headless]\nenabled = true\n",
    )
    .expect("broken headless defaults");
    write_idea_plugin_zip(temp.path(), "kast-idea.zip", b"plugin revision 2");

    let output = run();

    assert!(
        output.status.success(),
        "repair setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(kast_home.join("current/config/config.toml"))
            .expect("repaired IDEA defaults"),
        "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = true\n\n[backends.headless]\nenabled = false\n\n[backends.idea]\nenabled = true\n",
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
    let active_binary = source.join("commands/_kastctl");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary directory");
    std::fs::rename(source.join("bin/_kastctl"), &active_binary).expect("custom active binary");
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&manifest_path).expect("bundle manifest"))
            .expect("manifest JSON");
    manifest["activation"]["cli"]["path"] = serde_json::json!("commands/_kastctl");
    manifest["artifacts"][0]["path"] = serde_json::json!("commands/_kastctl");
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
        std::fs::read_link(home.join(".local/bin/_kastctl")).expect("control user command"),
        kast_home.join("current/commands/_kastctl"),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("agent user command"),
        kast_home.join("current/bin/kast"),
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
