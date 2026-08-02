#[cfg(target_os = "macos")]
#[test]
fn automatic_applied_mutation_checks_workspace_authority_before_backend_discovery() {
    let fixture = tempfile::tempdir().expect("automatic mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let idea_socket = fixture.path().join("idea.sock");
    let headless_socket = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    let idea_listener = bind_semantic_listener(&idea_socket);
    let headless_listener = bind_semantic_listener(&headless_socket);
    write_runtime_descriptors(
        &home,
        &[
            (&workspace, &idea_socket, "idea"),
            (&workspace, &headless_socket, "headless"),
        ],
    );
    let idea = ObservedSemanticBackend::spawn(idea_listener, workspace.clone(), "idea");
    let headless =
        ObservedSemanticBackend::spawn(headless_listener, workspace.clone(), "headless");

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

    assert!(!mutation.status.success(), "unprepared mutation must fail");
    let output: serde_json::Value =
        serde_json::from_slice(&mutation.stdout).expect("mutation JSON");
    assert_eq!(
        output["error"]["code"], "WORKSPACE_LEASE_REQUIRED",
        "{output:#}"
    );
    assert!(idea.finish().is_empty(), "IDEA must not be contacted");
    assert!(
        headless.finish().is_empty(),
        "headless must not be contacted"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn default_applied_mutation_maps_every_public_family_to_missing_workspace_authority() {
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
        assert_eq!(
            output["error"]["code"], "WORKSPACE_LEASE_REQUIRED",
            "{output:#}"
        );
    }
}

#[test]
fn applied_headless_mutation_requires_a_workspace_lease_before_rpc() {
    let fixture = tempfile::tempdir().expect("prepared mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    let listener = bind_semantic_listener(&socket_path);
    write_runtime_descriptor(&home, &workspace, &socket_path, "headless");
    let backend = ObservedSemanticBackend::spawn(listener, workspace.clone(), "headless");

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
            "--backend=headless",
        ])
        .output()
        .expect("prepared mutation");

    let observed_methods = backend.finish();
    assert!(!mutation.status.success(), "lease-free mutation must fail");
    let output: serde_json::Value =
        serde_json::from_slice(&mutation.stdout).expect("mutation JSON");
    assert_eq!(output["error"]["code"], "WORKSPACE_LEASE_REQUIRED");
    assert!(
        observed_methods.is_empty(),
        "lease rejection must precede RPC: {observed_methods:?}"
    );
}

#[test]
fn agent_verify_never_runs_configured_idea_launch_command() {
    let fixture = tempfile::tempdir().expect("launch fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let launch_marker = fixture.path().join("idea-launched");
    let launch_command = fixture.path().join("launch-idea");
    let socket_path = fixture.path().join("idea.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    write_macos_plugin_workspace_metadata(&workspace);
    std::fs::write(
        &launch_command,
        format!("#!/bin/sh\ntouch '{}'\n", launch_marker.display()),
    )
    .expect("launch command");
    let mut permissions = std::fs::metadata(&launch_command)
        .expect("launch metadata")
        .permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(&launch_command, permissions).expect("launch executable");
    let listener = bind_semantic_listener(&socket_path);
    write_runtime_descriptor(&home, &workspace, &socket_path, "idea");
    let backend = ObservedSemanticBackend::spawn(listener, workspace.clone(), "idea");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = true\ncommand = \"{}\"\nwaitTimeoutMillis = 100\n",
            launch_command.display()
        ),
    )
    .expect("config");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--backend=idea",
        ])
        .output()
        .expect("agent verify");

    let observed_methods = backend.finish();
    assert!(!verify.status.success(), "retired IDEA intent must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(
        output["error"]["code"],
        "IDEA_SEMANTIC_BACKEND_RETIRED"
    );
    assert!(
        observed_methods.is_empty(),
        "retirement must precede IDEA RPC: {observed_methods:?}"
    );
    assert!(
        !launch_marker.exists(),
        "verification must not execute runtime.ideaLaunch"
    );
}

#[test]
fn reuse_only_verify_preserves_dead_descriptor_bytes_without_launching() {
    let fixture = tempfile::tempdir().expect("stale descriptor fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("dead.sock");
    let launch_marker = fixture.path().join("idea-launched");
    let launch_command = fixture.path().join("launch-idea");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    write_macos_plugin_workspace_metadata(&workspace);
    write_stale_runtime_descriptor(&home, &workspace, &socket_path, "idea");
    let descriptor_path = default_descriptor_dir(&home).join("daemons.json");
    let descriptor_before = std::fs::read(&descriptor_path).expect("descriptor bytes");
    std::fs::write(
        &launch_command,
        format!("#!/bin/sh\ntouch '{}'\n", launch_marker.display()),
    )
    .expect("launch command");
    let mut permissions = std::fs::metadata(&launch_command)
        .expect("launch metadata")
        .permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(&launch_command, permissions).expect("launch executable");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            "[runtime]\ndefaultBackend = \"idea\"\n\n[runtime.ideaLaunch]\nenabled = true\ncommand = \"{}\"\nwaitTimeoutMillis = 100\n",
            launch_command.display()
        ),
    )
    .expect("config");

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

    assert!(!verify.status.success(), "dead backend must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(
        output["error"]["code"],
        "IDEA_SEMANTIC_BACKEND_RETIRED"
    );
    assert_eq!(
        std::fs::read(&descriptor_path).expect("preserved descriptor bytes"),
        descriptor_before,
        "reuse-only verification must not prune or rewrite descriptor state"
    );
    assert!(!launch_marker.exists(), "verification must not launch IDEA");
    assert!(
        !socket_path.exists(),
        "verification must not start a backend"
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
    write_stale_runtime_descriptor(&home, &workspace, &socket_path, "headless");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--backend=headless",
        ])
        .output()
        .expect("agent verify");

    assert!(!verify.status.success(), "non-Gradle root must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "SEMANTIC_WORKSPACE_UNSUPPORTED");
}

include!("mutation_and_selection/selection.rs");
