mod support;

use serde_json::Value;
use support::metrics::seed_source_index;
use support::*;

#[test]
fn top_level_help_exposes_repo_native_demo() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("top-level help");

    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    assert!(
        stdout
            .lines()
            .any(|line| line.trim_start().starts_with("demo")),
        "top-level help should expose the repo-native demo: {stdout}"
    );
}

#[test]
fn captured_demo_returns_ranked_repo_native_story_snapshot() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);
    seed_external_reference_target(&workspace);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("captured demo");

    assert!(
        demo.status.success(),
        "captured demo should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&demo.stdout),
        String::from_utf8_lossy(&demo.stderr)
    );
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo json");
    assert_eq!(response["type"], "KAST_DEMO");
    assert_eq!(response["availability"], "indexOnly");
    assert_eq!(response["workspaceRoot"], workspace.display().to_string());
    assert_eq!(response["mutates"], false);
    let candidates = response["candidates"].as_array().expect("candidates");
    assert!(
        candidates
            .iter()
            .all(|candidate| candidate["fqName"] != "kotlin.String"),
        "automatic stories must stay grounded in workspace declarations: {response:#}"
    );
    assert!(
        candidates.iter().any(|candidate| {
            candidate["kind"] == "impactHub" && candidate["fqName"] == "lib.Foo"
        }),
        "ranked candidates should include the highest-impact symbol: {response:#}"
    );
    assert_eq!(
        candidates
            .iter()
            .map(|candidate| candidate["kind"].as_str().expect("candidate kind"))
            .collect::<Vec<_>>(),
        vec!["impactHub", "callChainHub", "semanticAmbiguity"],
        "the fixture supports all three deterministic story kinds: {response:#}"
    );
    assert!(
        response["chapters"]
            .as_array()
            .expect("chapters")
            .iter()
            .any(|chapter| chapter["chapter"] == "impact" && chapter["available"] == true),
        "index-only stories should retain impact evidence: {response:#}"
    );
    assert!(
        response["help"]
            .as_array()
            .expect("help")
            .iter()
            .any(|entry| entry
                .as_str()
                .is_some_and(|entry| { entry.contains("kast agent impact --symbol lib.Foo") })),
        "snapshot should expose an exact reusable public command: {response:#}"
    );
}

fn seed_external_reference_target(workspace: &std::path::Path) {
    let connection =
        rusqlite::Connection::open(workspace.join(".gradle/kast/cache/source-index.db"))
            .expect("source index");
    connection
        .execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (99, 'kotlin.String')",
            [],
        )
        .expect("external fq name");
    for offset in 100..110 {
        connection
            .execute(
                "INSERT INTO symbol_references(src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, edge_kind) VALUES (1, 'A.kt', ?, 1, 99, 'TYPE_REF')",
                [offset],
            )
            .expect("external reference");
    }
}

#[test]
fn unavailable_demo_reports_the_platform_setup_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("unavailable demo");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(response["code"], "DEMO_SOURCE_INDEX_MISSING");
    let message = response["message"].as_str().expect("message");
    if cfg!(target_os = "macos") {
        assert!(
            message.contains("open this repository in IntelliJ IDEA or Android Studio"),
            "macOS remediation should name the plugin-owned setup path: {response:#}"
        );
    } else {
        assert!(
            message.contains("kast setup --workspace-root"),
            "headless remediation should name the CLI-owned setup path: {response:#}"
        );
    }
}

#[test]
fn requested_demo_symbol_fails_loudly_when_the_index_has_no_match() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--symbol",
            "NoSuchSymbol",
        ])
        .output()
        .expect("requested symbol demo");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(response["code"], "DEMO_SYMBOL_NOT_FOUND");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("NoSuchSymbol")),
        "the error should preserve the user's missing symbol query: {response:#}"
    );
}

#[cfg(target_os = "macos")]
#[test]
fn interactive_demo_renders_in_a_real_pty_without_changing_sources() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);
    let source = workspace.join("app/A.kt");
    let before = std::fs::read(&source).expect("source before demo");

    let mut child = Command::new("script")
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .env("KAST_TEST_BIN", env!("CARGO_BIN_EXE_kast"))
        .env("KAST_TEST_WORKSPACE", &workspace)
        .env("TERM", "xterm-256color")
        .args([
            "-q",
            "/dev/null",
            "/bin/sh",
            "-c",
            "stty rows 30 cols 120; exec \"$KAST_TEST_BIN\" --output human demo --workspace-root \"$KAST_TEST_WORKSPACE\"",
        ])
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .expect("spawn interactive demo");
    child
        .stdin
        .take()
        .expect("script stdin")
        .write_all(b"q")
        .expect("quit demo");
    let output = child.wait_with_output().expect("wait for demo");

    assert!(
        output.status.success(),
        "interactive demo should exit cleanly: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        String::from_utf8_lossy(&output.stdout).contains("Kast Semantic Story"),
        "the real PTY should render the guided experience: {}",
        String::from_utf8_lossy(&output.stdout)
    );
    assert_eq!(
        std::fs::read(&source).expect("source after demo"),
        before,
        "the interactive demo must not mutate user code"
    );
}

#[test]
fn demo_reports_full_availability_from_an_existing_ready_backend() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    seed_source_index(&workspace);
    let handle = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, 5, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("full demo");

    assert!(
        demo.status.success(),
        "full demo should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&demo.stdout),
        String::from_utf8_lossy(&demo.stderr)
    );
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo json");
    let requests = handle.join().expect("fake backend");
    assert_eq!(
        requests
            .iter()
            .map(|request| request["method"].as_str().expect("method"))
            .collect::<Vec<_>>(),
        vec![
            "runtime/status",
            "capabilities",
            "symbol/resolve",
            "symbol/references",
            "raw/diagnostics"
        ]
    );
    assert_eq!(response["availability"], "full");
    assert_eq!(response["backend"]["name"], "idea");
    assert_eq!(response["backend"]["referenceIndexReady"], true);
    assert_eq!(
        response["selectedStory"]["compilerIdentity"]["fqName"],
        "lib.Foo"
    );
    assert_eq!(response["selectedStory"]["compilerReferenceCount"], 2);
    assert_eq!(response["selectedStory"]["diagnostics"]["clean"], true);
    assert!(
        response["chapters"]
            .as_array()
            .expect("chapters")
            .iter()
            .any(|chapter| chapter["chapter"] == "identity" && chapter["available"] == true),
        "a ready backend should unlock compiler identity: {response:#}"
    );
}

#[test]
fn demo_uses_a_ready_backend_when_the_source_index_is_missing() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    write_macos_plugin_workspace_metadata(&workspace);
    let handle = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, 5, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
            "--symbol",
            "lib.Foo",
        ])
        .output()
        .expect("backend-only demo");

    assert!(
        demo.status.success(),
        "backend-only demo should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&demo.stdout),
        String::from_utf8_lossy(&demo.stderr)
    );
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo json");
    assert_eq!(handle.join().expect("fake backend").len(), 5);
    assert_eq!(response["availability"], "backendOnly");
    assert_eq!(response["candidates"][0]["kind"], "selectedSymbol");
    assert_eq!(
        response["selectedStory"]["compilerIdentity"]["fqName"],
        "lib.Foo"
    );
    assert!(
        response["chapters"]
            .as_array()
            .expect("chapters")
            .iter()
            .any(|chapter| chapter["chapter"] == "impact" && chapter["available"] == false),
        "backend-only output must not claim index-derived impact evidence: {response:#}"
    );
}

#[test]
fn backend_only_demo_requests_a_symbol_instead_of_inventing_a_ranked_story() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    write_macos_plugin_workspace_metadata(&workspace);
    let handle = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, 2, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("backend-only demo without symbol");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(handle.join().expect("fake backend").len(), 2);
    assert_eq!(response["code"], "DEMO_SYMBOL_REQUIRED");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("kast demo --symbol <name>")),
        "the fallback should provide a one-turn recovery command: {response:#}"
    );
}

#[test]
fn backend_only_demo_fails_when_the_compiler_cannot_resolve_the_requested_symbol() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    write_macos_plugin_workspace_metadata(&workspace);
    let handle = spawn_ready_demo_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        3,
        Some(serde_json::json!({
            "type": "RESOLVE_FAILURE",
            "ok": false,
            "message": "No Kotlin symbol matched NoSuchSymbol"
        })),
    );

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
            "--symbol",
            "NoSuchSymbol",
        ])
        .output()
        .expect("unresolved backend-only demo");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(handle.join().expect("fake backend").len(), 3);
    assert_eq!(response["code"], "DEMO_RESOLVE_FAILED");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("NoSuchSymbol")),
        "the compiler's resolution failure should reach the user: {response:#}"
    );
}

#[test]
fn backend_only_demo_handles_typed_not_found_and_ambiguous_resolve_outcomes() {
    for (resolve_result, expected_code) in [
        (
            serde_json::json!({"type":"RESOLVE_NOT_FOUND","ok":true,"source":"compiler"}),
            "DEMO_RESOLVE_NOT_FOUND",
        ),
        (
            serde_json::json!({
                "type":"RESOLVE_AMBIGUOUS",
                "ok":true,
                "source":"compiler",
                "candidates":[{"fqName":"alpha.Foo"},{"fqName":"beta.Foo"}]
            }),
            "DEMO_RESOLVE_AMBIGUOUS",
        ),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("idea.sock");
        write_macos_plugin_workspace_metadata(&workspace);
        let handle = spawn_ready_demo_backend(
            &home,
            &config_home,
            &workspace,
            &socket_path,
            3,
            Some(resolve_result),
        );

        let demo = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "demo",
                "--workspace-root",
                workspace.to_str().expect("workspace path"),
                "--backend",
                "idea",
                "--symbol",
                "Foo",
            ])
            .output()
            .expect("typed resolve outcome demo");

        assert!(!demo.status.success());
        let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
        assert_eq!(response["code"], expected_code);
        assert_eq!(handle.join().expect("fake backend").len(), 3);
    }
}

#[test]
fn demo_relations_use_canonical_resolved_symbol_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    seed_source_index(&workspace);
    let canonical_fq_name = "canonical.lib.Foo";
    let handle = spawn_ready_demo_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        5,
        Some(serde_json::json!({
            "type": "RESOLVE_SUCCESS",
            "ok": true,
            "symbol": {
                "fqName": canonical_fq_name,
                "kind": "CLASS",
                "location": {
                    "filePath": workspace.join("lib/Foo.kt").display().to_string(),
                    "startOffset": 13,
                    "endOffset": 22,
                    "startLine": 3,
                    "startColumn": 1,
                    "preview": "class Foo"
                }
            }
        })),
    );

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("canonical relation demo");

    assert!(
        demo.status.success(),
        "{}",
        String::from_utf8_lossy(&demo.stdout)
    );
    let requests = handle.join().expect("fake backend");
    assert_eq!(requests[3]["method"], "symbol/references");
    assert_eq!(requests[3]["params"]["symbol"], canonical_fq_name);
}

fn spawn_ready_demo_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket_path: &std::path::Path,
    expected_requests: usize,
    resolve_result: Option<Value>,
) -> std::thread::JoinHandle<Vec<Value>> {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("config");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        format!(
            r#"[{{
  "workspaceRoot": "{}",
  "backendName": "idea",
  "backendVersion": "demo-test",
  "transport": "uds",
  "socketPath": "{}",
  "pid": {},
  "schemaVersion": 3
}}]"#,
            workspace.display(),
            socket_path.display(),
            std::process::id(),
        ),
    )
    .expect("descriptor");

    let listener = UnixListener::bind(socket_path).expect("bind fake backend");
    listener.set_nonblocking(true).expect("nonblocking backend");
    let server_workspace = workspace.to_path_buf();
    thread::spawn(move || {
        let mut requests = Vec::new();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while requests.len() < expected_requests && std::time::Instant::now() < deadline {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(std::time::Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept demo client: {error}"),
            };
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: Value = serde_json::from_str(&request_line).expect("request json");
            let method = request["method"].as_str().expect("method").to_string();
            requests.push(request.clone());
            let result = match method.as_str() {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "demo-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "referenceIndexReady": true,
                    "schemaVersion": 3
                }),
                "capabilities" => serde_json::json!({
                    "backendName": "idea",
                    "backendVersion": "demo-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": ["symbol/resolve", "symbol/references", "raw/diagnostics"],
                    "mutationCapabilities": ["symbol/rename"],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                "symbol/resolve" => resolve_result.clone().unwrap_or_else(|| serde_json::json!({
                        "type": "RESOLVE_SUCCESS",
                        "ok": true,
                        "symbol": {
                            "fqName": "lib.Foo",
                            "kind": "CLASS",
                            "location": {
                                "filePath": server_workspace.join("lib/Foo.kt").display().to_string(),
                                "startOffset": 13,
                                "endOffset": 22,
                                "startLine": 3,
                                "startColumn": 1,
                                "preview": "class Foo"
                            }
                        }
                    })),
                "symbol/references" => serde_json::json!({
                    "type": "REFERENCES_SUCCESS",
                    "ok": true,
                    "references": [
                        {
                            "filePath": server_workspace.join("app/A.kt").display().to_string(),
                            "startOffset": 55,
                            "endOffset": 58,
                            "startLine": 7,
                            "startColumn": 9,
                            "preview": "Foo()"
                        },
                        {
                            "filePath": server_workspace.join("app/B.kt").display().to_string(),
                            "startOffset": 21,
                            "endOffset": 24,
                            "startLine": 4,
                            "startColumn": 9,
                            "preview": "Foo()"
                        }
                    ],
                    "cardinality": {"type": "EXACT", "totalCount": 2}
                }),
                "raw/diagnostics" => serde_json::json!({
                    "diagnostics": [],
                    "schemaVersion": 3
                }),
                other => panic!("unexpected demo method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write response");
        }
        requests
    })
}

#[test]
fn developer_inspect_demo_returns_targeted_moved_guidance() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let old_demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "inspect",
            "demo",
            "--view",
            "compare",
            "--query",
            "Foo",
        ])
        .output()
        .expect("old demo");

    assert!(!old_demo.status.success());
    let response: Value = serde_json::from_slice(&old_demo.stdout).expect("moved error json");
    assert_eq!(response["code"], "DEMO_COMMAND_MOVED");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("kast demo")),
        "old invocations should self-correct in one turn: {response:#}"
    );
}
