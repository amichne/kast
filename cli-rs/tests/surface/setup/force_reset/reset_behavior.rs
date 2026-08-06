#[test]
fn force_setup_removes_only_validated_kast_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let registered = home.join("workspaces/registered");
    let current = home.join("workspaces/current/nested");
    let unrelated = home.join("unrelated");

    for directory in [&registered, &current, &unrelated] {
        std::fs::create_dir_all(directory).expect("fixture directory");
    }
    assert!(
        setup(&home, &kast_home, &source).status.success(),
        "initial setup should succeed"
    );
    let unmanaged_control = home.join(".local/bin/kastctl");
    std::fs::write(&unmanaged_control, "unmanaged").expect("unmanaged control command");
    std::fs::write(current.join("source.kt"), "class Source\n").expect("workspace source");
    for root in [
        registered.as_path(),
        current.as_path(),
        current.parent().expect("current parent"),
        home.as_path(),
        unrelated.as_path(),
    ] {
        std::fs::create_dir_all(root.join(".kast")).expect("legacy local Kast state");
        std::fs::write(root.join(".kast/state"), "legacy").expect("legacy state marker");
    }
    std::fs::create_dir_all(kast_home.join("state/data/workspaces"))
        .expect("workspace registry directory");
    std::fs::write(
        kast_home.join("state/data/workspaces/local-workspaces.json"),
        serde_json::to_vec(&serde_json::json!({
            registered.display().to_string(): "registered-id",
            "../../../unrelated": "invalid-relative-root"
        }))
        .expect("registry JSON"),
    )
    .expect("workspace registry");
    std::fs::create_dir_all(kast_home.join("state/cache")).expect("cache");
    std::fs::write(kast_home.join("state/cache/source-index.db"), "database").expect("database");
    std::fs::create_dir_all(kast_home.join("releases/obsolete")).expect("obsolete release");
    std::fs::write(kast_home.join("releases/obsolete/junk"), "obsolete")
        .expect("obsolete release marker");
    let output = setup_command(&home, &kast_home, &source)
        .current_dir(&current)
        .arg("--force")
        .output()
        .expect("forced Kast setup");

    assert!(
        output.status.success(),
        "forced setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        current.join("source.kt").is_file(),
        "workspace source was removed"
    );
    for root in [
        registered.as_path(),
        current.as_path(),
        current.parent().expect("current parent"),
        home.as_path(),
    ] {
        assert!(
            root.join(".kast/state").is_file(),
            "workspace-owned state was removed at {}",
            root.display(),
        );
    }
    assert!(
        unrelated.join(".kast/state").is_file(),
        "unregistered state outside the ancestor chain was removed"
    );
    assert!(!kast_home.join("state/cache/source-index.db").exists());
    assert!(!kast_home.join("releases/obsolete").exists());
    assert!(kast_home.join("current/libexec/kastctl").is_file());
    assert!(home.join(".local/bin/kast").exists());
    assert_eq!(
        std::fs::read_to_string(unmanaged_control).expect("preserved unmanaged control command"),
        "unmanaged",
    );
    assert!(!home.join(".local/bin/_kastctl").exists());
}

#[test]
fn force_setup_archives_replaced_unmanaged_kast_instead_of_deleting_it() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("managed user command");
    std::fs::write(&user_command, "unmanaged").expect("replacement user command");

    let output = setup_command(&home, &kast_home, &source)
        .arg("--force")
        .output()
        .expect("forced setup");

    assert!(
        output.status.success(),
        "forced setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let archives = legacy_kast_archives(&kast_home);
    assert_eq!(archives.len(), 1);
    assert_eq!(
        std::fs::read_to_string(&archives[0]).expect("archived unmanaged command"),
        "unmanaged",
    );
    assert_eq!(
        std::fs::read_link(user_command).expect("managed user command"),
        kast_home.join("current/bin/kast"),
    );
}

#[test]
fn repeated_normal_and_force_setup_preserve_every_unmanaged_kast_archive() {
    for force in [false, true] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let kast_home = home.join(".local/share/kast");
        let source = write_install_bundle_source(temp.path(), "v9.8.7");
        assert!(setup(&home, &kast_home, &source).status.success());
        let user_command = home.join(".local/bin/kast");
        std::fs::remove_file(&user_command).expect("first managed user command");
        std::fs::write(&user_command, "first unmanaged").expect("first unmanaged command");
        assert!(setup(&home, &kast_home, &source).status.success());
        std::fs::remove_file(&user_command).expect("second managed user command");
        std::fs::write(&user_command, "second unmanaged").expect("second unmanaged command");

        let mut command = setup_command(&home, &kast_home, &source);
        if force {
            command.arg("--force");
        }
        let output = command.output().expect("repeated setup");

        assert!(
            output.status.success(),
            "repeated force={force} setup should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let contents = legacy_kast_archives(&kast_home)
            .iter()
            .map(|path| std::fs::read_to_string(path).expect("archived command"))
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(
            contents,
            std::collections::BTreeSet::from([
                "first unmanaged".to_string(),
                "second unmanaged".to_string(),
            ]),
            "force={force}",
        );
    }
}

#[test]
fn force_path_projection_race_preserves_an_unmanaged_kastctl_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let barrier = temp.path().join("force-path-projection-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .arg("--force")
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-remove",
        )
        .spawn()
        .expect("spawn forced setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-remove");
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace owned control projection");
    std::fs::write(&control, "unmanaged").expect("late unmanaged control command");
    release_setup_barrier(&barrier, "before-control-remove");

    let output = child.wait_with_output().expect("forced setup output");

    assert!(
        !output.status.success(),
        "force must fail closed after ownership changes: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(control).expect("preserved unmanaged control command"),
        "unmanaged",
    );
}
