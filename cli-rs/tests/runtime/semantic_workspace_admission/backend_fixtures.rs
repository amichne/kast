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
