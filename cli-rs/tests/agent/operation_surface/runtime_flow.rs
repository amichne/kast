#[test]
fn dependent_symbol_command_observes_the_completed_edit() {
    let fixture = MutationFixture::new();
    let backend = spawn_operation_backend(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &fixture.temp.path().join("idea.sock"),
        Some(mutation_result(false)),
        true,
    );
    assert!(fixture.apply("dependent-key").status.success());
    let symbol = kast(&fixture.home, &fixture.config_home)
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
        write_current_cli_install_manifest_for_test(&home, &config_home);
        Self {
            temp,
            home,
            config_home,
            workspace,
            content_file,
        }
    }

    fn apply(&self, key: &str) -> std::process::Output {
        kast(&self.home, &self.config_home)
            .args(["--output", "json", "agent", "add-file"])
            .arg("--workspace-root")
            .arg(&self.workspace)
            .arg("--file-path")
            .arg(self.workspace.join("src/Added.kt"))
            .arg("--content-file")
            .arg(&self.content_file)
            .args(["--apply", "--idempotency-key", key])
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
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    write_macos_plugin_workspace_metadata(workspace);
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
  "backendVersion": "test",
  "transport": "uds",
  "socketPath": "{}",
  "pid": {},
  "schemaVersion": 5
}}]"#,
            workspace.display(),
            socket_path.display(),
            std::process::id(),
        ),
    )
    .expect("descriptor");
    let listener = UnixListener::bind(socket_path).expect("bind backend");
    let workspace = workspace.to_path_buf();
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
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": workspace,
                    "schemaVersion": 5
                }),
                "capabilities" => json!({
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": workspace,
                    "readCapabilities": [],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 5
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
