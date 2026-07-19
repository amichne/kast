mod support;

use serde_json::{Value, json};
use support::*;

#[test]
fn applied_mutation_requires_idempotency_key_before_runtime_discovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path();
    let content_file = temp.path().join("Added.kt");
    let target = temp.path().join("Target.kt");
    std::fs::write(&content_file, "class Added\n").expect("content");
    let target = target.to_str().expect("target").to_string();
    let content = content_file.to_str().expect("content").to_string();
    let cases = [
        vec![
            "rename".to_string(),
            "--symbol".to_string(),
            "sample.Example".to_string(),
            "--new-name".to_string(),
            "Renamed".to_string(),
        ],
        vec![
            "add-file".to_string(),
            "--file-path".to_string(),
            target.clone(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-declaration".to_string(),
            "--inside-file".to_string(),
            target.clone(),
            "--at".to_string(),
            "file-bottom".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-implementation".to_string(),
            "--inside-scope".to_string(),
            "sample.Example".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-statement".to_string(),
            "--inside-scope".to_string(),
            "sample.Example.run".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "replace-declaration".to_string(),
            "--symbol".to_string(),
            "sample.Example".to_string(),
            "--content-file".to_string(),
            content,
        ],
    ];

    for args in cases {
        let output = kast(&home, &config_home)
            .args(["--output", "json", "agent"])
            .args(args)
            .args([
                "--workspace-root",
                workspace.to_str().expect("workspace root"),
            ])
            .arg("--apply")
            .output()
            .expect("applied mutation");

        assert!(!output.status.success(), "missing key must fail");
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("structured usage error");
        assert_eq!(stdout["error"]["code"], "AGENT_USAGE", "{stdout}");
        assert!(
            stdout["error"]["message"]
                .as_str()
                .is_some_and(|message| message.contains("--idempotency-key")),
            "{stdout}"
        );
    }
}

#[test]
fn operation_selector_requires_exactly_one_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    for args in [
        vec!["agent", "operation", "status"],
        vec![
            "agent",
            "operation",
            "cancel",
            "--operation-id",
            "00000000-0000-0000-0000-000000000001",
            "--idempotency-key",
            "issue-333",
        ],
    ] {
        let output = kast(&home, &config_home)
            .args(args)
            .output()
            .expect("operation selector parse");
        assert!(!output.status.success(), "invalid selector must fail");
        let diagnostic = format!(
            "{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        assert!(
            diagnostic.contains("required") || diagnostic.contains("cannot be used"),
            "{diagnostic}"
        );
    }
}

#[test]
fn applied_add_file_submits_typed_mutation_request() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let content_file = temp.path().join("Added.kt");
    let target = workspace.join("src/Added.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"operation-fixture\"\n",
    )
    .expect("settings");
    std::fs::write(&content_file, "class Added\n").expect("content");
    write_current_cli_install_manifest_for_test(&home, &config_home);
    let begin = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "task",
            "begin",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("begin workspace task");
    assert!(
        begin.status.success(),
        "task begin failed: stdout={} stderr={}",
        String::from_utf8_lossy(&begin.stdout),
        String::from_utf8_lossy(&begin.stderr),
    );
    let begin: Value = serde_json::from_slice(&begin.stdout).expect("task begin JSON");
    let workspace_task_id = begin["result"]["taskId"].clone();
    let canonical_target = workspace
        .canonicalize()
        .expect("canonical workspace")
        .join("src/Added.kt");
    let backend = spawn_operation_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        "mutation/submit",
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--file-path",
            target.to_str().expect("target"),
            "--content-file",
            content_file.to_str().expect("content"),
            "--apply",
            "--idempotency-key",
            "issue-333-add-file",
        ])
        .output()
        .expect("submit mutation");

    assert!(
        output.status.success(),
        "submit should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let requests = backend.join().expect("backend");
    let submit = requests
        .iter()
        .find(|request| request["method"] == "mutation/submit")
        .expect("mutation submit request");
    assert_eq!(submit["params"]["type"], "ADD_FILE", "{submit}");
    assert_eq!(submit["params"]["workspaceTaskId"], workspace_task_id);
    assert_eq!(
        submit["params"]["idempotencyKey"], "issue-333-add-file",
        "{submit}"
    );
    assert_eq!(
        submit["params"]["request"]["filePath"],
        canonical_target.to_str().unwrap()
    );
    assert_eq!(
        submit["params"]["request"]["contentFile"],
        content_file.to_str().unwrap()
    );
}

#[test]
fn operation_status_and_cancel_preserve_typed_backend_snapshots() {
    let cases = [
        (
            "status",
            "mutation/status",
            vec!["--operation-id", "00000000-0000-0000-0000-000000000001"],
            "BY_OPERATION_ID",
        ),
        (
            "cancel",
            "mutation/cancel",
            vec!["--idempotency-key", "issue-333-add-file"],
            "BY_IDEMPOTENCY_KEY",
        ),
    ];

    for (command, method, selector_args, selector_type) in cases {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("idea.sock");
        std::fs::create_dir_all(&workspace).expect("workspace");
        let backend =
            spawn_operation_backend(&home, &config_home, &workspace, &socket_path, method);
        let mut args = vec![
            "--output",
            "json",
            "agent",
            "operation",
            command,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ];
        args.extend(selector_args);

        let output = kast(&home, &config_home)
            .args(args)
            .output()
            .expect("operation command");

        assert!(
            output.status.success(),
            "{command} should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("operation output");
        assert_eq!(
            stdout["result"]["operation"]["operationId"],
            "00000000-0000-0000-0000-000000000001"
        );
        assert_eq!(stdout["result"]["operation"]["state"], "QUEUED");
        let requests = backend.join().expect("backend");
        let terminal = requests
            .iter()
            .find(|request| request["method"] == method)
            .expect("operation request");
        assert_eq!(terminal["params"]["type"], selector_type, "{terminal}");
    }
}

fn spawn_operation_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket_path: &std::path::Path,
    terminal_method: &'static str,
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
  "schemaVersion": 3
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
        while requests
            .iter()
            .all(|request: &Value| request["method"] != terminal_method)
        {
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
                    "schemaVersion": 3
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
                    "schemaVersion": 3
                }),
                "mutation/submit" => mutation_snapshot(true),
                "mutation/status" | "mutation/cancel" => mutation_snapshot(false),
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

fn mutation_snapshot(receipt: bool) -> Value {
    let operation = json!({
        "operationId": "00000000-0000-0000-0000-000000000001",
        "idempotencyKey": "issue-333-add-file",
        "mutationKind": "ADD_FILE",
        "state": {"type": "QUEUED", "trace": {"enteredStages": [], "editApplicationState": "NOT_STARTED"}, "cancellationRequested": false}
    });
    if receipt {
        json!({"operation": operation, "deduplicated": false})
    } else {
        operation
    }
}
