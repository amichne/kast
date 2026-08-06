#[test]
fn same_release_migration_preserves_original_bytes_before_receipt_publication() {
    for failure_point in ["before-current-verify", "before-receipt-write"] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let kast_home = home.join(".local/share/kast");
        let source = write_install_bundle_source(temp.path(), "v9.8.7");
        assert!(setup(&home, &kast_home, &source).status.success());
        let config = kast_home.join("current/config/config.toml");
        let original = b"# preserve these exact bytes\r\n[runtime]\r\ndefaultBackend = \"idea\"\r\n\r\n[server]\r\nmaxResults = 321\r\n";
        std::fs::write(&config, original).expect("legacy configuration");

        let failed = setup_command(&home, &kast_home, &source)
            .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
            .env(
                "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
                failure_point,
            )
            .output()
            .expect("failing same-release setup");

        assert!(
            !failed.status.success(),
            "injected {failure_point} failure must fail setup",
        );
        assert_eq!(
            std::fs::read(&config).expect("preserved configuration"),
            original,
            "pre-receipt failure at {failure_point} must not change configuration",
        );
    }
}

#[test]
fn same_release_migration_remains_after_receipt_commit() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "# keep this note\n[runtime]\ndefaultBackend = \"idea\"\n\n[server]\nmaxResults = 321\n",
    )
    .expect("legacy configuration");

    let failed = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-control-transaction-finalize",
        )
        .output()
        .expect("post-receipt same-release failure");

    assert!(
        !failed.status.success(),
        "injected post-receipt failure must fail setup",
    );
    let migrated = std::fs::read_to_string(config).expect("migrated configuration");
    assert!(!migrated.contains("defaultBackend"));
    assert!(migrated.contains("# keep this note"));
    assert!(migrated.contains("maxResults = 321"));
}

#[test]
fn same_release_reinstall_failure_removes_a_provisional_agent_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let agent_command = home.join(".local/bin/kast");
    std::fs::remove_file(&agent_command).expect("remove prior agent projection");
    std::fs::remove_file(kast_home.join("current/libexec/kastctl"))
        .expect("make current verification fail");

    let failed = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .output()
        .expect("failing same-release reinstall");

    assert!(
        !failed.status.success(),
        "injected reinstall failure must fail setup",
    );
    assert!(
        std::fs::symlink_metadata(agent_command).is_err(),
        "a projection that was absent before same-release verification must remain absent",
    );
    assert!(
        std::fs::symlink_metadata(kast_home.join("previous")).is_err(),
        "rollback must restore the prior absence of `previous`",
    );
}

#[test]
fn same_release_early_user_command_failure_does_not_create_the_agent_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let agent_command = home.join(".local/bin/kast");
    std::fs::remove_file(&agent_command).expect("remove prior agent projection");

    let failed = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-user-command-receipt-read",
        )
        .output()
        .expect("early user-command failure");

    assert!(!failed.status.success(), "injected setup failure must fail");
    assert!(
        std::fs::symlink_metadata(agent_command).is_err(),
        "an early pre-receipt failure must not create the agent projection",
    );
}

#[test]
fn same_release_control_crash_leaves_config_unmodified_until_retry() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    let original = b"# survive the crash\n[runtime]\ndefaultBackend = \"idea\"\n\n[server]\nmaxResults = 321\n";
    std::fs::write(&config, original).expect("legacy configuration");

    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-control-apply",
        )
        .output()
        .expect("interrupted same-release setup");

    assert!(!interrupted.status.success(), "crash injection must stop setup");
    assert_eq!(
        std::fs::read(&config).expect("configuration after crash"),
        original,
        "pre-receipt crash must leave the legacy configuration unchanged",
    );
    let recovered = setup_with_profile(&home, &kast_home, &source, "development");
    assert!(
        recovered.status.success(),
        "retry must recover: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    let migrated = std::fs::read_to_string(&config).expect("migrated configuration");
    assert!(!migrated.contains("defaultBackend"));
    assert!(migrated.contains("# survive the crash"));
}

#[test]
fn same_release_migration_preserves_a_concurrent_config_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("legacy configuration");
    let barrier = temp.path().join("config-migration-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-current-config-migration-validation",
        )
        .spawn()
        .expect("spawn setup with config migration barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-current-config-migration-validation",
    );
    std::fs::remove_file(&config).expect("replace validated configuration");
    std::fs::write(&config, "# concurrent operator replacement\n")
        .expect("concurrent configuration replacement");
    release_setup_barrier(&barrier, "after-current-config-migration-validation");

    let failed = child.wait_with_output().expect("failed migration output");

    assert!(!failed.status.success(), "changed migration target must fail closed");
    assert_eq!(
        std::fs::read_to_string(config).expect("preserved replacement"),
        "# concurrent operator replacement\n",
    );
}

#[test]
fn activation_failure_after_current_archive_restores_the_same_release() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let active = std::fs::canonicalize(kast_home.join("current")).expect("active release");
    std::fs::remove_file(kast_home.join("current/libexec/kastctl"))
        .expect("force same-release activation");

    let failed = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "after-current-archive",
        )
        .output()
        .expect("activation failure after archive");

    assert!(!failed.status.success(), "injected activation failure must fail");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("restored current release"),
        active,
    );
    assert!(kast_home.join("current/bin/kast").is_file());
}

#[test]
fn legacy_activation_rollback_restores_previous_absence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let legacy_current = kast_home.join("current");
    std::fs::create_dir_all(&legacy_current).expect("legacy current directory");
    std::fs::write(legacy_current.join("marker"), "legacy\n").expect("legacy marker");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    let failed = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .output()
        .expect("failed legacy replacement");

    assert!(!failed.status.success(), "injected setup failure must fail");
    assert_eq!(
        std::fs::read_to_string(legacy_current.join("marker")).expect("restored marker"),
        "legacy\n",
    );
    assert!(
        std::fs::symlink_metadata(kast_home.join("previous")).is_err(),
        "rollback must restore the prior absence of `previous`",
    );
}

#[test]
fn activation_rollback_preserves_a_concurrent_current_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    std::fs::remove_file(home.join(".local/bin/kast")).expect("remove agent projection");
    let barrier = temp.path().join("activation-rollback-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-agent-rollback-validation",
        )
        .spawn()
        .expect("setup with rollback barrier");
    wait_for_setup_barrier(&mut child, &barrier, "after-agent-rollback-validation");
    let current = kast_home.join("current");
    std::fs::remove_file(&current).expect("replace published current");
    std::fs::write(&current, "operator current\n").expect("operator replacement");
    release_setup_barrier(&barrier, "after-agent-rollback-validation");

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "injected setup failure must fail");
    assert_eq!(
        std::fs::read_to_string(current).expect("preserved current replacement"),
        "operator current\n",
    );
}

#[test]
fn config_migration_restores_after_a_prepared_file_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    let original = b"[runtime]\ndefaultBackend = \"idea\"\n";
    std::fs::write(&config, original).expect("legacy config");
    let barrier = temp.path().join("migration-exchange-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-current-config-migration-final-validation",
        )
        .spawn()
        .expect("setup with migration barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-current-config-migration-final-validation",
    );
    let prepared = std::fs::read_dir(config.parent().expect("config parent"))
        .expect("config directory")
        .map(|entry| entry.expect("config entry").path())
        .find(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.contains(".kast-migration-"))
        })
        .expect("prepared migration");
    std::fs::remove_file(&prepared).expect("replace prepared migration");
    std::fs::write(&prepared, "prepared replacement\n").expect("prepared replacement");
    release_setup_barrier(
        &barrier,
        "after-current-config-migration-final-validation",
    );

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "changed prepared file must fail");
    assert_eq!(std::fs::read(&config).expect("restored config"), original);
    assert_eq!(
        std::fs::read_to_string(prepared).expect("preserved prepared replacement"),
        "prepared replacement\n",
    );
}

#[cfg(unix)]
#[test]
fn same_release_migration_preserves_the_exact_config_mode() {
    use std::os::unix::fs::PermissionsExt;

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(&config, "[runtime]\ndefaultBackend = \"idea\"\n").expect("legacy config");
    std::fs::set_permissions(&config, std::fs::Permissions::from_mode(0o710))
        .expect("restrict config mode");

    let migrated = setup(&home, &kast_home, &source);

    assert!(migrated.status.success(), "migration must succeed");
    assert_eq!(
        std::fs::metadata(config).expect("config metadata").permissions().mode() & 0o7777,
        0o710,
    );
}
