include!("setup_release_pins/fixture.rs");

#[test]
fn normal_upgrade_preserves_a_registered_release_and_a_pinned_candidate_blocks_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v1.0.0");
    let second_source = write_install_bundle_source(temp.path(), "v2.0.0");
    assert!(setup(&home, &kast_home, &first_source).status.success());
    let mut runtime = PinnedRuntimeService::new(temp.path(), &kast_home);
    let pinned_release = runtime.release_root.clone();

    let upgrade = runtime.run(setup_command(&home, &kast_home, &second_source));

    assert!(
        upgrade.status.success(),
        "upgrade should preserve a release used by a registered runtime: stdout={}, stderr={}",
        String::from_utf8_lossy(&upgrade.stdout),
        String::from_utf8_lossy(&upgrade.stderr),
    );
    assert!(pinned_release.is_dir(), "pinned release was deleted");
    assert!(runtime.is_live(), "registered runtime was terminated");

    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let releases_before = installed_release_names(&kast_home);
    let state_before = test_path_sha256(&kast_home.join("state"));
    let blocked = runtime.run(setup_command(&home, &kast_home, &first_source));

    assert!(
        !blocked.status.success(),
        "pinned candidate was replaced: pinned={}, stdout={}, stderr={}",
        pinned_release.display(),
        String::from_utf8_lossy(&blocked.stdout),
        String::from_utf8_lossy(&blocked.stderr),
    );
    let error: serde_json::Value =
        serde_json::from_slice(&blocked.stdout).expect("typed setup error");
    assert_eq!(error["code"], "SETUP_RUNTIME_RELEASE_PINNED");
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("unchanged current release"),
        current_before,
    );
    assert_eq!(installed_release_names(&kast_home), releases_before);
    assert_eq!(test_path_sha256(&kast_home.join("state")), state_before);
    assert!(runtime.is_live(), "blocked setup changed runtime ownership");
}

#[test]
fn same_release_profile_switches_keep_a_live_release_pin_valid() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v1.0.0");
    assert!(setup(&home, &kast_home, &source).status.success());
    let mut runtime = PinnedRuntimeService::new(temp.path(), &kast_home);
    let registration_before = test_path_sha256(&runtime.registration);

    let development = runtime.run({
        let mut command = setup_command(&home, &kast_home, &source);
        command.args(["--profile", "development"]);
        command
    });
    assert!(
        development.status.success(),
        "standard-to-development switch failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&development.stdout),
        String::from_utf8_lossy(&development.stderr),
    );

    let standard = runtime.run(setup_command(&home, &kast_home, &source));
    assert!(
        standard.status.success(),
        "development-to-standard switch failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&standard.stdout),
        String::from_utf8_lossy(&standard.stderr),
    );
    assert_eq!(
        test_path_sha256(&runtime.registration),
        registration_before,
        "profile projection rewrote the immutable runtime registration",
    );
    assert!(runtime.release_root.is_dir(), "pinned release was deleted");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("current release"),
        runtime.release_root,
    );
    assert!(
        runtime.is_live(),
        "profile switch changed runtime ownership"
    );
}

#[test]
fn force_setup_with_a_registered_runtime_changes_no_install_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v1.0.0");
    assert!(setup(&home, &kast_home, &source).status.success());
    let mut runtime = PinnedRuntimeService::new(temp.path(), &kast_home);
    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let releases_before = installed_release_names(&kast_home);
    let state_before = test_path_sha256(&kast_home.join("state"));

    let blocked = runtime.run({
        let mut command = setup_command(&home, &kast_home, &source);
        command.arg("--force");
        command
    });

    assert!(!blocked.status.success(), "force deleted registered state");
    let error: serde_json::Value =
        serde_json::from_slice(&blocked.stdout).expect("typed setup error");
    assert_eq!(error["code"], "SETUP_RUNTIME_NOT_QUIESCENT");
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("unchanged current release"),
        current_before,
    );
    assert_eq!(installed_release_names(&kast_home), releases_before);
    assert_eq!(test_path_sha256(&kast_home.join("state")), state_before);
    assert!(runtime.is_live(), "force setup terminated the runtime");
}
