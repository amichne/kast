#[test]
fn relative_file_target_requires_explicit_workspace_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "class Added\n").expect("snippet");

    let plan = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--file-path",
            "Added.kt",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .output()
        .expect("relative add-file plan");
    let document: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan JSON");

    assert!(!plan.status.success(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE",
        "{document:#}",
    );
}

#[test]
fn agent_mutation_plans_preserve_scope_and_anchor_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src");
    std::fs::create_dir_all(&source_root).expect("source root");
    let file_path = source_root
        .canonicalize()
        .expect("canonical source root")
        .join("App.kt");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "println(\"added\")\n").expect("snippet");

    let declaration = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-declaration",
            "--inside-scope",
            "sample.Container",
            "--after-symbol",
            "sample.Container.existing",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("declaration plan");
    assert!(
        declaration.status.success(),
        "{}",
        String::from_utf8_lossy(&declaration.stdout)
    );
    let declaration: serde_json::Value = serde_json::from_slice(&declaration.stdout).expect("json");
    assert_eq!(
        declaration["result"]["plan"]["placement"],
        serde_json::json!({
            "scope": {"type": "NAMED_SCOPE", "insideScope": "sample.Container"},
            "anchor": {"type": "AFTER_SYMBOL", "symbol": "sample.Container.existing"}
        })
    );

    let file_anchor = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-declaration",
            "--inside-file",
            file_path.to_str().expect("file path"),
            "--at",
            "file-bottom",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("file anchor plan");
    assert!(
        file_anchor.status.success(),
        "{}",
        String::from_utf8_lossy(&file_anchor.stdout)
    );
    let file_anchor: serde_json::Value = serde_json::from_slice(&file_anchor.stdout).expect("json");
    assert_eq!(
        file_anchor["result"]["plan"]["placement"],
        serde_json::json!({
            "scope": {"type": "FILE_SCOPE", "insideFile": file_path},
            "anchor": {"type": "AT_ANCHOR", "anchor": "file-bottom"}
        })
    );

    let before_symbol = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-implementation",
            "--inside-scope",
            "sample.Container",
            "--before-symbol",
            "sample.Container.existing",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("before-symbol plan");
    assert!(
        before_symbol.status.success(),
        "{}",
        String::from_utf8_lossy(&before_symbol.stdout)
    );
    let before_symbol: serde_json::Value =
        serde_json::from_slice(&before_symbol.stdout).expect("json");
    assert_eq!(
        before_symbol["result"]["plan"]["placement"],
        serde_json::json!({
            "scope": {"type": "NAMED_SCOPE", "insideScope": "sample.Container"},
            "anchor": {"type": "BEFORE_SYMBOL", "symbol": "sample.Container.existing"}
        })
    );

    let statement = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-statement",
            "--inside-scope",
            "sample.Container.run",
            "--at",
            "body-end",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .args(["--workspace-root", workspace.to_str().expect("workspace")])
        .output()
        .expect("statement plan");
    assert!(
        statement.status.success(),
        "{}",
        String::from_utf8_lossy(&statement.stdout)
    );
    let statement: serde_json::Value = serde_json::from_slice(&statement.stdout).expect("json");
    assert_eq!(
        statement["result"]["plan"]["insideScope"],
        "sample.Container.run"
    );
    assert_eq!(
        statement["result"]["plan"]["anchor"],
        serde_json::json!({"type": "AT_ANCHOR", "anchor": "body-end"})
    );
}

#[test]
fn ready_flags_installed_backend_below_embedded_minimum() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let install_dir = install_root.join("current/lib/backends/headless/headless-0.0.1");
    let runtime_libs = install_dir.join("runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::create_dir_all(
        install_manifest_path(&home)
            .parent()
            .expect("manifest parent"),
    )
    .expect("manifest parent");
    std::fs::write(
        install_manifest_path(&home),
        serde_json::to_string_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "test-install",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(&home).display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": env!("CARGO_BIN_EXE_kast"),
                "activeBinary": env!("CARGO_BIN_EXE_kast")
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["backend:headless"],
            "managedPaths": ["current/lib/backends/headless"],
            "backends": [{
                "name": "headless",
                "version": "0.0.1",
                "installDir": install_dir.display().to_string(),
                "runtimeLibsDir": runtime_libs.display().to_string()
            }],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("ready");
    let stdout = String::from_utf8_lossy(&ready.stdout);

    assert!(
        !ready.status.success(),
        "ready should fail for stale backend"
    );
    assert!(stdout.contains("\"ok\": false"), "{stdout}");
    assert!(stdout.contains("\"minimumBackendVersion\""), "{stdout}");
    assert!(stdout.contains("0.0.1"), "{stdout}");
    assert!(stdout.contains("older than required"), "{stdout}");
}
