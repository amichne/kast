#[test]
fn path_projection_upgrade_retains_ownership_across_an_activation_crash() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(
        setup_with_profile(&home, &kast_home, &first_source, "development")
            .status
            .success(),
    );
    let interrupted = setup_command(&home, &kast_home, &second_source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-bundle-activation",
        )
        .output()
        .expect("interrupted upgrade");
    assert!(
        !interrupted.status.success(),
        "upgrade must stop at the crash point"
    );

    let recovered = setup(&home, &kast_home, &second_source);

    assert!(
        recovered.status.success(),
        "recovered upgrade should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err(),
        "stable upgrade must remove the receipt-owned developer projection",
    );
}

#[test]
fn path_projection_unsupported_receipt_schema_is_not_ownership_proof() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let receipt_path = kast_home.join("current/receipt.json");
    let mut receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("receipt"))
            .expect("receipt JSON");
    receipt["schemaVersion"] = serde_json::json!(999);
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
    )
    .expect("unsupported receipt");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "standard setup may preserve unproven state"
    );
    assert!(
        home.join(".local/bin/kastctl").is_symlink(),
        "unsupported receipt schema must not authorize deletion",
    );
}

#[test]
fn missing_receipt_authority_fields_never_authorize_control_deletion() {
    for missing_field in ["schemaVersion", "tool"] {
        for force in [false, true] {
            let temp = tempfile::tempdir().expect("tempdir");
            let home = temp.path().join("home");
            let kast_home = home.join(".local/share/kast");
            let source = write_install_bundle_source(temp.path(), "v9.8.7");
            assert!(
                setup_with_profile(&home, &kast_home, &source, "development")
                    .status
                    .success(),
            );
            let control = home.join(".local/bin/kastctl");
            let expected_target = kast_home.join("current/libexec/kastctl");
            let receipt_path = kast_home.join("current/receipt.json");
            let mut receipt: serde_json::Value =
                serde_json::from_slice(&std::fs::read(&receipt_path).expect("receipt"))
                    .expect("receipt JSON");
            receipt
                .as_object_mut()
                .expect("receipt object")
                .remove(missing_field);
            std::fs::write(
                &receipt_path,
                serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
            )
            .expect("receipt without authority field");

            let mut command = setup_command(&home, &kast_home, &source);
            if force {
                command.arg("--force");
            }
            let output = command.output().expect("standard setup");

            assert!(
                output.status.success(),
                "missing {missing_field} must be preserved during force={force}: stdout={}, stderr={}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            );
            assert_eq!(
                std::fs::read_link(&control).expect("preserved unproven control projection"),
                expected_target,
                "missing {missing_field} with force={force}",
            );
        }
    }
}

#[test]
fn path_projection_in_tree_wrong_target_is_not_ownership_proof() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let control = home.join(".local/bin/kastctl");
    let wrong_target = kast_home.join("current/bin/kast");
    std::fs::remove_file(&control).expect("replace control projection");
    std::os::unix::fs::symlink(&wrong_target, &control).expect("wrong in-tree projection");
    let receipt_path = kast_home.join("current/receipt.json");
    let mut receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("receipt"))
            .expect("receipt JSON");
    let projections = receipt["pathProjections"]
        .as_array_mut()
        .expect("path projections");
    projections
        .iter_mut()
        .find(|projection| projection["command"] == "KASTCTL")
        .expect("control projection")["target"] =
        serde_json::json!(wrong_target.display().to_string());
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
    )
    .expect("wrong-target receipt");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "standard setup may preserve unproven state"
    );
    assert_eq!(
        std::fs::read_link(control).expect("preserved wrong-target projection"),
        wrong_target,
    );
}

#[test]
fn path_projection_distinct_version_upgrade_can_enable_development_profile() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first_source).status.success());

    let output = setup_with_profile(&home, &kast_home, &second_source, "development");

    assert!(
        output.status.success(),
        "development upgrade should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kastctl")).expect("developer projection"),
        kast_home.join("current/libexec/kastctl"),
    );
}

#[test]
fn path_projection_distinct_version_upgrade_can_return_to_standard_profile() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(
        setup_with_profile(&home, &kast_home, &first_source, "development")
            .status
            .success(),
    );

    let output = setup(&home, &kast_home, &second_source);

    assert!(
        output.status.success(),
        "standard upgrade should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
}

#[test]
fn development_setup_projects_both_commands_and_records_receipt_ownership() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    let output = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(
        output.status.success(),
        "development setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let local_bin = home.join(".local/bin");
    assert_eq!(
        std::fs::read_link(local_bin.join("kast")).expect("kast projection"),
        kast_home.join("current/bin/kast"),
    );
    assert_eq!(
        std::fs::read_link(local_bin.join("kastctl")).expect("kastctl projection"),
        kast_home.join("current/libexec/kastctl"),
    );
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(receipt["setupProfile"], "DEVELOPMENT");
    assert_eq!(
        receipt["pathProjections"],
        serde_json::json!([
            {
                "command": "KAST",
                "path": local_bin.join("kast").display().to_string(),
                "target": kast_home.join("current/bin/kast").display().to_string()
            },
            {
                "command": "KASTCTL",
                "path": local_bin.join("kastctl").display().to_string(),
                "target": kast_home.join("current/libexec/kastctl").display().to_string()
            }
        ]),
    );
}

#[test]
fn development_setup_rejects_unmanaged_kastctl_before_activation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let unmanaged = home.join(".local/bin/kastctl");
    std::fs::create_dir_all(unmanaged.parent().expect("local bin")).expect("local bin");
    std::fs::write(&unmanaged, "unmanaged").expect("unmanaged kastctl");

    let output = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(
        !output.status.success(),
        "unmanaged kastctl must block development setup"
    );
    let error: serde_json::Value = serde_json::from_slice(&output.stdout).expect("typed error");
    assert_eq!(error["code"], "PATH_PROJECTION_UNMANAGED");
    assert_eq!(error["details"]["path"], unmanaged.display().to_string());
    assert_eq!(
        std::fs::read_to_string(&unmanaged).expect("preserved unmanaged kastctl"),
        "unmanaged",
    );
    assert!(
        !kast_home.join("current").exists(),
        "bundle was not activated"
    );
}

#[test]
fn standard_setup_preserves_unmanaged_kastctl() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let unmanaged = home.join(".local/bin/kastctl");
    std::fs::write(&unmanaged, "unmanaged").expect("unmanaged kastctl");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "standard setup should preserve an unmanaged command: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(unmanaged).expect("preserved unmanaged kastctl"),
        "unmanaged",
    );
}

#[test]
fn standard_setup_removes_only_receipt_owned_kastctl() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let control_projection = home.join(".local/bin/kastctl");
    assert!(control_projection.is_symlink());

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "standard profile transition should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(!control_projection.exists());
    assert!(std::fs::symlink_metadata(control_projection).is_err());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(receipt["setupProfile"], "STANDARD");
    assert_eq!(receipt["pathProjections"].as_array().map(Vec::len), Some(1));
}

#[test]
fn forced_development_setup_recreates_its_receipt_owned_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );

    let output = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development", "--force"])
        .output()
        .expect("forced development setup");

    assert!(
        output.status.success(),
        "forced development setup should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kastctl")).expect("kastctl projection"),
        kast_home.join("current/libexec/kastctl"),
    );
}
