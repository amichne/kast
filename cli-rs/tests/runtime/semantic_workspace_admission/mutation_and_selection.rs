#[cfg(target_os = "macos")]
#[test]
fn automatic_applied_mutation_acquires_internal_authority_before_dispatch() {
    let fixture = tempfile::tempdir().expect("automatic mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let indexer_socket = fixture.path().join("indexer.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    let indexer_listener = bind_semantic_listener(&indexer_socket);
    let _runtime = write_runtime_descriptor(&home, &workspace, &indexer_socket, "indexer");
    let indexer = ObservedSemanticBackend::spawn(indexer_listener, workspace.clone(), "indexer");

    let mutation = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "sample.Foo",
            "--new-name",
            "Bar",
            "--apply",
            "--idempotency-key",
            "authority-test",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("automatic mutation");

    assert!(mutation.status.success(), "semantic demand must acquire authority");
    let observed = indexer.finish();
    assert!(observed.contains(&"runtime/status".to_string()), "{observed:?}");
    assert!(observed.contains(&"mutation/submit".to_string()), "{observed:?}");
}

#[cfg(target_os = "macos")]
#[test]
fn default_applied_mutation_maps_every_public_family_to_unavailable_semantic_demand() {
    let fixture = tempfile::tempdir().expect("default mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let content_file = fixture.path().join("content.kt");
    let target_file = workspace.join("src/main/kotlin/Added.kt");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("content");

    for mut args in applied_mutation_cases(&target_file, &content_file) {
        args.extend([
            "--apply".to_string(),
            "--idempotency-key".to_string(),
            "authority-test".to_string(),
            "--workspace-root".to_string(),
            workspace.display().to_string(),
        ]);
        let mutation = kast(&home, &config_home)
            .args(["--output", "json"])
            .args(args)
            .output()
            .expect("default applied mutation");
        assert!(!mutation.status.success(), "unprepared mutation must fail");
        let output: serde_json::Value =
            serde_json::from_slice(&mutation.stdout).expect("mutation JSON");
        assert_eq!(output["error"]["code"], "NO_INDEXER_AVAILABLE", "{output:#}");
    }
}

#[test]
fn applied_indexer_mutation_automatically_holds_internal_authority() {
    let fixture = tempfile::tempdir().expect("prepared mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("indexer.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    let listener = bind_semantic_listener(&socket_path);
    let _runtime = write_runtime_descriptor(&home, &workspace, &socket_path, "indexer");
    let backend = ObservedSemanticBackend::spawn(listener, workspace.clone(), "indexer");

    let mutation = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "sample.Foo",
            "--new-name",
            "Bar",
            "--apply",
            "--idempotency-key",
            "authority-test",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("prepared mutation");

    let observed_methods = backend.finish();
    assert!(mutation.status.success(), "semantic mutation demand must succeed");
    assert!(
        observed_methods.contains(&"mutation/submit".to_string()),
        "mutation demand did not reach RPC: {observed_methods:?}"
    );
}

#[test]
fn reuse_only_verify_preserves_dead_indexer_descriptor_bytes() {
    let fixture = tempfile::tempdir().expect("stale descriptor fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("dead.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    write_stale_runtime_descriptor(&home, &workspace, &socket_path, "indexer");
    let descriptor_path = default_descriptor_dir(&home).join("daemons.json");
    let descriptor_before = std::fs::read(&descriptor_path).expect("descriptor bytes");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent verify");

    assert!(!verify.status.success(), "dead indexer must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "NO_INDEXER_AVAILABLE");
    assert_eq!(
        std::fs::read(&descriptor_path).expect("preserved descriptor bytes"),
        descriptor_before,
        "reuse-only verification must not prune or rewrite descriptor state"
    );
    assert!(
        !socket_path.exists(),
        "verification must not start an indexer"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn temporary_git_clone_is_classified_as_disposable() {
    let fixture = GitWorkspaceFixture::new();
    let disposable = tempfile::tempdir().expect("disposable parent");
    let clone = disposable.path().join("clone");
    run_git_clone(fixture.primary(), &clone);

    assert_unprepared_route(&clone, "DISPOSABLE_CHECKOUT");
}

#[test]
fn descriptor_cannot_make_non_gradle_root_supported() {
    let fixture = tempfile::tempdir().expect("unsupported fixture");
    let workspace = fixture.path().join("unsupported");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("stale.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&home).expect("home");
    write_stale_runtime_descriptor(&home, &workspace, &socket_path, "indexer");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent verify");

    assert!(!verify.status.success(), "non-Gradle root must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "UNSUPPORTED_WORKSPACE");
}

include!("mutation_and_selection/selection.rs");
