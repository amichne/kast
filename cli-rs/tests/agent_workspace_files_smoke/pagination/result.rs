#[test]
fn exact_root_inventory_returns_a_bounded_compact_public_result() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = create_workspace_index(&home, &workspace, "exact-inventory", 1);
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let socket = temp.path().join("workspace-files.sock");
    let module = |files: serde_json::Value, include_files: bool| {
        serde_json::json!({
            "snapshotToken": "snapshot-one",
            "modules": [{
                "name": "fixture.main",
                "sourceRoots": [workspace.join("src/main/kotlin").display().to_string()],
                "contentRoots": [workspace.display().to_string()],
                "dependencyModuleNames": [],
                "files": files,
                "returnedFileCount": if include_files { 1 } else { 0 },
                "filesTruncated": false,
                "fileCount": 1,
                "nextPageToken": null
            }],
            "schemaVersion": 5
        })
    };
    let server = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            ("raw/workspace-files", module(serde_json::json!([]), false)),
            (
                "raw/workspace-files",
                module(serde_json::json!([source.display().to_string()]), true),
            ),
            (
                "raw/workspace-files",
                serde_json::json!({
                    "snapshotToken": "snapshot-one",
                    "modules": [],
                    "schemaVersion": 5
                }),
            ),
            (
                "raw/workspace-files",
                serde_json::json!({
                    "snapshotToken": "snapshot-one",
                    "modules": [],
                    "schemaVersion": 5
                }),
            ),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--kind",
            "source",
            "--limit",
            "1",
        ])
        .output()
        .expect("workspace-files command");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files JSON result");
    assert_eq!(stdout["method"], "agent/workspace-files", "{stdout:#}");
    assert_eq!(
        stdout["result"]["cardinality"]["type"], "EXACT",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"]["totalCount"], 1,
        "{stdout:#}"
    );
    assert_eq!(stdout["result"]["returnedCount"], 1, "{stdout:#}");
    assert_eq!(
        stdout["result"]["files"][0]["paths"][0]["filePath"],
        source.display().to_string(),
        "{stdout:#}"
    );
    assert_eq!(
        stdout["result"]["files"][0]["paths"][0]["relativePath"],
        "src/main/kotlin/sample/Source0000.kt",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["result"]["files"][0]["kind"], "KOTLIN_SOURCE",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["result"]["files"][0]["package"],
        serde_json::json!({"type": "PROVEN_NAMED", "name": "sample"}),
        "{stdout:#}"
    );
    assert!(
        !stdout["result"]["truncated"].as_bool().unwrap_or(true),
        "{stdout:#}"
    );

    let requests = server.join().expect("scripted backend");
    assert_eq!(requests.len(), 6, "one admitted raw session: {requests:#?}");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/workspace-files")
            .count(),
        4,
        "{requests:#?}"
    );
}
