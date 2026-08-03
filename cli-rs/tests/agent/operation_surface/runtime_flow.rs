#[test]
fn dependent_symbol_command_observes_the_completed_edit() {
    let fixture = MutationFixture::new();
    let backend = spawn_operation_backend(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &fixture.temp.path().join("indexer.sock"),
        Some(mutation_result(false)),
        true,
    );
    let lease_id = fixture.acquire_lease();
    assert!(fixture.apply("dependent-key", &lease_id).status.success());
    let symbol = kast_at(&fixture.binary, &fixture.home, &fixture.config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Added",
            "--workspace-root",
        ])
        .arg(&fixture.workspace)
        .output()
        .expect("dependent symbol command");
    assert!(
        symbol.status.success(),
        "dependent symbol failed: {}",
        String::from_utf8_lossy(&symbol.stdout)
    );
    let symbol: Value = serde_json::from_slice(&symbol.stdout).expect("symbol result");
    assert_eq!(symbol["result"]["outcome"], "RESOLVED", "{symbol}");
    assert_eq!(symbol["result"]["identity"]["fqName"], "sample.Added");
    backend.join().expect("dependent backend");
}

struct MutationFixture {
    temp: tempfile::TempDir,
    home: std::path::PathBuf,
    config_home: std::path::PathBuf,
    binary: std::path::PathBuf,
    workspace: std::path::PathBuf,
    content_file: std::path::PathBuf,
}

impl MutationFixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let content_file = temp.path().join("Added.kt");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(
            workspace.join("settings.gradle.kts"),
            "rootProject.name = \"mutation-fixture\"\n",
        )
        .expect("settings");
        std::fs::write(&content_file, "class Added\n").expect("content");
        let binary = write_active_kast_for_test(&home, &config_home);
        Self {
            temp,
            home,
            config_home,
            binary,
            workspace,
            content_file,
        }
    }

    fn acquire_lease(&self) -> String {
        acquire_workspace_lease(
            &self.binary,
            &self.home,
            &self.config_home,
            &self.workspace,
        )
    }

    fn apply(&self, key: &str, lease_id: &str) -> std::process::Output {
        kast_at(&self.binary, &self.home, &self.config_home)
            .args(["--output", "json", "agent", "add-file"])
            .arg("--workspace-root")
            .arg(&self.workspace)
            .arg("--file-path")
            .arg(self.workspace.join("src/Added.kt"))
            .arg("--content-file")
            .arg(&self.content_file)
            .args([
                "--apply",
                "--idempotency-key",
                key,
                "--lease-id",
                lease_id,
            ])
            .output()
            .expect("apply mutation")
    }
}

fn spawn_operation_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket_path: &std::path::Path,
    terminal_result: Option<Value>,
    dependent_symbol: bool,
) -> std::thread::JoinHandle<Vec<Value>> {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[indexer]\nhostCommand = \"idea\"\n",
    )
    .expect("config");
    let listener = UnixListener::bind(socket_path).expect("bind backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            "indexer",
            "test",
        )]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor");
    std::thread::spawn(move || {
        let mut requests = Vec::new();
        while requests.iter().all(|request: &Value| {
            request["method"]
                != if dependent_symbol {
                    "symbol/resolve"
                } else {
                    "mutation/submit"
                }
        }) {
            let (mut stream, _) = listener.accept().expect("accept client");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut line = String::new();
            reader.read_line(&mut line).expect("read request");
            let request: Value = serde_json::from_str(&line).expect("request json");
            let method = request["method"].as_str().expect("method");
            let result = match method {
                "runtime/status" => json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "indexer",
                    "backendVersion": "test",
                    "workspaceRoot": workspace,
                    "sourceModuleNames": [":fixture"],
                    "referenceIndexReady": true,
                    "schemaVersion": api_schema_version()
                }),
                "capabilities" => json!({
                    "backendName": "indexer",
                    "backendVersion": "test",
                    "workspaceRoot": workspace,
                    "readCapabilities": ["symbol/resolve"],
                    "mutationCapabilities": ["APPLY_EDITS"],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": api_schema_version()
                }),
                "mutation/submit" => match terminal_result.as_ref() {
                    Some(result) => {
                        let added = workspace.join("src/Added.kt");
                        std::fs::create_dir_all(added.parent().expect("source parent"))
                            .expect("source directory");
                        std::fs::write(&added, "package sample\nclass Added\n")
                            .expect("applied edit");
                        result.clone()
                    }
                    None => {
                        let added = workspace.join("src/Added.kt");
                        std::fs::create_dir_all(added.parent().expect("source parent"))
                            .expect("source directory");
                        std::fs::write(&added, "package sample\nclass Added\n")
                            .expect("server-owned edit");
                        requests.push(request);
                        return requests;
                    }
                },
                "symbol/resolve" => json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "symbol": {
                        "fqName": "sample.Added",
                        "kind": "CLASS",
                        "location": {
                            "filePath": workspace.join("src/Added.kt").display().to_string(),
                            "startOffset": 21,
                            "endOffset": 26,
                            "startLine": 2,
                            "startColumn": 7,
                            "preview": "class Added"
                        }
                    }
                }),
                other => panic!("unexpected method {other}"),
            };
            requests.push(request.clone());
            writeln!(
                stream,
                "{}",
                json!({"jsonrpc": "2.0", "id": request["id"], "result": result})
            )
            .expect("write response");
        }
        requests
    })
}

fn acquire_workspace_lease(
    binary: &std::path::Path,
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
) -> String {
    let output = kast_at(binary, home, config_home)
        .args([
            "--output",
            "json",
            "agent",
            "lease",
            "acquire",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("acquire workspace lease");
    assert!(
        output.status.success(),
        "workspace lease acquisition should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let payload: Value = serde_json::from_slice(&output.stdout).expect("workspace lease JSON");
    payload["result"]["leaseId"]
        .as_str()
        .expect("workspace lease id")
        .to_string()
}

fn mutation_result(deduplicated: bool) -> Value {
    json!({
        "type": "SUCCEEDED",
        "result": {
            "type": "SCOPE_MUTATION_RESULT",
            "response": {
                "editCount": 0,
                "affectedFiles": [],
                "createdFiles": [],
                "diagnostics": {"errorCount": 0, "warningCount": 0}
            }
        },
        "deduplicated": deduplicated
    })
}
