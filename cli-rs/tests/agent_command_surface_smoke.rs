mod support;

use support::*;

fn assert_removed_agent_workflow(stdout: &serde_json::Value) {
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["method"], "agent/workflow", "{stdout}");
    assert_eq!(stdout["error"]["code"], "AGENT_COMMAND_REMOVED", "{stdout}");
    let replacements = stdout["error"]["details"]["replacements"]
        .as_array()
        .expect("workflow replacements");
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast agent verify --workspace-root <repo>"),
        "{stdout}"
    );
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast repair --apply"),
        "{stdout}"
    );
}

#[test]
fn removed_agent_workflow_package_verify_fails_closed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let workflow = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "package-verify",
            "--dry-run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent workflow package-verify");

    assert!(
        !workflow.status.success(),
        "removed workflow should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow removal json");
    assert_removed_agent_workflow(&stdout);
}

#[test]
fn removed_agent_workflow_write_validate_fails_before_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let workflow = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "write-validate",
            "--mode",
            "create",
            "--file-path",
            temp.path()
                .join("Example.kt")
                .to_str()
                .expect("example path"),
            "--content",
            "class Example",
        ])
        .output()
        .expect("agent workflow write-validate");

    assert!(
        !workflow.status.success(),
        "removed workflow should fail before mutation: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow removal json");
    assert_removed_agent_workflow(&stdout);
}

#[test]
fn agent_rename_without_apply_returns_identity_first_plan() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--kind",
            "function",
        ])
        .output()
        .expect("agent rename plan");

    assert!(
        plan.status.success(),
        "rename plan should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan json");
    let request = &stdout["result"]["request"];
    assert_eq!(stdout["method"], "agent/rename", "{stdout}");
    assert_eq!(
        stdout["result"]["type"], "KAST_AGENT_RENAME_PLAN",
        "{stdout}"
    );
    assert_eq!(stdout["result"]["applyRequired"], true, "{stdout}");
    assert_eq!(request["method"], "symbol/rename", "{stdout}");
    assert_eq!(
        request["params"]["type"], "RENAME_BY_SYMBOL_REQUEST",
        "{stdout}"
    );
    assert_eq!(
        request["params"]["symbol"], "io.example.OrderService.process",
        "{stdout}"
    );
    assert_eq!(request["params"]["kind"], "function", "{stdout}");
    assert!(
        !request.to_string().contains("offset"),
        "public rename plan must not expose offsets: {stdout}"
    );
}

#[test]
fn agent_scope_mutations_without_apply_return_typed_request_plans() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("snippet");
    let target_file = temp.path().join("Added.kt");

    let cases = [
        (
            "add-file",
            vec![
                "agent",
                "add-file",
                "--file-path",
                target_file.to_str().expect("target"),
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-file",
            "symbol/add-file",
        ),
        (
            "add-declaration",
            vec![
                "agent",
                "add-declaration",
                "--inside-file",
                target_file.to_str().expect("target"),
                "--at",
                "file-bottom",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-declaration",
            "symbol/add-declaration",
        ),
        (
            "add-implementation",
            vec![
                "agent",
                "add-implementation",
                "--inside-scope",
                "sample.Greeter",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-implementation",
            "symbol/add-implementation",
        ),
        (
            "add-statement",
            vec![
                "agent",
                "add-statement",
                "--inside-scope",
                "sample.greet",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-statement",
            "symbol/add-statement",
        ),
        (
            "replace-declaration",
            vec![
                "agent",
                "replace-declaration",
                "--symbol",
                "sample.greet",
                "--kind",
                "function",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/replace-declaration",
            "symbol/replace-declaration",
        ),
    ];

    for (name, args, agent_method, request_method) in cases {
        let plan = kast(&home, &config_home)
            .arg("--output")
            .arg("json")
            .args(args)
            .output()
            .unwrap_or_else(|error| panic!("{name} plan failed to launch: {error}"));

        assert!(
            plan.status.success(),
            "{name} plan should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr)
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&plan.stdout).unwrap_or_else(|error| {
                panic!(
                    "{name} plan should emit json: {error}; stdout={}",
                    String::from_utf8_lossy(&plan.stdout)
                )
            });
        assert_eq!(stdout["method"], agent_method, "{stdout}");
        assert_eq!(
            stdout["result"]["type"], "KAST_AGENT_MUTATION_PLAN",
            "{stdout}"
        );
        assert_eq!(stdout["result"]["applyRequired"], true, "{stdout}");
        assert_eq!(
            stdout["result"]["request"]["method"], request_method,
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["request"]["params"].get("type"),
            None,
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["request"]["params"]["contentFile"],
            content_file.to_str().expect("snippet"),
            "{stdout}"
        );
    }
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
