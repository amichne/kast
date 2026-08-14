#[test]
fn prepared_linked_worktree_supports_read_only_symbol_resolution() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let fixture_root = workspace.parent().expect("linked fixture root");
    let home = fixture_root.join("home");
    let config_home = fixture_root.join("config");
    let socket_path = fixture.socket_path("linked-symbol.sock");
    std::fs::create_dir_all(&home).expect("home");
    let listener = bind_semantic_listener(&socket_path);
    let _runtime = write_runtime_descriptor(&home, &workspace, &socket_path, "indexer");
    let backend = spawn_verify_backend(listener, workspace.clone(), "indexer", 4);

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
        vec![
            "runtime/status",
            "capabilities",
            "symbol/resolve",
            "runtime/status"
        ]
    );
}

#[test]
fn prepared_linked_worktree_supports_read_only_diagnostics() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let fixture_root = workspace.parent().expect("linked fixture root");
    let home = fixture_root.join("home");
    let config_home = fixture_root.join("config");
    let socket_path = fixture.socket_path("linked-diagnostics.sock");
    std::fs::create_dir_all(&home).expect("home");
    let file = workspace.join("lib/Foo.kt");
    std::fs::create_dir_all(file.parent().expect("file parent")).expect("source dir");
    std::fs::write(&file, "package lib\n\nclass Foo\n").expect("source file");
    let listener = bind_semantic_listener(&socket_path);
    let _runtime = write_runtime_descriptor(&home, &workspace, &socket_path, "indexer");
    let backend = spawn_verify_backend(listener, workspace.clone(), "indexer", 6);
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
            "runtime/status",
            "raw/diagnostics",
            "runtime/status"
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
    assert_eq!(output["error"]["code"], "UNSUPPORTED_WORKSPACE");
    assert_eq!(
        output["error"]["details"]["semanticWorkspace"],
        serde_json::json!({
            "backendName": default_semantic_backend(),
            "workspaceRoot": std::fs::canonicalize(&workspace).expect("canonical unsupported workspace").display().to_string(),
            "workspaceKind": "UNSUPPORTED_PROJECT",
            "sourceModuleNames": [],
            "limitations": ["UNSUPPORTED_PROJECT"],
            "evidenceQuality": "UNAVAILABLE"
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
    assert_eq!(output["error"]["code"], "NO_INDEXER_AVAILABLE");
    let semantic_workspace = &output["error"]["details"]["semanticWorkspace"];
    assert_eq!(semantic_workspace["backendName"], "indexer");
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
        serde_json::json!(["SOURCE_MODULES_UNAVAILABLE"])
    );
    assert_eq!(semantic_workspace["evidenceQuality"], "UNAVAILABLE");
    assert!(semantic_workspace.get("nextActions").is_none());
}

fn default_semantic_backend() -> &'static str {
    "indexer"
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
