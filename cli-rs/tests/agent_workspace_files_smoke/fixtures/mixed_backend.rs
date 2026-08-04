fn spawn_small_mixed_workspace_files_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let source_root = workspace.join("src/main/kotlin");
    let module = |files: serde_json::Value, returned_file_count: usize| {
        serde_json::json!({
            "snapshotToken": "snapshot-mixed",
            "modules": [{
                "name": "fixture.main",
                "sourceRoots": [source_root.display().to_string()],
                "contentRoots": [workspace.display().to_string()],
                "dependencyModuleNames": [],
                "files": files,
                "returnedFileCount": returned_file_count,
                "filesTruncated": false,
                "fileCount": 2,
                "nextPageToken": null
            }],
            "schemaVersion": api_schema_version()
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-mixed",
        "modules": [],
        "schemaVersion": api_schema_version()
    });
    spawn_scripted_indexer_backend_for_published_workspace_read(
        home,
        config_home,
        workspace,
        socket,
        vec![
            ("raw/workspace-files", module(serde_json::json!([]), 0)),
            (
                "raw/workspace-files",
                module(
                    serde_json::json!([
                        source_root.join("sample/Script.kts").display().to_string(),
                        source_root
                            .join("sample/Source0000.kt")
                            .display()
                            .to_string()
                    ]),
                    2,
                ),
            ),
            ("raw/workspace-files", validation.clone()),
            ("raw/workspace-files", validation),
        ],
    )
}

fn spawn_structured_filter_workspace_files_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let source_root = workspace.join("src/main/kotlin/sample");
    let files = [
        "Good.kt",
        "WrongModule.kt",
        "WrongSourceSet.kt",
        "WrongPackage.kt",
        "LegacyOnly.kt",
    ]
    .map(|name| source_root.join(name).display().to_string());
    let module = |files: serde_json::Value, returned_file_count: usize| {
        serde_json::json!({
            "snapshotToken": "snapshot-structured-filters",
            "modules": [{
                "name": "fixture.main",
                "sourceRoots": [source_root.display().to_string()],
                "contentRoots": [workspace.display().to_string()],
                "dependencyModuleNames": [],
                "files": files,
                "returnedFileCount": returned_file_count,
                "filesTruncated": false,
                "fileCount": 5,
                "nextPageToken": null
            }],
            "schemaVersion": api_schema_version()
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-structured-filters",
        "modules": [],
        "schemaVersion": api_schema_version()
    });
    spawn_scripted_indexer_backend_for_published_workspace_read(
        home,
        config_home,
        workspace,
        socket,
        vec![
            ("raw/workspace-files", module(serde_json::json!([]), 0)),
            ("raw/workspace-files", module(serde_json::json!(files), 5)),
            ("raw/workspace-files", validation.clone()),
            ("raw/workspace-files", validation),
        ],
    )
}

fn spawn_single_owned_workspace_files_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
    owned_file: &std::path::Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let source_root = workspace.join("src/main/kotlin");
    let module = |files: serde_json::Value, returned_file_count: usize| {
        serde_json::json!({
            "snapshotToken": "snapshot-composition",
            "modules": [{
                "name": "fixture.main",
                "sourceRoots": [source_root.display().to_string()],
                "contentRoots": [workspace.display().to_string()],
                "dependencyModuleNames": [],
                "files": files,
                "returnedFileCount": returned_file_count,
                "filesTruncated": false,
                "fileCount": 1,
                "nextPageToken": null
            }],
            "schemaVersion": api_schema_version()
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-composition",
        "modules": [],
        "schemaVersion": api_schema_version()
    });
    spawn_scripted_indexer_backend_for_published_workspace_read(
        home,
        config_home,
        workspace,
        socket,
        vec![
            ("raw/workspace-files", module(serde_json::json!([]), 0)),
            (
                "raw/workspace-files",
                module(serde_json::json!([owned_file.display().to_string()]), 1),
            ),
            ("raw/workspace-files", validation.clone()),
            ("raw/workspace-files", validation),
        ],
    )
}
