mod support;

use std::time::{Duration, Instant};
use support::*;

#[test]
fn prepared_primary_checkout_reports_compiler_backed_workspace_evidence() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.primary()).expect("canonical primary");
    let home = fixture.primary().join("test-home");
    let config_home = fixture.primary().join("test-config");
    let socket_path = fixture.primary().join("semantic-backend.sock");
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
        diagnostics_output["result"]["semanticAnalysis"]["semanticOutcome"],
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
    let socket_path = fixture.primary().join("primary-backend.sock");
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
    assert_eq!(output["error"]["code"], "IDEA_NOT_RUNNING");
    assert!(backend.join().expect("backend thread").is_empty());
}

#[test]
fn prepared_linked_worktree_supports_read_only_symbol_resolution() {
    let fixture = GitWorkspaceFixture::new();
    let workspace = std::fs::canonicalize(fixture.linked()).expect("canonical linked");
    let home = fixture.linked().join("test-home");
    let config_home = fixture.linked().join("test-config");
    let socket_path = fixture.linked().join("semantic-backend.sock");
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
    assert_eq!(output["result"]["steps"][1]["name"], "symbol-resolve");
    assert_eq!(
        output["result"]["steps"][1]["result"]["type"],
        "SYMBOL_RESOLVE_SUCCESS"
    );
    assert_eq!(
        output["result"]["steps"][1]["result"]["workspaceRoot"],
        workspace.display().to_string()
    );
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
    let socket_path = fixture.linked().join("semantic-backend.sock");
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
    assert_eq!(
        output["result"]["semanticAnalysis"]["semanticOutcome"],
        "COMPLETE"
    );
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

struct GitWorkspaceFixture {
    _temp: tempfile::TempDir,
    primary: PathBuf,
    linked: PathBuf,
}

impl GitWorkspaceFixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().expect("git fixture");
        let primary = temp.path().join("primary");
        let linked = temp.path().join("linked");
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

fn write_runtime_descriptor(home: &Path, workspace: &Path, socket_path: &Path, backend: &str) {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": backend,
            "backendVersion": "admission-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": std::process::id(),
            "schemaVersion": 3
        }]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor");
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
                    "schemaVersion": 3
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
                    "schemaVersion": 3
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
                    "schemaVersion": 3
                }),
                "symbol/resolve" => serde_json::json!({
                    "type": "SYMBOL_RESOLVE_SUCCESS",
                    "symbol": request["params"]["symbol"],
                    "workspaceRoot": workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "raw/workspace-refresh" => serde_json::json!({
                    "refreshedFiles": request["params"]["filePaths"],
                    "removedFiles": [],
                    "fullRefresh": false,
                    "schemaVersion": 3
                }),
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
                        "semanticOutcome": "COMPLETE",
                        "requestedFileCount": file_paths.len(),
                        "analyzedFileCount": file_paths.len(),
                        "skippedFileCount": 0,
                        "schemaVersion": 3
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
