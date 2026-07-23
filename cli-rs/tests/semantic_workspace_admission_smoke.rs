mod support;

use std::os::unix::fs::PermissionsExt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use support::*;

#[test]
fn prepared_primary_checkout_reports_compiler_backed_workspace_evidence() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.primary()).expect("canonical primary");
    let home = fixture.primary().join("test-home");
    let config_home = fixture.primary().join("test-config");
    let socket_path = fixture.socket_path("primary.sock");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    write_runtime_descriptor(&home, &workspace, &socket_path, "idea");
    let listener = bind_semantic_listener(&socket_path);
    let backend = spawn_verify_backend(listener, workspace.clone(), "idea", 10);

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=idea",
        ])
        .output()
        .expect("agent verify");

    assert!(
        verify.status.success(),
        "prepared verify should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(
        output["result"]["semanticWorkspace"],
        serde_json::json!({
            "backendName": "idea",
            "workspaceRoot": workspace.display().to_string(),
            "workspaceKind": "PRIMARY_CHECKOUT",
            "sourceModuleNames": [":analysis-api", ":backend:idea"],
            "limitations": ["REFERENCE_INDEX_UNAVAILABLE"],
            "evidenceQuality": "COMPILER_BACKED",
            "nextActions": []
        })
    );
    let toon = kast(&home, &config_home)
        .args([
            "--output",
            "toon",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=idea",
        ])
        .output()
        .expect("agent verify TOON");
    assert!(
        toon.status.success(),
        "prepared TOON verify should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&toon.stdout),
        String::from_utf8_lossy(&toon.stderr)
    );
    let toon_output = decode_toon(&toon.stdout);
    assert_eq!(
        toon_output["result"]["semanticWorkspace"],
        output["result"]["semanticWorkspace"]
    );
    assert_eq!(
        backend.join().expect("backend thread"),
        vec![
            "runtime/status",
            "capabilities",
            "health",
            "runtime/status",
            "capabilities",
            "runtime/status",
            "capabilities",
            "health",
            "runtime/status",
            "capabilities",
        ]
    );
}

#[test]
fn prepared_linked_worktree_verify_views_retain_admission_evidence() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let home = fixture.linked().join("test-home");
    let config_home = fixture.linked().join("test-config");
    let socket_path = fixture.socket_path("linked-verify-views.sock");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    write_runtime_descriptor(&home, &workspace, &socket_path, "idea");
    let backend = spawn_verify_backend(
        bind_semantic_listener(&socket_path),
        workspace.clone(),
        "idea",
        15,
    );
    let views: [&[&str]; 3] = [&[], &["--fields", "health"], &["--count"]];

    for view in views {
        let verify = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "verify",
                "--workspace-root",
                workspace.to_str().expect("workspace path"),
                "--backend=idea",
            ])
            .args(view)
            .output()
            .expect("agent verify");
        let output: serde_json::Value =
            serde_json::from_slice(&verify.stdout).expect("verify JSON");

        assert!(verify.status.success(), "view={view:?}: {output:#}");
        assert_eq!(
            output["result"]["semanticWorkspace"]["workspaceRoot"],
            workspace.display().to_string(),
            "view={view:?}: {output:#}",
        );
        assert_eq!(
            output["result"]["semanticWorkspace"]["workspaceKind"], "LINKED_WORKTREE",
            "view={view:?}: {output:#}",
        );
        assert_eq!(
            output["result"]["semanticWorkspace"]["evidenceQuality"], "COMPILER_BACKED",
            "view={view:?}: {output:#}",
        );
    }
    assert_eq!(backend.join().expect("backend thread").len(), 15);
}

#[test]
#[cfg(not(target_os = "macos"))]
fn unprepared_disposable_checkout_can_use_headless_read_only_workflows() {
    let fixture = tempfile::tempdir().expect("headless fixture");
    let workspace = fixture.path().join("disposable");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let source_file = workspace.join("src/main/kotlin/Foo.kt");
    std::fs::create_dir_all(source_file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&source_file, "class Foo\n").expect("source file");
    std::fs::create_dir_all(&home).expect("home");
    write_runtime_descriptor(&home, &workspace, &socket_path, "headless");
    let backend = spawn_verify_backend(
        bind_semantic_listener(&socket_path),
        workspace.clone(),
        "headless",
        12,
    );
    let install_manifest = install_manifest_path(&home);
    let homebrew_receipt = home.join("Library/Application Support/Kast/homebrew-install.json");
    assert!(!install_manifest.exists());
    assert!(!homebrew_receipt.exists());

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
        ])
        .output()
        .expect("headless verify");

    assert!(
        verify.status.success(),
        "exact-root headless verify should succeed without IDEA metadata: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(
        output["result"]["semanticWorkspace"]["backendName"],
        "headless"
    );
    assert_eq!(
        output["result"]["semanticWorkspace"]["workspaceRoot"],
        workspace.display().to_string()
    );
    assert_eq!(
        output["result"]["semanticWorkspace"]["workspaceKind"],
        "DISPOSABLE_CHECKOUT"
    );

    let symbol = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Foo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
        ])
        .output()
        .expect("headless symbol");
    assert!(
        symbol.status.success(),
        "headless symbol should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&symbol.stdout),
        String::from_utf8_lossy(&symbol.stderr)
    );

    let diagnostics = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "diagnostics",
            "--file-path",
            source_file.to_str().expect("source path"),
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
        ])
        .output()
        .expect("headless diagnostics");
    assert!(
        diagnostics.status.success(),
        "headless diagnostics should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&diagnostics.stdout),
        String::from_utf8_lossy(&diagnostics.stderr)
    );
    let diagnostics_output: serde_json::Value =
        serde_json::from_slice(&diagnostics.stdout).expect("diagnostics JSON");
    assert_eq!(
        diagnostics_output["result"]["analysis"]["semanticOutcome"],
        "COMPLETE"
    );
    assert!(!install_manifest.exists());
    assert!(!homebrew_receipt.exists());
    assert_eq!(backend.join().expect("backend thread").len(), 12);
}

#[test]
fn prepared_linked_worktree_never_attaches_primary_checkout_descriptor() {
    let fixture = GitWorkspaceFixture::new();
    let primary = std::fs::canonicalize(fixture.primary()).expect("canonical primary");
    let linked = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let home = fixture.linked().join("test-home");
    let config_home = fixture.linked().join("test-config");
    let socket_path = fixture.socket_path("primary.sock");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&linked);
    write_runtime_descriptor(&home, &primary, &socket_path, "idea");
    let backend = spawn_verify_backend(bind_semantic_listener(&socket_path), primary, "idea", 0);

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            linked.to_str().expect("linked path"),
            "--backend=idea",
        ])
        .output()
        .expect("linked verify");

    assert!(
        !verify.status.success(),
        "other checkout must not serve verify"
    );
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "NO_BACKEND_AVAILABLE");
    assert!(backend.join().expect("backend thread").is_empty());
}

#[cfg(target_os = "macos")]
#[test]
fn missing_workspace_authority_rejects_every_explicit_headless_mutation_before_rpc() {
    let fixture = tempfile::tempdir().expect("mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    write_runtime_descriptor(&home, &workspace, &socket_path, "headless");
    let backend = ObservedSemanticBackend::spawn(
        bind_semantic_listener(&socket_path),
        workspace.clone(),
        "headless",
    );
    let content_file = fixture.path().join("content.kt");
    let target_file = workspace.join("src/main/kotlin/Added.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("content");

    let cases = applied_mutation_cases(&target_file, &content_file);

    let views: [&[&str]; 3] = [&[], &["--fields", "outcome"], &["--count"]];
    for view in views {
        for mut args in cases.clone() {
            args.extend(view.iter().map(|argument| (*argument).to_string()));
            args.extend([
                "--apply".to_string(),
                "--idempotency-key".to_string(),
                "authority-test".to_string(),
                "--workspace-root".to_string(),
                workspace.display().to_string(),
                "--backend=headless".to_string(),
            ]);
            let mutation = kast(&home, &config_home)
                .args(["--output", "json"])
                .args(args)
                .output()
                .expect("applied mutation");
            assert!(
                !mutation.status.success(),
                "unprepared mutation must fail for view={view:?}",
            );
            let output: serde_json::Value =
                serde_json::from_slice(&mutation.stdout).expect("mutation JSON");
            assert_eq!(
                output["error"]["code"], "SEMANTIC_MUTATION_AUTHORITY_REQUIRED",
                "view={view:?}: {output:#}",
            );
        }
    }
    assert!(
        backend.finish().is_empty(),
        "authority must fail before RPC"
    );
}

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
    write_runtime_descriptors(
        &home,
        &[
            (&workspace, &idea_socket, "idea"),
            (&workspace, &headless_socket, "headless"),
        ],
    );
    let idea = ObservedSemanticBackend::spawn(
        bind_semantic_listener(&idea_socket),
        workspace.clone(),
        "idea",
    );
    let headless = ObservedSemanticBackend::spawn(
        bind_semantic_listener(&headless_socket),
        workspace.clone(),
        "headless",
    );

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
        output["error"]["code"], "SEMANTIC_MUTATION_AUTHORITY_REQUIRED",
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
            output["error"]["code"], "SEMANTIC_MUTATION_AUTHORITY_REQUIRED",
            "{output:#}"
        );
    }
}

#[cfg(target_os = "macos")]
#[test]
#[cfg(not(target_os = "macos"))]
fn prepared_workspace_authority_allows_explicit_headless_mutation() {
    let fixture = tempfile::tempdir().expect("prepared mutation fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let socket_path = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    write_runtime_descriptor(&home, &workspace, &socket_path, "headless");
    let backend = ObservedSemanticBackend::spawn(
        bind_semantic_listener(&socket_path),
        workspace.clone(),
        "headless",
    );

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

    assert!(
        mutation.status.success(),
        "prepared authority should admit mutation: stdout={}, stderr={}",
        String::from_utf8_lossy(&mutation.stdout),
        String::from_utf8_lossy(&mutation.stderr)
    );
    assert_eq!(
        backend.finish(),
        vec!["runtime/status", "capabilities", "mutation/submit"]
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

    assert!(
        !verify.status.success(),
        "verify without a runtime must fail"
    );
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "NO_BACKEND_AVAILABLE");
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
    assert_eq!(output["error"]["code"], "NO_BACKEND_AVAILABLE");
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

#[test]
#[cfg(not(target_os = "macos"))]
fn automatic_selection_rejects_two_ready_exact_root_backends() {
    let fixture = tempfile::tempdir().expect("ambiguity fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let idea_socket = fixture.path().join("idea.sock");
    let headless_socket = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    write_runtime_descriptors(
        &home,
        &[
            (&workspace, &idea_socket, "idea"),
            (&workspace, &headless_socket, "headless"),
        ],
    );
    let idea = ObservedSemanticBackend::spawn(
        bind_semantic_listener(&idea_socket),
        workspace.clone(),
        "idea",
    );
    let headless = ObservedSemanticBackend::spawn(
        bind_semantic_listener(&headless_socket),
        workspace.clone(),
        "headless",
    );

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

    assert!(!verify.status.success(), "automatic ambiguity must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "SEMANTIC_BACKEND_AMBIGUOUS");
    let mut candidate_names = output["error"]["details"]["semanticWorkspace"]["backendCandidates"]
        .as_array()
        .expect("candidate evidence")
        .iter()
        .map(|candidate| candidate["backendName"].as_str().expect("backend name"))
        .collect::<Vec<_>>();
    candidate_names.sort_unstable();
    assert_eq!(candidate_names, vec!["headless", "idea"]);
    assert_eq!(
        output["error"]["details"]["semanticWorkspace"]["workspaceRoot"],
        workspace.display().to_string()
    );
    assert!(!idea.finish().is_empty());
    assert!(!headless.finish().is_empty());
}

#[test]
fn prepared_linked_worktree_supports_read_only_symbol_resolution() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let home = fixture.linked().join("test-home");
    let config_home = fixture.linked().join("test-config");
    let socket_path = fixture.socket_path("linked-symbol.sock");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    write_runtime_descriptor(&home, &workspace, &socket_path, "idea");
    let backend = spawn_verify_backend(
        bind_semantic_listener(&socket_path),
        workspace.clone(),
        "idea",
        3,
    );

    let symbol = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Foo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=idea",
        ])
        .output()
        .expect("agent symbol");

    assert!(
        symbol.status.success(),
        "prepared symbol should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&symbol.stdout),
        String::from_utf8_lossy(&symbol.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&symbol.stdout).expect("symbol JSON");
    assert_eq!(output["result"]["type"], "KAST_AGENT_SYMBOL_RESULT");
    assert_eq!(output["result"]["outcome"], "RESOLVED");
    assert_eq!(output["result"]["identity"]["fqName"], "Foo");
    assert_eq!(output["result"]["source"], "compiler");
    assert_eq!(
        backend.join().expect("backend thread"),
        vec!["runtime/status", "capabilities", "symbol/resolve"]
    );
}

#[test]
fn prepared_linked_worktree_supports_read_only_diagnostics() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let home = fixture.linked().join("test-home");
    let config_home = fixture.linked().join("test-config");
    let socket_path = fixture.socket_path("linked-diagnostics.sock");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    let file = workspace.join("lib/Foo.kt");
    std::fs::create_dir_all(file.parent().expect("file parent")).expect("source dir");
    std::fs::write(&file, "package lib\n\nclass Foo\n").expect("source file");
    write_runtime_descriptor(&home, &workspace, &socket_path, "idea");
    let backend = spawn_verify_backend(
        bind_semantic_listener(&socket_path),
        workspace.clone(),
        "idea",
        4,
    );
    let diagnostics = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "diagnostics",
            "--file-path",
            file.to_str().expect("file path"),
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=idea",
        ])
        .output()
        .expect("agent diagnostics");

    assert!(
        diagnostics.status.success(),
        "prepared diagnostics should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&diagnostics.stdout),
        String::from_utf8_lossy(&diagnostics.stderr)
    );
    let output: serde_json::Value =
        serde_json::from_slice(&diagnostics.stdout).expect("diagnostics JSON");
    assert_eq!(output["result"]["analysis"]["semanticOutcome"], "COMPLETE");
    assert_eq!(
        backend.join().expect("backend thread"),
        vec![
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ]
    );
}

#[cfg(target_os = "macos")]
#[test]
fn unprepared_primary_checkout_reports_supported_semantic_routes() {
    let fixture = GitWorkspaceFixture::new();

    assert_unprepared_route(fixture.primary(), "PRIMARY_CHECKOUT");
}

#[cfg(target_os = "macos")]
#[test]
fn unprepared_linked_worktree_reports_supported_semantic_routes() {
    let fixture = GitWorkspaceFixture::new();

    assert_unprepared_route(fixture.linked(), "LINKED_WORKTREE");
}

#[cfg(target_os = "macos")]
#[test]
fn unprepared_disposable_checkout_reports_supported_semantic_routes() {
    let fixture = tempfile::tempdir().expect("disposable root");
    let workspace = fixture.path().join("disposable-checkout");
    write_gradle_workspace(&workspace);

    assert_unprepared_route(&workspace, "DISPOSABLE_CHECKOUT");
}

#[test]
fn unsupported_project_reports_distinct_semantic_outcome() {
    let fixture = tempfile::tempdir().expect("unsupported root");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("unsupported");
    std::fs::create_dir_all(&workspace).expect("unsupported workspace");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("agent verify");

    assert!(!verify.status.success(), "unsupported project must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "SEMANTIC_WORKSPACE_UNSUPPORTED");
    assert_eq!(
        output["error"]["details"]["semanticWorkspace"],
        serde_json::json!({
            "backendName": default_semantic_backend(),
            "workspaceRoot": workspace.display().to_string(),
            "workspaceKind": "UNSUPPORTED_PROJECT",
            "sourceModuleNames": [],
            "limitations": ["UNSUPPORTED_PROJECT"],
            "evidenceQuality": "UNAVAILABLE",
            "nextActions": []
        })
    );
}

#[cfg(target_os = "macos")]
fn assert_unprepared_route(workspace: &Path, expected_kind: &str) {
    let fixture = tempfile::tempdir().expect("isolated home");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("agent verify");

    assert!(!verify.status.success(), "unprepared workspace must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "SEMANTIC_WORKSPACE_UNPREPARED");
    let semantic_workspace = &output["error"]["details"]["semanticWorkspace"];
    assert_eq!(semantic_workspace["backendName"], "idea");
    assert_eq!(
        semantic_workspace["workspaceRoot"],
        workspace.display().to_string()
    );
    assert_eq!(semantic_workspace["workspaceKind"], expected_kind);
    assert_eq!(
        semantic_workspace["sourceModuleNames"],
        serde_json::json!([])
    );
    assert_eq!(
        semantic_workspace["limitations"],
        serde_json::json!(["WORKSPACE_UNPREPARED", "SOURCE_MODULES_UNAVAILABLE"])
    );
    assert_eq!(semantic_workspace["evidenceQuality"], "UNAVAILABLE");
    assert_eq!(
        semantic_workspace["nextActions"][0]["kind"],
        "PREPARE_IDEA_WORKSPACE"
    );
    assert_eq!(
        semantic_workspace["nextActions"][0]["mutatesGlobalInstallAuthority"],
        false
    );
    assert!(
        semantic_workspace["nextActions"][0]["command"]
            .as_str()
            .is_some_and(|command| command.contains(&workspace.display().to_string())),
        "IDEA action must name the exact root: {semantic_workspace:#}"
    );
    assert_eq!(
        semantic_workspace["nextActions"][1]["kind"],
        "USE_HEADLESS_DISTRIBUTION"
    );
    assert_eq!(
        semantic_workspace["nextActions"][1]["mutatesGlobalInstallAuthority"],
        false
    );
}

fn default_semantic_backend() -> &'static str {
    if cfg!(target_os = "macos") {
        "idea"
    } else {
        "headless"
    }
}

fn decode_toon(output: &[u8]) -> serde_json::Value {
    let text = std::str::from_utf8(output).expect("TOON output UTF-8");
    toon_format::decode_default(text.trim()).expect("decode TOON output")
}

fn write_gradle_workspace(workspace: &Path) {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("settings");
}

#[cfg(target_os = "macos")]
fn applied_mutation_cases(target_file: &Path, content_file: &Path) -> [Vec<String>; 6] {
    [
        vec![
            "agent".to_string(),
            "rename".to_string(),
            "--symbol".to_string(),
            "sample.Foo".to_string(),
            "--new-name".to_string(),
            "Bar".to_string(),
        ],
        vec![
            "agent".to_string(),
            "add-file".to_string(),
            "--file-path".to_string(),
            target_file.display().to_string(),
            "--content-file".to_string(),
            content_file.display().to_string(),
        ],
        vec![
            "agent".to_string(),
            "add-declaration".to_string(),
            "--inside-file".to_string(),
            target_file.display().to_string(),
            "--at".to_string(),
            "file-bottom".to_string(),
            "--content-file".to_string(),
            content_file.display().to_string(),
        ],
        vec![
            "agent".to_string(),
            "add-implementation".to_string(),
            "--inside-scope".to_string(),
            "sample.Foo".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content_file.display().to_string(),
        ],
        vec![
            "agent".to_string(),
            "add-statement".to_string(),
            "--inside-scope".to_string(),
            "sample.foo".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content_file.display().to_string(),
        ],
        vec![
            "agent".to_string(),
            "replace-declaration".to_string(),
            "--symbol".to_string(),
            "sample.Foo".to_string(),
            "--content-file".to_string(),
            content_file.display().to_string(),
        ],
    ]
}

struct GitWorkspaceFixture {
    _temp: tempfile::TempDir,
    sockets: tempfile::TempDir,
    primary: PathBuf,
    linked: PathBuf,
}

impl GitWorkspaceFixture {
    fn new() -> Self {
        let temp = tempfile::Builder::new()
            .prefix("semantic-workspace-git-")
            .tempdir_in(std::env::current_dir().expect("current directory"))
            .expect("git fixture");
        let primary = temp.path().join("primary");
        let linked = temp.path().join("linked");
        let sockets = tempfile::tempdir().expect("socket fixture");
        write_gradle_workspace(&primary);
        run_git(&primary, &["init"]);
        run_git(&primary, &["config", "user.name", "Kast Test"]);
        run_git(&primary, &["config", "user.email", "kast@example.invalid"]);
        run_git(&primary, &["add", "settings.gradle.kts"]);
        run_git(&primary, &["commit", "-m", "fixture"]);
        run_git(
            &primary,
            &[
                "worktree",
                "add",
                "--detach",
                linked.to_str().expect("linked path"),
            ],
        );
        Self {
            _temp: temp,
            sockets,
            primary,
            linked,
        }
    }

    fn primary(&self) -> &Path {
        &self.primary
    }

    fn linked(&self) -> &Path {
        &self.linked
    }

    fn socket_path(&self, name: &str) -> PathBuf {
        self.sockets.path().join(name)
    }
}

fn run_git(workspace: &Path, args: &[&str]) {
    let output = Command::new("git")
        .current_dir(workspace)
        .args(args)
        .output()
        .expect("git command");
    assert!(
        output.status.success(),
        "git {args:?}: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[cfg(target_os = "macos")]
fn run_git_clone(source: &Path, destination: &Path) {
    let output = Command::new("git")
        .args(["clone", "--quiet"])
        .arg(source)
        .arg(destination)
        .output()
        .expect("git clone");
    assert!(
        output.status.success(),
        "git clone: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

fn write_runtime_descriptor(home: &Path, workspace: &Path, socket_path: &Path, backend: &str) {
    write_runtime_descriptors(home, &[(workspace, socket_path, backend)]);
}

fn write_stale_runtime_descriptor(
    home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend: &str,
) {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": backend,
            "backendVersion": "stale-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": 0,
            "schemaVersion": 5
        }]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor");
}

fn write_runtime_descriptors(home: &Path, descriptors: &[(&Path, &Path, &str)]) {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(
            &descriptors
                .iter()
                .map(|(workspace, socket_path, backend)| {
                    serde_json::json!({
                        "workspaceRoot": workspace.display().to_string(),
                        "backendName": backend,
                        "backendVersion": "admission-test",
                        "transport": "uds",
                        "socketPath": socket_path.display().to_string(),
                        "pid": std::process::id(),
                        "schemaVersion": 5
                    })
                })
                .collect::<Vec<_>>(),
        )
        .expect("descriptor JSON"),
    )
    .expect("descriptor");
}

struct ObservedSemanticBackend {
    stop: Arc<AtomicBool>,
    thread: std::thread::JoinHandle<Vec<String>>,
}

impl ObservedSemanticBackend {
    fn spawn(listener: UnixListener, workspace: PathBuf, backend_name: &'static str) -> Self {
        listener
            .set_nonblocking(true)
            .expect("nonblocking listener");
        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = Arc::clone(&stop);
        let thread = thread::spawn(move || {
            let mut methods = vec![];
            while !thread_stop.load(Ordering::Acquire) {
                let (mut stream, _) = match listener.accept() {
                    Ok(connection) => connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                        continue;
                    }
                    Err(error) => panic!("accept observed semantic client: {error}"),
                };
                stream
                    .set_nonblocking(false)
                    .expect("blocking observed stream");
                let mut request_line = String::new();
                BufReader::new(stream.try_clone().expect("clone observed stream"))
                    .read_line(&mut request_line)
                    .expect("read observed request");
                let request: serde_json::Value =
                    serde_json::from_str(&request_line).expect("observed request JSON");
                let method = request["method"].as_str().expect("method").to_string();
                methods.push(method.clone());
                let result = match method.as_str() {
                    "health" => serde_json::json!({
                        "ok": true,
                        "backendName": backend_name,
                        "backendVersion": "admission-test",
                        "schemaVersion": 5
                    }),
                    "runtime/status" => serde_json::json!({
                        "state": "READY",
                        "healthy": true,
                        "active": true,
                        "indexing": false,
                        "backendName": backend_name,
                        "backendVersion": "admission-test",
                        "workspaceRoot": workspace.display().to_string(),
                        "sourceModuleNames": [":fixture"],
                        "referenceIndexReady": true,
                        "schemaVersion": 5
                    }),
                    "capabilities" => serde_json::json!({
                        "backendName": backend_name,
                        "backendVersion": "admission-test",
                        "workspaceRoot": workspace.display().to_string(),
                        "readCapabilities": ["RESOLVE_SYMBOL", "DIAGNOSTICS"],
                        "mutationCapabilities": ["RENAME", "APPLY_EDITS"],
                        "limits": {
                            "requestTimeoutMillis": 60000,
                            "maxResults": 1000,
                            "maxConcurrentRequests": 4
                        },
                        "schemaVersion": 5
                    }),
                    "mutation/submit" => serde_json::json!({
                        "type": "SUCCEEDED",
                        "result": {
                            "type": "RENAME_RESULT",
                            "response": {
                                "ok": true,
                                "editCount": 0,
                                "affectedFiles": [],
                                "applyResult": {
                                    "applied": [],
                                    "affectedFiles": [],
                                    "createdFiles": [],
                                    "deletedFiles": []
                                },
                                "diagnostics": {"errorCount": 0, "warningCount": 0}
                            }
                        },
                        "deduplicated": false
                    }),
                    other => panic!("unexpected observed method: {other}"),
                };
                writeln!(
                    stream,
                    "{}",
                    serde_json::json!({"jsonrpc": "2.0", "id": request["id"], "result": result}),
                )
                .expect("write observed response");
            }
            methods
        });
        Self { stop, thread }
    }

    fn finish(self) -> Vec<String> {
        self.stop.store(true, Ordering::Release);
        self.thread.join().expect("observed backend thread")
    }
}

fn bind_semantic_listener(socket_path: &Path) -> UnixListener {
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("remove stale test socket");
    }
    UnixListener::bind(socket_path).expect("bind semantic listener")
}

fn spawn_verify_backend(
    listener: UnixListener,
    workspace: PathBuf,
    backend_name: &'static str,
    expected_requests: usize,
) -> std::thread::JoinHandle<Vec<String>> {
    listener
        .set_nonblocking(true)
        .expect("nonblocking listener");
    thread::spawn(move || {
        let mut methods = Vec::with_capacity(expected_requests);
        let deadline = Instant::now() + Duration::from_secs(10);
        while methods.len() < expected_requests && Instant::now() < deadline {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept semantic client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking semantic stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone semantic stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read semantic request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("semantic request JSON");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            methods.push(method.clone());
            let result = match method.as_str() {
                "health" => serde_json::json!({
                    "ok": true,
                    "backendName": backend_name,
                    "backendVersion": "admission-test",
                    "schemaVersion": 5
                }),
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": backend_name,
                    "backendVersion": "admission-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "sourceModuleNames": [":analysis-api", format!(":backend:{backend_name}")],
                    "referenceIndexReady": false,
                    "schemaVersion": 5
                }),
                "capabilities" => serde_json::json!({
                    "backendName": backend_name,
                    "backendVersion": "admission-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readCapabilities": ["SYMBOL_RESOLUTION", "DIAGNOSTICS"],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 5
                }),
                "symbol/resolve" => serde_json::json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "symbol": {
                        "fqName": request["params"]["symbol"],
                        "kind": "CLASS",
                        "workspaceRoot": workspace.display().to_string(),
                        "location": {
                            "filePath": workspace.join("Foo.kt"),
                            "startOffset": 0
                        }
                    },
                    "schemaVersion": 5
                }),
                "raw/workspace-refresh" => {
                    let file_paths = request["params"]["filePaths"]
                        .as_array()
                        .cloned()
                        .expect("refresh file paths");
                    serde_json::json!({
                        "refreshedFiles": file_paths,
                        "removedFiles": [],
                        "fullRefresh": false,
                        "fileStatuses": file_paths.iter().map(|file_path| serde_json::json!({
                            "filePath": file_path,
                            "fileSystemDiscovery": "DISCOVERED",
                            "sourceModuleOwnership": "OWNED",
                            "indexAdmission": "ADMITTED",
                            "analysisAvailability": "AVAILABLE",
                            "analysisStatus": {
                                "filePath": file_path,
                                "state": "ANALYZED"
                            }
                        })).collect::<Vec<_>>(),
                        "semanticOutcome": "COMPLETE",
                        "requestedFileCount": file_paths.len(),
                        "analyzedFileCount": file_paths.len(),
                        "skippedFileCount": 0,
                        "removedFileCount": 0,
                        "attemptCount": 1,
                        "elapsedMillis": 0,
                        "schemaVersion": 5
                    })
                }
                "raw/diagnostics" => {
                    let file_paths = request["params"]["filePaths"]
                        .as_array()
                        .cloned()
                        .expect("diagnostics file paths");
                    serde_json::json!({
                        "diagnostics": [],
                        "fileStatuses": file_paths.iter().map(|file_path| serde_json::json!({
                            "filePath": file_path,
                            "state": "ANALYZED"
                        })).collect::<Vec<_>>(),
                        "fileHashes": file_paths.iter().map(|file_path| serde_json::json!({
                            "filePath": file_path,
                            "hash": "a".repeat(64)
                        })).collect::<Vec<_>>(),
                        "semanticOutcome": "COMPLETE",
                        "requestedFileCount": file_paths.len(),
                        "analyzedFileCount": file_paths.len(),
                        "skippedFileCount": 0,
                        "severityCounts": {
                            "error": 0,
                            "warning": 0,
                            "info": 0,
                            "total": 0
                        },
                        "cardinality": {
                            "type": "EXACT",
                            "totalCount": 0
                        },
                        "schemaVersion": 5
                    })
                }
                other => panic!("unexpected fake verification method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc": "2.0", "id": request["id"], "result": result}),
            )
            .expect("write semantic response");
        }
        assert_eq!(methods.len(), expected_requests, "fake backend timeout");
        methods
    })
}
