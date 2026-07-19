mod support;

use support::*;

#[test]
fn lifecycle_commands_render_human_text_when_selected_and_json_when_selected() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    write_macos_plugin_workspace_metadata(&workspace);

    let human = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "developer",
            "runtime",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("status human");

    assert!(
        human.status.success(),
        "human status should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&human.stdout),
        String::from_utf8_lossy(&human.stderr)
    );
    let stdout = String::from_utf8_lossy(&human.stdout);
    assert!(
        stdout.starts_with("Kast status\n===========\n"),
        "status should render a readable summary when human output is selected: {stdout}"
    );
    assert!(
        stdout.contains("No runtime candidates were found."),
        "status should include an actionable empty-state message: {stdout}"
    );
    assert!(
        stdout.contains("Next steps\n----------"),
        "status should render Markdown section headings: {stdout}"
    );
    assert!(
        !stdout.contains("# Kast status") && !stdout.contains("`kast up`"),
        "status should not dump raw Markdown control tokens: {stdout}"
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&human.stdout).is_err(),
        "human status output should not be JSON"
    );

    let json = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("status json");

    assert!(
        json.status.success(),
        "json status should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&json.stdout),
        String::from_utf8_lossy(&json.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&json.stdout).expect("status json");
    assert_eq!(
        stdout["candidates"].as_array().expect("candidates").len(),
        0
    );
}

#[test]
#[cfg(not(target_os = "macos"))]
fn stop_removes_every_matching_stale_headless_descriptor() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let descriptor_dir = default_descriptor_dir(&home);
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    write_macos_plugin_workspace_metadata(&workspace);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        format!(
            r#"[
  {{
    "workspaceRoot": "{}",
    "backendName": "headless",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 0,
    "schemaVersion": 3
  }},
  {{
    "workspaceRoot": "{}",
    "backendName": "headless",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 999999999,
    "schemaVersion": 3
  }},
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 0,
    "schemaVersion": 3
  }}
]"#,
            workspace.display(),
            temp.path().join("one.sock").display(),
            workspace.display(),
            temp.path().join("two.sock").display(),
            workspace.display(),
            temp.path().join("idea.sock").display(),
        ),
    )
    .expect("descriptors");

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "stop",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "headless",
        ])
        .output()
        .expect("stop");

    assert!(
        stop.status.success(),
        "stop should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&stop.stdout),
        String::from_utf8_lossy(&stop.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&stop.stdout).expect("stop json");
    assert_eq!(stdout["backendName"], "headless");
    assert_eq!(stdout["stopped"], true);
    assert_eq!(stdout["stoppedCount"], 2);
    assert_eq!(
        stdout["candidates"].as_array().expect("candidates").len(),
        2
    );

    let remaining: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(descriptor_dir.join("daemons.json"))
            .expect("remaining descriptors"),
    )
    .expect("remaining descriptor json");
    let remaining = remaining.as_array().expect("remaining descriptor array");
    assert_eq!(remaining.len(), 1);
    assert_eq!(remaining[0]["backendName"], "idea");
}

#[test]
fn stop_requests_reachable_idea_backend_shutdown() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let descriptor_dir = default_descriptor_dir(&home);
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    write_macos_plugin_workspace_metadata(&workspace);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("config");
    let descriptor_file = descriptor_dir.join("daemons.json");
    std::fs::write(
        &descriptor_file,
        format!(
            r#"[
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": {},
    "schemaVersion": 3
  }}
]"#,
            workspace.display(),
            socket_path.display(),
            std::process::id(),
        ),
    )
    .expect("descriptors");

    let listener = UnixListener::bind(&socket_path).expect("bind fake idea socket");
    let server_workspace = workspace.clone();
    let server_descriptor_file = descriptor_file.clone();
    let handle = thread::spawn(move || {
        let mut methods = Vec::new();
        for _ in 0..3 {
            let (mut stream, _) = listener.accept().expect("accept fake idea client");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read fake idea request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            methods.push(method.clone());
            let result = match method.as_str() {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "capabilities" => serde_json::json!({
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                "runtime/shutdown" => {
                    let result = serde_json::json!({
                        "accepted": true,
                        "action": "SHUTDOWN",
                        "backendName": "idea",
                        "backendVersion": "test",
                        "workspaceRoot": server_workspace.display().to_string(),
                        "schemaVersion": 3
                    });
                    writeln!(
                        stream,
                        "{}",
                        serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
                    )
                    .expect("write shutdown response");
                    std::fs::remove_file(&server_descriptor_file).expect("remove descriptor");
                    break;
                }
                other => panic!("unexpected fake idea method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write fake idea response");
        }
        methods
    });

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "stop",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("stop");

    assert!(
        stop.status.success(),
        "stop should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&stop.stdout),
        String::from_utf8_lossy(&stop.stderr)
    );
    let methods = handle.join().expect("fake idea server");
    assert_eq!(
        methods,
        vec!["runtime/status", "capabilities", "runtime/shutdown"]
    );
    let stdout: serde_json::Value = serde_json::from_slice(&stop.stdout).expect("stop json");
    assert_eq!(stdout["backendName"], "idea");
    assert_eq!(stdout["stopped"], true);
    assert_eq!(stdout["stoppedCount"], 1);
    assert_eq!(stdout["candidates"][0]["lifecycleAccepted"], true);
    assert_eq!(
        stdout["candidates"][0]["lifecycleMethod"],
        "runtime/shutdown"
    );
    assert_eq!(stdout["candidates"][0]["lifecycleAction"], "SHUTDOWN");
    assert!(
        !descriptor_file.exists(),
        "IDEA lifecycle shutdown should remove the descriptor"
    );
}

#[test]
fn restart_requests_reachable_idea_backend_restart() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let descriptor_dir = default_descriptor_dir(&home);
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    write_macos_plugin_workspace_metadata(&workspace);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        format!(
            r#"[
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": {},
    "schemaVersion": 3
  }},
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test-stale",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 1,
    "schemaVersion": 3
  }}
]"#,
            workspace.display(),
            socket_path.display(),
            std::process::id(),
            workspace.display(),
            temp.path().join("stale-idea.sock").display(),
        ),
    )
    .expect("descriptors");

    let listener = UnixListener::bind(&socket_path).expect("bind fake idea socket");
    let server_workspace = workspace.clone();
    let handle = thread::spawn(move || {
        let mut methods = Vec::new();
        for _ in 0..5 {
            let (mut stream, _) = listener.accept().expect("accept fake idea client");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read fake idea request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            methods.push(method.clone());
            let result = match method.as_str() {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "capabilities" => serde_json::json!({
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                "runtime/restart" => serde_json::json!({
                    "accepted": true,
                    "action": "RESTART",
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                other => panic!("unexpected fake idea method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write fake idea response");
        }
        methods
    });

    let restart = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "restart",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("restart");

    assert!(
        restart.status.success(),
        "restart should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&restart.stdout),
        String::from_utf8_lossy(&restart.stderr)
    );
    let methods = handle.join().expect("fake idea server");
    assert_eq!(
        methods,
        vec![
            "runtime/status",
            "capabilities",
            "runtime/restart",
            "runtime/status",
            "capabilities",
        ]
    );
    let stdout: serde_json::Value = serde_json::from_slice(&restart.stdout).expect("restart json");
    assert_eq!(stdout["backendName"], "idea");
    assert_eq!(stdout["stop"]["stopped"], true);
    assert_eq!(stdout["stop"]["stoppedCount"], 2);
    assert_eq!(stdout["stop"]["candidates"][0]["lifecycleAccepted"], true);
    assert_eq!(
        stdout["stop"]["candidates"][0]["lifecycleMethod"],
        "runtime/restart"
    );
    assert_eq!(
        stdout["stop"]["candidates"][0]["lifecycleAction"],
        "RESTART"
    );
    assert_eq!(stdout["ensure"]["started"], false);
    assert_eq!(
        stdout["ensure"]["selected"]["descriptor"]["backendName"],
        "idea"
    );
    let remaining: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(descriptor_dir.join("daemons.json"))
            .expect("remaining descriptors"),
    )
    .expect("remaining descriptor json");
    let remaining = remaining.as_array().expect("remaining descriptor array");
    assert_eq!(remaining.len(), 1);
    assert_eq!(remaining[0]["backendVersion"], "test");
}

#[test]
fn lifecycle_commands_walk_up_to_workspace_marker_when_root_is_omitted() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let nested = workspace.join("app/src/main/kotlin");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&nested).expect("nested");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "pluginManagement {}\n",
    )
    .expect("settings marker");
    let expected_workspace = std::fs::canonicalize(&workspace).expect("canonical workspace");
    write_macos_plugin_workspace_metadata(&expected_workspace);

    let status = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(&nested)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args(["--output", "json", "developer", "runtime", "status"])
        .output()
        .expect("status");

    assert!(
        status.status.success(),
        "status should resolve workspace marker from cwd: stdout={}, stderr={}",
        String::from_utf8_lossy(&status.stdout),
        String::from_utf8_lossy(&status.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&status.stdout).expect("status json");
    assert_eq!(
        stdout["workspaceRoot"].as_str().expect("workspace root"),
        expected_workspace.to_str().expect("workspace path")
    );
}
