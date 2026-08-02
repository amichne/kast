#[test]
fn setup_keeps_manifest_active_binary_private() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let manifest_path = source.join("manifest.json");
    let active_binary = source.join("commands/kastctl");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary directory");
    std::fs::rename(source.join("libexec/kastctl"), &active_binary)
        .expect("custom active binary");
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&manifest_path).expect("bundle manifest"))
            .expect("manifest JSON");
    manifest["activation"]["cli"]["path"] = serde_json::json!("commands/kastctl");
    manifest["artifacts"][0]["path"] = serde_json::json!("commands/kastctl");
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
    assert!(kast_home.join("current/commands/kastctl").is_file());
    assert!(!home.join(".local/bin/_kastctl").exists());
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
