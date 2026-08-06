#[test]
fn setup_installs_one_indexer_release_without_a_public_plugin() {
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
        vec!["cli", "agent-cli", "indexer"],
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
        serde_json::json!(["cli", "indexer", "manifest"]),
    );
    let config = std::fs::read_to_string(kast_home.join("current/config/config.toml"))
        .expect("installed config");
    assert!(!config.contains("defaultBackend"));
}

#[test]
fn ordinary_setup_removes_retired_public_plugins_without_controlling_the_ide() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let idea_plugins =
        home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let android_plugins =
        home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    for plugins in [&idea_plugins, &android_plugins] {
        std::fs::create_dir_all(plugins.join("kast/lib")).expect("retired public plugin");
        std::fs::write(plugins.join("kast/lib/plugin.jar"), "retired").expect("plugin payload");
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
        assert!(
            !plugins.join("kast").exists(),
            "retired public plugin removed"
        );
        assert!(
            plugins.join("unrelated").is_dir(),
            "unrelated plugin preserved"
        );
    }
}

#[test]
fn ordinary_setup_retires_an_owned_legacy_headless_daemon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let socket_path = temp.path().join("legacy-headless.sock");
    let listener = UnixListener::bind(&socket_path).expect("legacy runtime socket");
    let server = spawn_legacy_headless_status_server(listener, workspace.clone());
    let (pid, reaped) = spawn_reapable_process();
    let current_socket = temp.path().join("current.sock");
    let _current_listener = UnixListener::bind(&current_socket).expect("current runtime socket");
    let (current_pid, current_reaped) = spawn_reapable_process();
    let preserved = vec![
        runtime_descriptor_for_process_test(
            &workspace,
            &current_socket,
            "indexer",
            "current-test",
            current_pid,
        ),
        serde_json::json!({"futureDescriptor": {"schemaVersion": 999}}),
    ];
    write_legacy_headless_descriptor(&home, &workspace, &socket_path, pid, None, &preserved);

    let output = setup(&home, &kast_home, &source);
    let stopped_by_setup = reaped
        .recv_timeout(std::time::Duration::from_secs(1))
        .is_ok();
    if !stopped_by_setup {
        terminate_fixture_process(pid);
        let _ = reaped.recv_timeout(std::time::Duration::from_secs(1));
    }
    let current_stopped_by_setup = current_reaped
        .recv_timeout(std::time::Duration::from_millis(100))
        .is_ok();
    if !current_stopped_by_setup {
        terminate_fixture_process(current_pid);
        let _ = current_reaped.recv_timeout(std::time::Duration::from_secs(1));
    }
    assert!(
        server.finish(),
        "setup did not inspect the owned legacy runtime: stopped={stopped_by_setup}, stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    assert!(
        output.status.success(),
        "setup should retire the owned legacy runtime: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        stopped_by_setup,
        "setup stopped the registered legacy process"
    );
    assert!(
        !current_stopped_by_setup,
        "setup preserved the current indexer process"
    );
    let remaining: serde_json::Value = serde_json::from_slice(
        &std::fs::read(default_descriptor_dir(&home).join("daemons.json"))
            .expect("remaining registry"),
    )
    .expect("remaining registry JSON");
    assert_eq!(
        remaining,
        serde_json::Value::Array(preserved),
        "setup removes only the retired descriptor",
    );
}

#[test]
fn ordinary_setup_rejects_an_unproven_legacy_headless_daemon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let socket_path = temp.path().join("unproven-headless.sock");
    let _listener = UnixListener::bind(&socket_path).expect("unproven runtime socket");
    let (pid, reaped) = spawn_reapable_process();
    write_legacy_headless_descriptor(&home, &workspace, &socket_path, pid, Some(1), &[]);
    let registry_path = default_descriptor_dir(&home).join("daemons.json");
    let registry_before = std::fs::read(&registry_path).expect("descriptor registry");

    let output = setup(&home, &kast_home, &source);
    let stopped_by_setup = reaped
        .recv_timeout(std::time::Duration::from_millis(100))
        .is_ok();
    if !stopped_by_setup {
        terminate_fixture_process(pid);
        let _ = reaped.recv_timeout(std::time::Duration::from_secs(1));
    }

    assert!(
        !output.status.success(),
        "unproven process identity must block setup"
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup failure");
    assert_eq!(result["code"], "RUNTIME_IDENTITY_MISMATCH");
    assert!(
        !stopped_by_setup,
        "setup did not signal an unproven process"
    );
    assert_eq!(
        std::fs::read(registry_path).expect("unchanged descriptor registry"),
        registry_before,
    );
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("current release after failure"),
        current_before,
    );
}

#[test]
fn ordinary_setup_rejects_a_malformed_runtime_registry_without_activating() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let registry_path = default_descriptor_dir(&home).join("daemons.json");
    std::fs::create_dir_all(registry_path.parent().expect("registry parent"))
        .expect("registry directory");
    let malformed = b"{not-json";
    std::fs::write(&registry_path, malformed).expect("malformed registry");

    let output = setup(&home, &kast_home, &source);

    assert!(
        !output.status.success(),
        "malformed registry must block setup"
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup failure");
    assert_eq!(result["code"], "RUNTIME_DESCRIPTOR_REGISTRY_INVALID");
    assert_eq!(
        std::fs::read(registry_path).expect("unchanged registry"),
        malformed
    );
    assert!(
        !kast_home.join("current").exists(),
        "setup did not activate a release"
    );
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
    let archives = legacy_kast_archives(&kast_home);
    assert_eq!(archives.len(), 1);
    let backup = &archives[0];
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
    assert!(!migrated.contains("defaultBackend"));
    assert!(!migrated.contains("[runtime]"));
    assert!(migrated.contains("# keep this operator note"));
    assert!(migrated.contains("maxResults = 321"));
}
