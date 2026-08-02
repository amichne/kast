#[test]
fn demo_uses_a_ready_backend_when_the_source_index_is_missing() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let handle = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, 5, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
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
    let socket_path = temp.path().join("indexer.sock");
    let handle = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, 2, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
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
    let socket_path = temp.path().join("indexer.sock");
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
        let socket_path = temp.path().join("indexer.sock");
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
    let socket_path = temp.path().join("indexer.sock");
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
    let settings = workspace.join("settings.gradle.kts");
    if !settings.is_file() && !workspace.join("settings.gradle").is_file() {
        std::fs::write(&settings, "rootProject.name = \"demo-fixture\"\n")
            .expect("Gradle settings");
    }
    let server_workspace = workspace.canonicalize().expect("canonical workspace");
    let listener = UnixListener::bind(socket_path).expect("bind fake backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &server_workspace,
            socket_path,
            "indexer",
            "demo-test",
        )]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor");

    listener.set_nonblocking(true).expect("nonblocking backend");
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
                    "backendName": "indexer",
                    "backendVersion": "demo-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "referenceIndexReady": true,
                    "schemaVersion": 5
                }),
                "capabilities" => serde_json::json!({
                    "backendName": "indexer",
                    "backendVersion": "demo-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": ["symbol/resolve", "symbol/references", "raw/diagnostics"],
                    "mutationCapabilities": ["RENAME"],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 5
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
                    "schemaVersion": 5
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
