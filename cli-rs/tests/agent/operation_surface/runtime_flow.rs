use sha2::Digest as _;
use std::os::unix::process::CommandExt as _;

#[test]
fn dependent_symbol_command_observes_the_completed_edit() {
    let fixture = MutationFixture::new();
    let backend = spawn_operation_backend(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &fixture.temp.path().join("indexer.sock"),
        true,
    );
    let plan = fixture.plan();
    assert!(plan.status.success(), "plan failed: {plan:?}");
    let plan = decode_public_result(&plan);
    let plan_id = plan["planId"].as_str().expect("plan id");
    let applied = fixture.apply(plan_id);
    assert!(applied.status.success(), "apply failed: {applied:?}");
    assert_eq!(
        std::fs::read_to_string(fixture.target()).expect("applied target"),
        fixture.content,
    );
    let symbol = fixture
        .public_command()
        .args([
            "--output",
            "json",
            "symbol",
            "resolve",
            "--query",
            "Added",
        ])
        .output()
        .expect("dependent symbol command");
    assert!(
        symbol.status.success(),
        "dependent symbol failed: {}",
        String::from_utf8_lossy(&symbol.stdout)
    );
    let symbol = decode_public_result(&symbol);
    assert_eq!(symbol["type"], "resolved", "{symbol}");
    assert_eq!(symbol["symbol"]["fqName"], "sample.Added");
    let requests = backend.join().expect("dependent backend");
    assert_eq!(
        requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
            .collect::<Vec<_>>(),
        [
            "change/plan-add-file",
            "change/apply-add-file",
            "symbol/resolve",
        ],
    );
}

struct MutationFixture {
    temp: tempfile::TempDir,
    home: std::path::PathBuf,
    config_home: std::path::PathBuf,
    binary: std::path::PathBuf,
    workspace: std::path::PathBuf,
    content: &'static str,
}

impl MutationFixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(
            workspace.join("settings.gradle.kts"),
            "rootProject.name = \"mutation-fixture\"\n",
        )
        .expect("settings");
        std::fs::create_dir_all(workspace.join("src")).expect("source root");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let binary = write_active_kast_for_test(&home, &config_home);
        Self {
            temp,
            home,
            config_home,
            binary,
            workspace,
            content: "package sample\nclass Added\n",
        }
    }

    fn target(&self) -> std::path::PathBuf {
        self.workspace.join("src/Added.kt")
    }

    fn public_command(&self) -> Command {
        let mut command = kast_at(&self.binary, &self.home, &self.config_home);
        command
            .arg0("kast")
            .current_dir(&self.workspace)
            .env("KAST_HOME", self.home.join(".local/share/kast"));
        command
    }

    fn plan(&self) -> std::process::Output {
        let mut command = self.public_command();
        command
            .args(["--output", "json", "change", "plan", "add-file", "--file"])
            .arg(self.target());
        run_with_stdin(command, self.content)
    }

    fn apply(&self, plan_id: &str) -> std::process::Output {
        self.public_command()
            .args([
                "--output",
                "json",
                "change",
                "apply",
                "--plan-id",
                plan_id,
            ])
            .output()
            .expect("apply verified add-file plan")
    }
}

fn run_with_stdin(mut command: Command, stdin: &str) -> std::process::Output {
    let mut child = command
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .expect("spawn public Kast command");
    child
        .stdin
        .take()
        .expect("stdin")
        .write_all(stdin.as_bytes())
        .expect("write stdin");
    child.wait_with_output().expect("wait for public Kast command")
}

fn decode_public_result(output: &std::process::Output) -> Value {
    let envelope: Value =
        serde_json::from_slice(&output.stdout).expect("structured public command output");
    if envelope["schemaVersion"] == 3 && envelope["result"].is_object() {
        envelope["result"].clone()
    } else {
        envelope
    }
}

fn spawn_operation_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket_path: &std::path::Path,
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
    publish_scripted_workspace_capabilities(&workspace);
    let published = published_workspace_generation_for_test(&workspace)
        .expect("published operation workspace generation");
    let exact_test_runtime = publish_exact_test_runtime(
        home,
        &workspace,
        socket_path,
        "indexer",
        "test",
        &descriptor_dir,
    );
    std::thread::spawn(move || {
        let _exact_test_runtime = exact_test_runtime;
        let mut requests = Vec::new();
        let content = "package sample\nclass Added\n";
        let target = workspace.join("src/Added.kt");
        let plan_id = verified_add_file_plan_id(&workspace, &target, content);
        let terminal_method = if dependent_symbol {
            "symbol/resolve"
        } else {
            "change/apply-add-file"
        };
        while requests.iter().all(|request: &Value| {
            request["method"] != terminal_method
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
                    "backendName": "indexer",
                    "backendVersion": "test",
                    "workspaceRoot": workspace,
                    "sourceModuleNames": [":fixture"],
                    "readiness": {
                        "runtime": {"type": "READY"}, "model": {"type": "READY"},
                        "references": {"type": "READY"}, "semanticGraph": {"type": "READY"},
                        "mutation": {"type": "READY"}
                    },
                    "publishedWorkspaceGeneration": published.clone(),
                    "schemaVersion": api_schema_version()
                }),
                "capabilities" => json!({
                    "backendName": "indexer",
                    "backendVersion": "test",
                    "workspaceRoot": workspace,
                    "readCapabilities": ["symbol/resolve"],
                    "mutationCapabilities": [
                        "APPLY_EDITS",
                        "FILE_OPERATIONS",
                        "EXACT_FILE_OBSERVATION",
                        "EXACT_FILE_IMAGE_CAS",
                        "VERIFY_MUTATION_POSTCONDITION",
                        "MUTATION_SCRATCH_RECOVERY",
                        "REFRESH_WORKSPACE"
                    ],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": api_schema_version()
                }),
                "change/plan-add-file" => json!({
                    "planId": plan_id,
                    "planVersion": 0,
                    "stage": "AWAITING_APPROVAL",
                    "operation": "add-file",
                    "preview": {
                        "targetPath": target,
                        "proposedContent": content,
                        "generation": 7,
                    },
                    "schemaVersion": api_schema_version(),
                }),
                "change/apply-add-file" => {
                    std::fs::create_dir_all(target.parent().expect("source parent"))
                        .expect("source directory");
                    std::fs::write(&target, content).expect("server-owned edit");
                    json!({
                        "outcome": "VERIFIED",
                        "planId": plan_id,
                        "planVersion": 5,
                        "operation": "add-file",
                        "publication": {"generation": 8},
                        "identity": {
                            "targetPath": target,
                            "packageName": "sample",
                            "declarations": [{"name": "Added", "kind": "CLASS"}],
                        },
                        "postimageSha256": source_sha256(content.as_bytes()),
                        "schemaVersion": api_schema_version(),
                    })
                }
                "symbol/resolve" => json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "selectorHandle": "ksh1.operation-added",
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

fn source_sha256(content: &[u8]) -> String {
    hex::encode(sha2::Sha256::digest(content))
}

fn verified_add_file_plan_id(
    workspace: &std::path::Path,
    target: &std::path::Path,
    content: &str,
) -> String {
    format!(
        "af-{}",
        source_sha256(
            format!(
                "{}\0{}\0{}\07",
                workspace.display(),
                target.display(),
                content,
            )
            .as_bytes(),
        ),
    )
}
