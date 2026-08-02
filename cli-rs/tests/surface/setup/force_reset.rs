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
    std::fs::write(kast_home.join("state/cache/source-index.db"), "database")
        .expect("database");
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
    assert!(current.join("source.kt").is_file(), "workspace source was removed");
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
    assert!(!home.join(".local/bin/_kastctl").exists());
}
