fn write_descriptor(home: &Path, workspace: &Path, socket_path: &Path) {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            "indexer",
            "diagnostics-test",
        )]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor");
}

fn bind_listener(socket_path: &Path) -> UnixListener {
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("remove stale test socket");
    }
    UnixListener::bind(socket_path).expect("bind fake diagnostics socket")
}

fn spawn_fake_backend(
    listener: UnixListener,
    workspace: PathBuf,
    refresh: Value,
    diagnostics: Value,
    expected_requests: usize,
) -> std::thread::JoinHandle<Vec<Value>> {
    let workspace = workspace.canonicalize().expect("canonical workspace");
    listener
        .set_nonblocking(true)
        .expect("nonblocking listener");
    thread::spawn(move || {
        let mut requests = Vec::with_capacity(expected_requests);
        let deadline = Instant::now() + Duration::from_secs(10);
        while requests.len() < expected_requests && Instant::now() < deadline {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept fake diagnostics client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking diagnostics stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone diagnostics stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read diagnostics request");
            let request: Value =
                serde_json::from_str(&request_line).expect("diagnostics request JSON");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            requests.push(request.clone());
            let result = match method.as_str() {
                "runtime/status" => json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "indexer",
                    "backendVersion": "diagnostics-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "schemaVersion": api_schema_version()
                }),
                "capabilities" => json!({
                    "backendName": "indexer",
                    "backendVersion": "diagnostics-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readCapabilities": ["DIAGNOSTICS"],
                    "mutationCapabilities": ["REFRESH_WORKSPACE"],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": api_schema_version()
                }),
                "raw/workspace-refresh" => refresh.clone(),
                "raw/diagnostics" => diagnostics.clone(),
                other => panic!("unexpected fake diagnostics method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                json!({"jsonrpc": "2.0", "id": request["id"], "result": result}),
            )
            .expect("write diagnostics response");
        }
        assert_eq!(
            requests.len(),
            expected_requests,
            "fake backend request timeout"
        );
        requests
    })
}

fn complete_refresh(file: &Path) -> Value {
    complete_refresh_for(&[file.display().to_string()])
}

fn complete_refresh_for(file_paths: &[String]) -> Value {
    json!({
        "refreshedFiles": file_paths,
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": file_paths
            .iter()
            .map(|file_path| json!({
                "filePath": file_path,
                "fileSystemDiscovery": "DISCOVERED",
                "sourceModuleOwnership": "OWNED",
                "indexAdmission": "ADMITTED",
                "analysisAvailability": "AVAILABLE",
                "analysisStatus": {
                    "filePath": file_path,
                    "state": "ANALYZED"
                }
            }))
            .collect::<Vec<_>>(),
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": file_paths.len(),
        "analyzedFileCount": file_paths.len(),
        "skippedFileCount": 0,
        "removedFileCount": 0,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": api_schema_version()
    })
}

fn complete_removed_refresh(file: &Path) -> Value {
    let file_path = file.display().to_string();
    json!({
        "refreshedFiles": [],
        "removedFiles": [file_path],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file_path,
            "fileSystemDiscovery": "REMOVED",
            "sourceModuleOwnership": "NOT_APPLICABLE",
            "indexAdmission": "NOT_APPLICABLE",
            "analysisAvailability": "NOT_APPLICABLE"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 0,
        "analyzedFileCount": 0,
        "skippedFileCount": 0,
        "removedFileCount": 1,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": api_schema_version()
    })
}
