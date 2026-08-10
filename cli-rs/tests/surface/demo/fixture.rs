struct DemoBackend {
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    handle: std::thread::JoinHandle<Vec<Value>>,
}

impl DemoBackend {
    fn finish(self) -> Vec<Value> {
        self.stop
            .store(true, std::sync::atomic::Ordering::Release);
        self.handle.join().expect("fake demo backend")
    }
}

fn spawn_ready_demo_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket_path: &std::path::Path,
    resolve_result: Option<Value>,
) -> DemoBackend {
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
    let exact_test_runtime = publish_exact_test_runtime(
        home,
        &server_workspace,
        socket_path,
        "indexer",
        "demo-test",
        &descriptor_dir,
    );

    listener.set_nonblocking(true).expect("nonblocking backend");
    let published_generation = published_workspace_generation_for_test(&server_workspace);
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let server_stop = std::sync::Arc::clone(&stop);
    let handle = thread::spawn(move || {
        let _exact_test_runtime = exact_test_runtime;
        let mut requests = Vec::new();
        while !server_stop.load(std::sync::atomic::Ordering::Acquire) {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(std::time::Duration::from_millis(1));
                    continue;
                }
                Err(error) => panic!("accept demo client: {error}"),
            };
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: Value = serde_json::from_str(&request_line).expect("request JSON");
            let method = request["method"].as_str().expect("method");
            let result = match method {
                "runtime/status" => {
                    let mut status = serde_json::json!({
                        "state": "READY",
                        "backendName": "indexer",
                        "backendVersion": "demo-test",
                        "workspaceRoot": server_workspace.display().to_string(),
                        "sourceModuleNames": [":fixture"],
                        "readiness": {
                            "runtime": {"type": "READY"}, "model": {"type": "READY"},
                            "references": {"type": "READY"}, "semanticGraph": {"type": "READY"},
                            "mutation": {"type": "READY"}
                        },
                        "schemaVersion": api_schema_version()
                    });
                    if let Some(published_generation) = &published_generation {
                        status["publishedWorkspaceGeneration"] = published_generation.clone();
                    }
                    status
                }
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
                    "schemaVersion": api_schema_version()
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
                    "schemaVersion": api_schema_version()
                }),
                other => panic!("unexpected demo method: {other}"),
            };
            requests.push(request);
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write response");
        }
        requests
    });
    DemoBackend { stop, handle }
}
