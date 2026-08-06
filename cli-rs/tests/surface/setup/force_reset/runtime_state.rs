#[test]
fn force_setup_preserves_the_install_when_runtime_registration_state_is_malformed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let initial_source = write_install_bundle_source(temp.path(), "v1.0.0");
    let replacement_source = write_install_bundle_source(temp.path(), "v2.0.0");
    assert!(setup(&home, &kast_home, &initial_source).status.success());

    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let releases_before = installed_release_names(&kast_home);
    let registration = kast_home
        .join("state/runtime/services")
        .join("malformed-workspace")
        .join("malformed-runtime")
        .join("receipt.json");
    std::fs::create_dir_all(registration.parent().expect("registration directory"))
        .expect("registration directory");
    let malformed = b"{not-runtime-registration-json";
    std::fs::write(&registration, malformed).expect("malformed runtime registration");

    let output = setup_command(&home, &kast_home, &replacement_source)
        .arg("--force")
        .output()
        .expect("forced setup");

    assert!(
        !output.status.success(),
        "force must fail before deleting unproven runtime state: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let error: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup error");
    assert_eq!(error["code"], "SETUP_RUNTIME_PREFLIGHT_BLOCKED");
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("unchanged current release"),
        current_before,
    );
    assert_eq!(installed_release_names(&kast_home), releases_before);
    assert_eq!(
        std::fs::read(&registration).expect("preserved runtime registration"),
        malformed,
    );
}

#[test]
fn force_setup_preserves_a_registered_legacy_runtime_descriptor() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let initial_source = write_install_bundle_source(temp.path(), "v1.0.0");
    let replacement_source = write_install_bundle_source(temp.path(), "v2.0.0");
    assert!(setup(&home, &kast_home, &initial_source).status.success());
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let socket = temp.path().join("legacy-runtime.sock");
    let _listener = UnixListener::bind(&socket).expect("legacy runtime socket");
    let descriptor = kast_home.join("state/runtime/daemons/daemons.json");
    std::fs::create_dir_all(descriptor.parent().expect("descriptor directory"))
        .expect("descriptor directory");
    let descriptor_bytes =
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &workspace,
            &socket,
            "indexer",
            "legacy-test"
        )]))
        .expect("descriptor JSON");
    std::fs::write(&descriptor, &descriptor_bytes).expect("runtime descriptor");
    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let releases_before = installed_release_names(&kast_home);

    let output = setup_command(&home, &kast_home, &replacement_source)
        .arg("--force")
        .output()
        .expect("forced setup");

    assert!(
        !output.status.success(),
        "force deleted a runtime descriptor"
    );
    let error: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup error");
    assert_eq!(error["code"], "SETUP_RUNTIME_NOT_QUIESCENT");
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("unchanged current release"),
        current_before,
    );
    assert_eq!(installed_release_names(&kast_home), releases_before);
    assert_eq!(
        std::fs::read(&descriptor).expect("preserved runtime descriptor"),
        descriptor_bytes,
    );
}

#[test]
fn force_setup_review_regression_recovers_durable_registration_publication_temporaries() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let initial_source = write_install_bundle_source(temp.path(), "v1.0.0");
    let replacement_source = write_install_bundle_source(temp.path(), "v2.0.0");
    assert!(setup(&home, &kast_home, &initial_source).status.success());
    let workspace_services = kast_home
        .join("state/runtime/services")
        .join("a".repeat(64));
    std::fs::create_dir_all(&workspace_services).expect("workspace services");
    std::fs::set_permissions(
        workspace_services.parent().expect("services directory"),
        std::fs::Permissions::from_mode(0o700),
    )
    .expect("services mode");
    std::fs::set_permissions(
        &workspace_services,
        std::fs::Permissions::from_mode(0o700),
    )
    .expect("workspace services mode");
    let staging = workspace_services.join(".staging-11111111-1111-4111-8111-111111111111");
    std::fs::create_dir(&staging).expect("durable staging directory");
    std::fs::set_permissions(&staging, std::fs::Permissions::from_mode(0o700))
        .expect("staging mode");
    let partial_launch = staging.join("launch.json");
    std::fs::write(&partial_launch, b"{partial").expect("partial launch");
    std::fs::set_permissions(&partial_launch, std::fs::Permissions::from_mode(0o600))
        .expect("partial launch mode");
    let active_temporary =
        workspace_services.join(".runtime-22222222-2222-4222-8222-222222222222.tmp");
    std::fs::write(&active_temporary, b"{partial").expect("partial active pointer");
    std::fs::set_permissions(
        &active_temporary,
        std::fs::Permissions::from_mode(0o600),
    )
    .expect("active temporary mode");

    let output = setup(&home, &kast_home, &replacement_source);

    assert!(
        output.status.success(),
        "setup must recover proven publication temporaries: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(!staging.exists(), "staging directory remains");
    assert!(!active_temporary.exists(), "active pointer temporary remains");
}

#[test]
fn force_setup_review_regression_preserves_ambiguous_registration_publication_artifact() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v1.0.0");
    let workspace_services = kast_home
        .join("state/runtime/services")
        .join("b".repeat(64));
    std::fs::create_dir_all(&workspace_services).expect("workspace services");
    let ambiguous = workspace_services.join(".staging-33333333-3333-4333-8333-333333333333");
    std::fs::write(&ambiguous, b"unmanaged").expect("ambiguous staging artifact");

    let output = setup_command(&home, &kast_home, &source)
        .arg("--force")
        .output()
        .expect("forced setup");

    assert!(!output.status.success(), "ambiguous artifact must block setup");
    let error: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup error");
    assert_eq!(error["code"], "SETUP_RUNTIME_PREFLIGHT_BLOCKED");
    assert_eq!(
        std::fs::read_to_string(&ambiguous).expect("preserved ambiguous artifact"),
        "unmanaged",
    );
    assert!(!kast_home.join("current").exists());
}

fn installed_release_names(kast_home: &Path) -> std::collections::BTreeSet<String> {
    std::fs::read_dir(kast_home.join("releases"))
        .expect("release directory")
        .map(|entry| {
            entry
                .expect("release entry")
                .file_name()
                .into_string()
                .expect("UTF-8 release name")
        })
        .collect()
}
