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
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).expect("setup JSON");
    let routed_cli = Path::new(
        result["developerOperations"]["cli"]
            .as_str()
            .expect("developer CLI route"),
    );
    assert_eq!(routed_cli, kast_home.join("current/commands/kastctl"));
    let help_args = result["developerOperations"]["helpArgs"]
        .as_array()
        .expect("developer help args")
        .iter()
        .map(|argument| argument.as_str().expect("developer help argument"));
    let help = Command::new(routed_cli)
        .args(help_args)
        .output()
        .expect("run routed developer help");
    assert!(help.status.success(), "{help:?}");
    assert!(
        String::from_utf8_lossy(&help.stdout).contains("Usage: kastctl"),
        "{help:?}"
    );
    assert!(kast_home.join("current/commands/kastctl").is_file());
    assert!(!home.join(".local/bin/_kastctl").exists());
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kast")).expect("agent user command"),
        kast_home.join("current/bin/kast"),
    );
}

#[test]
fn development_setup_projects_manifest_selected_control_path() {
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

    let output = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(
        output.status.success(),
        "development setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let projection = home.join(".local/bin/kastctl");
    let expected_target = kast_home.join("current/commands/kastctl");
    assert_eq!(
        std::fs::read_link(&projection).expect("control projection"),
        expected_target,
    );
    assert!(
        Command::new(&projection)
            .arg("--help")
            .status()
            .expect("projected control help")
            .success(),
    );
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    let control_projection = receipt["pathProjections"]
        .as_array()
        .expect("path projections")
        .iter()
        .find(|projection| projection["command"] == "KASTCTL")
        .expect("control projection receipt");
    assert_eq!(
        control_projection["target"],
        expected_target.display().to_string(),
    );
}

#[test]
fn development_upgrade_retargets_a_receipt_owned_control_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &first_source, "development")
            .status
            .success(),
    );
    let control_projection = home.join(".local/bin/kastctl");
    assert_eq!(
        std::fs::read_link(&control_projection).expect("initial control projection"),
        kast_home.join("current/libexec/kastctl"),
    );

    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    let manifest_path = second_source.join("manifest.json");
    let active_binary = second_source.join("commands/kastctl");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary directory");
    std::fs::rename(second_source.join("libexec/kastctl"), &active_binary)
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

    let output = setup_with_profile(&home, &kast_home, &second_source, "development");

    assert!(
        output.status.success(),
        "development upgrade should retarget its owned projection: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let expected_target = kast_home.join("current/commands/kastctl");
    assert_eq!(
        std::fs::read_link(&control_projection).expect("retargeted control projection"),
        expected_target,
    );
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    let control_receipt = receipt["pathProjections"]
        .as_array()
        .expect("path projections")
        .iter()
        .find(|projection| projection["command"] == "KASTCTL")
        .expect("control projection receipt");
    assert_eq!(
        control_receipt["target"],
        expected_target.display().to_string(),
    );
}

#[test]
fn development_retarget_recovers_on_both_sides_of_receipt_publication() {
    for crash_point in ["after-control-apply", "after-receipt-commit"] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let kast_home = home.join(".local/share/kast");
        let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
        assert!(
            setup_with_profile(&home, &kast_home, &first_source, "development")
                .status
                .success(),
        );
        let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
        let manifest_path = second_source.join("manifest.json");
        let active_binary = second_source.join("commands/kastctl");
        std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
            .expect("active binary directory");
        std::fs::rename(second_source.join("libexec/kastctl"), &active_binary)
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

        let interrupted = setup_command(&home, &kast_home, &second_source)
            .args(["--profile", "development"])
            .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
            .env("KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT", crash_point)
            .output()
            .expect("interrupted development retarget");

        assert!(!interrupted.status.success(), "crash point {crash_point}");
        let expected_target = kast_home.join("current/commands/kastctl");
        assert_eq!(
            std::fs::read_link(home.join(".local/bin/kastctl"))
                .expect("published replacement"),
            expected_target,
        );
        assert!(kast_home.join("path-projection-transaction.json").is_file());

        let recovered =
            setup_with_profile(&home, &kast_home, &second_source, "development");

        assert!(
            recovered.status.success(),
            "retarget recovery after {crash_point}: stdout={}, stderr={}",
            String::from_utf8_lossy(&recovered.stdout),
            String::from_utf8_lossy(&recovered.stderr),
        );
        assert_eq!(
            std::fs::read_link(home.join(".local/bin/kastctl"))
                .expect("recovered replacement"),
            expected_target,
        );
        assert!(!kast_home.join("path-projection-transaction.json").exists());
    }
}

#[test]
fn development_retarget_restores_a_public_replacement_after_exchange_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &first_source, "development")
            .status
            .success(),
    );
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    let manifest_path = second_source.join("manifest.json");
    let active_binary = second_source.join("commands/kastctl");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary directory");
    std::fs::rename(second_source.join("libexec/kastctl"), &active_binary)
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
    let barrier = temp.path().join("control-replace-barrier");
    let mut child = setup_command(&home, &kast_home, &second_source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-replace",
        )
        .spawn()
        .expect("spawn development retarget");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-replace");
    let control = home.join(".local/bin/kastctl");
    let replacement = home
        .join(".local/bin")
        .read_dir()
        .expect("local bin directory")
        .map(|entry| entry.expect("local bin entry").path())
        .find(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with("kastctl.kast-replace-"))
        })
        .expect("replacement transaction path");
    let desired_target = kast_home.join("current/commands/kastctl");
    assert_eq!(
        std::fs::read_link(&replacement).expect("prepared desired projection"),
        desired_target,
    );
    std::fs::remove_file(&control).expect("replace prior public projection");
    std::fs::write(&control, "late unmanaged").expect("late public replacement");
    release_setup_barrier(&barrier, "before-control-replace");

    let output = child.wait_with_output().expect("failed retarget output");

    assert!(!output.status.success(), "changed retarget state must fail closed");
    assert_eq!(
        std::fs::read_to_string(&control).expect("restored public replacement"),
        "late unmanaged",
    );
    assert_eq!(
        std::fs::read_link(&replacement).expect("restored desired projection"),
        desired_target,
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
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
        &kast_home.join("current/libexec/kastctl"),
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
