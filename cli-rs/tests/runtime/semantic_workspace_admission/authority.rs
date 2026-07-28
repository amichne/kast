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
    write_macos_plugin_workspace_metadata_at_home(&workspace, &home);
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
    write_macos_plugin_workspace_metadata_at_home(&workspace, &home);
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
    write_macos_plugin_workspace_metadata_at_home(&linked, &home);
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
