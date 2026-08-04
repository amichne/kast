fn spawn_paged_workspace_files_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
    consumed_state: Option<serde_json::Value>,
    issued_token: Option<&'static str>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_paged_workspace_files_backend_with_completion(
        home,
        config_home,
        workspace,
        socket,
        consumed_state,
        issued_token,
        WorkspaceFilesReadCompletion::Revalidated,
    )
}

fn spawn_rejected_paged_workspace_files_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
    consumed_state: serde_json::Value,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_paged_workspace_files_backend_with_completion(
        home,
        config_home,
        workspace,
        socket,
        Some(consumed_state),
        None,
        WorkspaceFilesReadCompletion::RejectedBeforeRevalidation,
    )
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum WorkspaceFilesReadCompletion {
    Revalidated,
    RejectedBeforeRevalidation,
}

fn spawn_paged_workspace_files_backend_with_completion(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
    consumed_state: Option<serde_json::Value>,
    issued_token: Option<&'static str>,
    completion: WorkspaceFilesReadCompletion,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let mut responses = workspace_files_session_responses(workspace);
    if let Some(state) = consumed_state {
        responses.push((
            "raw/workspace-files-continuation",
            serde_json::json!({"type": "CONSUMED", "state": state}),
        ));
    }
    append_paged_workspace_files_collection(&mut responses, workspace, "snapshot-500");
    if let Some(page_token) = issued_token {
        responses.push((
            "raw/workspace-files-continuation",
            serde_json::json!({"type": "ISSUED", "pageToken": page_token}),
        ));
    }
    if completion == WorkspaceFilesReadCompletion::Revalidated {
        responses.push(("runtime/status", workspace_files_runtime_status(workspace)));
    }
    spawn_sequenced_indexer_backend(home, config_home, workspace, socket, responses)
}

fn workspace_files_runtime_status(workspace: &std::path::Path) -> serde_json::Value {
    serde_json::json!({
        "state": "READY",
        "healthy": true,
        "active": true,
        "indexing": false,
        "backendName": "indexer",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "schemaVersion": api_schema_version()
    })
}

fn workspace_files_session_responses(
    workspace: &std::path::Path,
) -> Vec<(&'static str, serde_json::Value)> {
    let capabilities = serde_json::json!({
        "backendName": "indexer",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "readCapabilities": ["WORKSPACE_FILES"],
        "mutationCapabilities": [],
        "limits": {
            "requestTimeoutMillis": 60000,
            "maxResults": 1000,
            "maxConcurrentRequests": 4
        },
        "schemaVersion": api_schema_version()
    });
    vec![
        ("runtime/status", workspace_files_runtime_status(workspace)),
        ("capabilities", capabilities),
    ]
}

fn append_paged_workspace_files_collection(
    responses: &mut Vec<(&'static str, serde_json::Value)>,
    workspace: &std::path::Path,
    revalidation_snapshot_token: &str,
) {
    let source_root = workspace.join("src/main/kotlin");
    let page = |range: std::ops::Range<usize>, next_page_token: Option<&str>| {
        let files = range
            .map(|index| {
                source_root
                    .join(format!("sample/Source{index:04}.kt"))
                    .display()
                    .to_string()
            })
            .collect::<Vec<_>>();
        serde_json::json!({
            "snapshotToken": "snapshot-500",
            "modules": [{
                "name": "fixture.main",
                "sourceRoots": [source_root.display().to_string()],
                "contentRoots": [workspace.display().to_string()],
                "dependencyModuleNames": [],
                "returnedFileCount": files.len(),
                "filesTruncated": next_page_token.is_some(),
                "fileCount": 500,
                "nextPageToken": next_page_token,
                "files": files
            }],
            "schemaVersion": api_schema_version()
        })
    };
    let collection_validation = serde_json::json!({
        "snapshotToken": "snapshot-500",
        "modules": [],
        "schemaVersion": api_schema_version()
    });
    let barrier_validation = serde_json::json!({
        "snapshotToken": revalidation_snapshot_token,
        "modules": [],
        "schemaVersion": api_schema_version()
    });
    responses.extend([
        (
            "raw/workspace-files",
            serde_json::json!({
                "snapshotToken": "snapshot-500",
                "modules": [{
                    "name": "fixture.main",
                    "sourceRoots": [source_root.display().to_string()],
                    "contentRoots": [workspace.display().to_string()],
                    "dependencyModuleNames": [],
                    "returnedFileCount": 0,
                    "filesTruncated": false,
                    "fileCount": 500,
                    "nextPageToken": null,
                    "files": []
                }],
                "schemaVersion": api_schema_version()
            }),
        ),
        ("raw/workspace-files", page(0..200, Some("raw-page-2"))),
        ("raw/workspace-files", page(200..400, Some("raw-page-3"))),
        ("raw/workspace-files", page(400..500, None)),
        ("raw/workspace-files", collection_validation),
        ("raw/workspace-files", barrier_validation),
    ]);
}

fn run_workspace_files_page(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    page_token: Option<&str>,
) -> std::process::Output {
    let mut command = kast(home, config_home);
    command.args([
        "--output",
        "json",
        "agent",
        "workspace-files",
        "--workspace-root",
        workspace.to_str().expect("UTF-8 workspace"),
        "--kind",
        "source",
        "--limit",
        "200",
        "--verbose",
    ]);
    if let Some(page_token) = page_token {
        command.args(["--page-token", page_token]);
    }
    command.output().expect("workspace-files page")
}

fn workspace_files_issue_state(requests: &[serde_json::Value]) -> serde_json::Value {
    requests
        .iter()
        .find(|request| {
            request["method"] == "raw/workspace-files-continuation"
                && request["params"]["action"] == "ISSUE"
        })
        .unwrap_or_else(|| panic!("missing workspace-files continuation issue: {requests:#?}"))
        ["params"]["state"]
        .clone()
}
