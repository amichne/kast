mod support;

use support::*;

fn run_workspace_files_with_output(
    output_format: &str,
    extra_args: &[&str],
) -> std::process::Output {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    kast(&home, &config_home)
        .args(["--output", output_format, "agent", "workspace-files"])
        .args(extra_args)
        .output()
        .expect("workspace-files command")
}

fn run_workspace_files(extra_args: &[&str]) -> std::process::Output {
    run_workspace_files_with_output("json", extra_args)
}

fn assert_typed_boundary(extra_args: &[&str]) -> serde_json::Value {
    let output = run_workspace_files(extra_args);
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files JSON error");
    assert!(
        stdout["error"]["code"].is_string(),
        "typed admission must return a structured error: {stdout:#}"
    );
    assert!(
        stdout["error"]["details"]["admittedQuery"].is_object(),
        "typed query admission must precede exact-root runtime admission: {stdout:#}"
    );
    stdout
}

fn assert_usage_error(extra_args: &[&str]) {
    let output = run_workspace_files(extra_args);
    assert_eq!(
        output.status.code(),
        Some(2),
        "args={extra_args:?} stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files usage JSON");
    assert_eq!(stdout["code"], "CLI_USAGE", "{stdout:#}");
}

fn create_workspace_index(
    home: &std::path::Path,
    workspace: &std::path::Path,
    workspace_id: &str,
    source_count: usize,
) -> workspace_files::WorkspaceIndexFixture {
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let workspaces_data = default_install_root(home).join("state/data/workspaces");
    std::fs::create_dir_all(workspaces_data.join("local")).expect("local workspace data");
    std::fs::write(
        workspaces_data.join("local-workspaces.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            workspace.display().to_string(): workspace_id
        }))
        .expect("workspace registry JSON"),
    )
    .expect("workspace registry");
    let mut sanitized_workspace = String::new();
    for character in workspace.display().to_string().chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
            sanitized_workspace.push(character);
        } else if !sanitized_workspace.ends_with('-') {
            sanitized_workspace.push('-');
        }
    }
    let sanitized_workspace = sanitized_workspace
        .trim_matches('-')
        .chars()
        .take(80)
        .collect::<String>();
    let database_path = if workspace.starts_with(std::env::temp_dir()) {
        workspace.join(".gradle/kast/cache/source-index.db")
    } else {
        workspaces_data
            .join("local")
            .join(format!("{sanitized_workspace}--{workspace_id}"))
            .join("cache/source-index.db")
    };
    let index =
        workspace_files::WorkspaceIndexFixture::at_database_path(&workspace, &database_path);
    index.seed_high_cardinality_sources(source_count);
    index.seed_progress(
        "app",
        "COMPLETE",
        i64::try_from(source_count).expect("fixture source count fits i64"),
        i64::try_from(source_count).expect("fixture source count fits i64"),
    );
    index
}

fn spawn_paged_workspace_files_backend(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    socket: &std::path::Path,
    consumed_state: Option<serde_json::Value>,
    issued_token: Option<&'static str>,
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
    spawn_sequenced_idea_backend(home, config_home, workspace, socket, responses)
}

fn workspace_files_session_responses(
    workspace: &std::path::Path,
) -> Vec<(&'static str, serde_json::Value)> {
    let runtime = serde_json::json!({
        "state": "READY",
        "healthy": true,
        "active": true,
        "indexing": false,
        "backendName": "idea",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "schemaVersion": 3
    });
    let capabilities = serde_json::json!({
        "backendName": "idea",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "readCapabilities": ["WORKSPACE_FILES"],
        "mutationCapabilities": [],
        "limits": {
            "requestTimeoutMillis": 60000,
            "maxResults": 1000,
            "maxConcurrentRequests": 4
        },
        "schemaVersion": 3
    });
    vec![("runtime/status", runtime), ("capabilities", capabilities)]
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
            "schemaVersion": 3
        })
    };
    let collection_validation = serde_json::json!({
        "snapshotToken": "snapshot-500",
        "modules": [],
        "schemaVersion": 3
    });
    let barrier_validation = serde_json::json!({
        "snapshotToken": revalidation_snapshot_token,
        "modules": [],
        "schemaVersion": 3
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
                "schemaVersion": 3
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
        "--backend",
        "idea",
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
            "schemaVersion": 3
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-mixed",
        "modules": [],
        "schemaVersion": 3
    });
    spawn_scripted_idea_backend(
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
            "schemaVersion": 3
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-structured-filters",
        "modules": [],
        "schemaVersion": 3
    });
    spawn_scripted_idea_backend(
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
            "schemaVersion": 3
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-composition",
        "modules": [],
        "schemaVersion": 3
    });
    spawn_scripted_idea_backend(
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

fn seed_structured_filter_evidence(index: &workspace_files::WorkspaceIndexFixture) {
    let connection = index.connection();
    connection
        .execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (2, 'sample.target'), (3, 'sample.other')",
            [],
        )
        .expect("filter package names");
    for (filename, package_id, legacy_module, legacy_source_set, project, source_set) in [
        (
            "Good.kt",
            Some(2),
            None,
            None,
            Some(":app"),
            Some("integrationTest"),
        ),
        (
            "WrongModule.kt",
            Some(2),
            None,
            None,
            Some(":other"),
            Some("integrationTest"),
        ),
        (
            "WrongSourceSet.kt",
            Some(2),
            None,
            None,
            Some(":app"),
            Some("main"),
        ),
        (
            "WrongPackage.kt",
            Some(3),
            None,
            None,
            Some(":app"),
            Some("integrationTest"),
        ),
        (
            "LegacyOnly.kt",
            None,
            Some("gradle:.#:app"),
            Some("integrationTest"),
            None,
            None,
        ),
    ] {
        index.insert_manifest_file(1, "src/main/kotlin/sample", filename, true);
        if let Some(package_id) = package_id {
            connection
                .execute(
                    "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (1, ?, ?, 'PROVEN_NAMED', NULL, ?, ?)",
                    rusqlite::params![filename, package_id, legacy_module, legacy_source_set],
                )
                .expect("proven filter metadata");
        } else {
            connection
                .execute(
                    "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (1, ?, NULL, 'UNPROVEN', 'LEGACY_TEXT_ONLY', ?, ?)",
                    rusqlite::params![filename, legacy_module, legacy_source_set],
                )
                .expect("legacy-only filter metadata");
        }
        if let (Some(project), Some(source_set)) = (project, source_set) {
            index.insert_project_evidence(1, filename, ".", project, source_set);
        }
    }
    index.seed_progress("app", "COMPLETE", 5, 5);
}

fn grouped_cardinality<'a>(
    output: &'a serde_json::Value,
    group: &str,
    value: &str,
) -> &'a serde_json::Value {
    output["result"]["groupedCardinalities"][group]
        .as_array()
        .expect("grouped cardinalities")
        .iter()
        .find(|entry| entry["value"] == value)
        .unwrap_or_else(|| panic!("missing {group}={value} group: {output:#}"))
}

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
            "schemaVersion": 3
        })
    };
    let server = spawn_scripted_idea_backend(
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
                    "schemaVersion": 3
                }),
            ),
            (
                "raw/workspace-files",
                serde_json::json!({
                    "snapshotToken": "snapshot-one",
                    "modules": [],
                    "schemaVersion": 3
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
            "--backend",
            "idea",
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

#[test]
fn public_continuations_return_five_hundred_files_as_200_200_100_without_gaps() {
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
    let _index = create_workspace_index(&home, &workspace, "paged-inventory", 500);

    let first_server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("page-1.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440001"),
    );
    let first_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(
        first_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&first_output.stdout),
        String::from_utf8_lossy(&first_output.stderr),
    );
    let first: serde_json::Value =
        serde_json::from_slice(&first_output.stdout).expect("first workspace-files page");
    let first_requests = first_server.join().expect("first workspace-files backend");
    let first_state = workspace_files_issue_state(&first_requests);

    let second_server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("page-2.sock"),
        Some(first_state),
        Some("550e8400-e29b-41d4-a716-446655440002"),
    );
    let second_output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440001"),
    );
    assert!(
        second_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&second_output.stdout),
        String::from_utf8_lossy(&second_output.stderr),
    );
    let second: serde_json::Value =
        serde_json::from_slice(&second_output.stdout).expect("second workspace-files page");
    let second_requests = second_server
        .join()
        .expect("second workspace-files backend");
    assert_eq!(
        second_requests
            .iter()
            .map(|request| request["method"].as_str().expect("request method"))
            .take(4)
            .collect::<Vec<_>>(),
        vec![
            "runtime/status",
            "capabilities",
            "raw/workspace-files-continuation",
            "raw/workspace-files",
        ],
        "public continuation must be consumed before any inventory collection"
    );
    assert_eq!(second_requests[2]["params"]["action"], "CONSUME");
    let second_state = workspace_files_issue_state(&second_requests);

    let third_server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("page-3.sock"),
        Some(second_state),
        None,
    );
    let third_output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440002"),
    );
    assert!(
        third_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&third_output.stdout),
        String::from_utf8_lossy(&third_output.stderr),
    );
    let third: serde_json::Value =
        serde_json::from_slice(&third_output.stdout).expect("third workspace-files page");
    third_server.join().expect("third workspace-files backend");

    let pages = [&first, &second, &third];
    assert_eq!(
        pages.map(|page| page["result"]["returnedCount"].as_u64()),
        [Some(200), Some(200), Some(100)]
    );
    assert_eq!(
        first["result"]["nextPageToken"],
        "550e8400-e29b-41d4-a716-446655440001"
    );
    assert_eq!(
        second["result"]["nextPageToken"],
        "550e8400-e29b-41d4-a716-446655440002"
    );
    assert!(third["result"].get("nextPageToken").is_none(), "{third:#}");
    for page in pages {
        assert_eq!(page["result"]["cardinality"]["type"], "EXACT", "{page:#}");
        assert_eq!(page["result"]["cardinality"]["totalCount"], 500, "{page:#}");
        assert_eq!(
            page["result"]["returnedCount"].as_u64(),
            page["result"]["files"]
                .as_array()
                .map(|files| files.len() as u64),
            "{page:#}"
        );
    }
    let relative_paths = pages
        .into_iter()
        .flat_map(|page| {
            page["result"]["files"]
                .as_array()
                .expect("workspace files")
                .iter()
                .map(|file| {
                    file["relativePath"]
                        .as_str()
                        .expect("relative path")
                        .to_string()
                })
        })
        .collect::<Vec<_>>();
    assert_eq!(relative_paths.len(), 500);
    assert!(relative_paths.windows(2).all(|pair| pair[0] < pair[1]));
    assert_eq!(
        relative_paths.first().map(String::as_str),
        Some("src/main/kotlin/sample/Source0000.kt")
    );
    assert_eq!(
        relative_paths.last().map(String::as_str),
        Some("src/main/kotlin/sample/Source0499.kt")
    );
}

#[test]
fn malformed_issued_continuation_token_fails_closed() {
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
    let _index = create_workspace_index(&home, &workspace, "malformed-token", 500);
    let server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("malformed-token.sock"),
        None,
        Some("not-a-canonical-uuid-v4"),
    );

    let output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files JSON error");
    assert_eq!(
        stdout["error"]["code"], "AGENT_RESULT_INVALID",
        "{stdout:#}"
    );

    let requests = server.join().expect("malformed-token backend");
    assert!(
        requests.iter().any(|request| {
            request["method"] == "raw/workspace-files-continuation"
                && request["params"]["action"] == "ISSUE"
        }),
        "{requests:#?}"
    );
}

#[test]
fn high_cardinality_default_compact_page_stays_within_agent_budget() {
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
    let _index = create_workspace_index(&home, &workspace, "compact-budget", 500);
    let server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("compact-budget.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440003"),
    );

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
        ])
        .output()
        .expect("compact workspace-files page");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("compact budget backend");
    let raw = String::from_utf8(output.stdout).expect("compact UTF-8");
    let stdout: serde_json::Value =
        toon_format::decode_default(&raw).expect("compact default TOON");
    assert_eq!(stdout["result"]["returnedCount"], 20);
    assert_eq!(stdout["result"]["files"].as_array().map(Vec::len), Some(1));
    let group = &stdout["result"]["files"][0];
    assert_eq!(group["backendModules"], serde_json::json!(["fixture.main"]));
    assert_eq!(
        group["indexedGradleProjects"],
        serde_json::json!([{"buildRoot": ".", "projectPath": ":app"}])
    );
    assert_eq!(
        group["sourceSets"],
        serde_json::json!({
            "type": "PROVEN",
            "sourceSets": [{
                "buildRoot": ".",
                "projectPath": ":app",
                "sourceSetName": "main"
            }]
        })
    );
    assert_eq!(group["kind"], "KOTLIN_SOURCE");
    assert_eq!(
        group["package"],
        serde_json::json!({"type": "PROVEN_NAMED", "name": "sample"})
    );
    assert_eq!(group["sourceIndex"], "INDEXED");
    assert_eq!(group["drift"], "NONE");
    assert_eq!(group["dirty"], "UNKNOWN");
    assert_eq!(group["paths"].as_array().map(Vec::len), Some(20));
    assert_eq!(
        group["paths"][0],
        serde_json::json!({
            "filePath": workspace.join("src/main/kotlin/sample/Source0000.kt").display().to_string(),
            "relativePath": "src/main/kotlin/sample/Source0000.kt"
        }),
        "compact projection must retain routing and coherence evidence: {stdout:#}"
    );
    let lines = raw.lines().count();
    let tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k tokenizer")
        .encode_with_special_tokens(&raw)
        .len();
    assert!(
        lines <= 120,
        "compact page used {lines} lines and {tokens} cl100k tokens; budgets are 120/1500"
    );
    assert!(
        tokens <= 1_500,
        "compact page used {tokens} cl100k tokens; budget is 1500"
    );
}

#[test]
fn compact_groups_only_consecutive_globally_sorted_identical_evidence() {
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
    let index = create_workspace_index(&home, &workspace, "group-boundaries", 500);
    index
        .connection()
        .execute_batch(
            r#"
            DELETE FROM file_gradle_source_sets WHERE filename = 'Source0002.kt';
            DELETE FROM file_gradle_projects WHERE filename = 'Source0002.kt';
            INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path)
                VALUES (1, 'Source0002.kt', '.', ':other');
            INSERT INTO file_gradle_source_sets(
                prefix_id, filename, build_root, project_path, source_set_name
            ) VALUES (1, 'Source0002.kt', '.', ':other', 'main');
            "#,
        )
        .expect("distinct typed evidence");
    let server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("group-boundaries.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440004"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
        ])
        .output()
        .expect("compact workspace-files grouping");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("grouping backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("compact grouping JSON");
    let groups = stdout["result"]["files"]
        .as_array()
        .expect("compact groups");
    assert_eq!(groups.len(), 3, "{stdout:#}");
    assert_eq!(
        groups
            .iter()
            .map(|group| group["paths"].as_array().expect("group paths").len())
            .collect::<Vec<_>>(),
        vec![2, 1, 17]
    );
    let evidence = groups
        .iter()
        .map(|group| {
            let mut evidence = group.as_object().expect("compact group").clone();
            evidence.remove("paths");
            evidence
        })
        .collect::<Vec<_>>();
    assert_eq!(evidence[0], evidence[2]);
    assert_ne!(evidence[0], evidence[1]);
    let relative_paths = groups
        .iter()
        .flat_map(|group| group["paths"].as_array().expect("group paths"))
        .map(|path| path["relativePath"].as_str().expect("relativePath"))
        .collect::<Vec<_>>();
    assert_eq!(relative_paths.len(), 20);
    assert!(relative_paths.windows(2).all(|pair| pair[0] < pair[1]));
    assert_eq!(relative_paths[2], "src/main/kotlin/sample/Source0002.kt");
}

#[test]
fn public_continuations_reject_query_mismatch_and_stale_evidence_without_restarting() {
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
    let _index = create_workspace_index(&home, &workspace, "continuation-failures", 500);

    let seed_backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("continuation-seed.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440010"),
    );
    let seed_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(
        seed_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&seed_output.stdout),
        String::from_utf8_lossy(&seed_output.stderr),
    );
    let seed_requests = seed_backend.join().expect("continuation seed backend");
    let seed_state = workspace_files_issue_state(&seed_requests);

    for (name, mut consumed_state, expected_code) in [
        (
            "query-mismatch",
            seed_state.clone(),
            "INVALID_WORKSPACE_FILES_PAGE_TOKEN",
        ),
        (
            "stale-evidence",
            seed_state.clone(),
            "STALE_WORKSPACE_FILES_PAGE",
        ),
    ] {
        if name == "query-mismatch" {
            consumed_state["identity"]["normalizedQuery"] =
                serde_json::Value::String("{\"filters\":{\"kind\":\"script\"}}".to_string());
        } else {
            consumed_state["compositionStampDigest"] =
                serde_json::Value::String("stale-composition".to_string());
        }
        let backend = spawn_paged_workspace_files_backend(
            &home,
            &config_home,
            &workspace,
            &temp.path().join(format!("{name}.sock")),
            Some(consumed_state),
            None,
        );
        let output = run_workspace_files_page(
            &home,
            &config_home,
            &workspace,
            Some("550e8400-e29b-41d4-a716-446655440010"),
        );
        assert_eq!(
            output.status.code(),
            Some(1),
            "case={name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("continuation failure JSON");
        assert_eq!(
            stdout["error"]["code"], expected_code,
            "case={name} {stdout:#}"
        );
        assert!(stdout.get("result").is_none(), "case={name} {stdout:#}");
        let requests = backend.join().expect("continuation failure backend");
        assert_eq!(requests[2]["params"]["action"], "CONSUME");
        assert_eq!(requests[3]["method"], "raw/workspace-files");
        assert!(
            requests.iter().all(|request| {
                request["method"] != "raw/workspace-files-continuation"
                    || request["params"]["action"] != "ISSUE"
            }),
            "invalid or stale continuation must never restart or issue a replacement: {requests:#?}"
        );
    }
}

#[test]
fn consumed_continuation_reports_stale_before_disappeared_authorities() {
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
    let index = create_workspace_index(&home, &workspace, "disappeared-authorities", 500);

    let seed_backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("disappeared-authorities-seed.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440011"),
    );
    let seed_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(seed_output.status.success());
    let seed_state = workspace_files_issue_state(
        &seed_backend
            .join()
            .expect("disappeared-authorities seed backend"),
    );

    std::fs::remove_file(index.database_path()).expect("remove source-index authority");
    let mut responses = workspace_files_session_responses(&workspace);
    responses.push((
        "raw/workspace-files-continuation",
        serde_json::json!({"type": "CONSUMED", "state": seed_state}),
    ));
    responses.extend([
        ("raw/workspace-files", serde_json::json!({})),
        ("raw/workspace-files", serde_json::json!({})),
    ]);
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("disappeared-authorities.sock"),
        responses,
    );

    let output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440011"),
    );
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("stale continuation JSON");
    assert_eq!(
        stdout["error"]["code"], "STALE_WORKSPACE_FILES_PAGE",
        "{stdout:#}"
    );
    backend.join().expect("disappeared-authorities backend");
}

#[test]
fn consumed_continuation_rejects_cross_lane_instability() {
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
    let _index = create_workspace_index(&home, &workspace, "unstable-continuation", 500);

    let seed_backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unstable-continuation-seed.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440012"),
    );
    let seed_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(seed_output.status.success());
    let seed_state = workspace_files_issue_state(
        &seed_backend
            .join()
            .expect("unstable-continuation seed backend"),
    );

    let mut responses = workspace_files_session_responses(&workspace);
    responses.push((
        "raw/workspace-files-continuation",
        serde_json::json!({"type": "CONSUMED", "state": seed_state}),
    ));
    append_paged_workspace_files_collection(&mut responses, &workspace, "snapshot-moved");
    append_paged_workspace_files_collection(&mut responses, &workspace, "snapshot-moved");
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unstable-continuation.sock"),
        responses,
    );

    let output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440012"),
    );
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("unstable continuation JSON");
    assert_eq!(
        stdout["error"]["code"], "STALE_WORKSPACE_FILES_PAGE",
        "{stdout:#}"
    );
    backend.join().expect("unstable-continuation backend");
}

#[test]
fn stable_partial_inventory_can_continue_known_matches() {
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
    let index = create_workspace_index(&home, &workspace, "stable-partial", 500);
    index.seed_progress("app", "INDEXING", 499, 500);
    let backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("stable-partial.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440011"),
    );

    let output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("stable partial JSON");
    assert_eq!(stdout["result"]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(stdout["result"]["returnedCount"], 200);
    assert_eq!(
        stdout["result"]["nextPageToken"],
        "550e8400-e29b-41d4-a716-446655440011"
    );
    assert!(
        stdout["result"]["limitations"]
            .as_array()
            .expect("limitations")
            .iter()
            .any(|limitation| limitation["code"] == "SOURCE_INDEX_PROGRESS_INCOMPLETE"),
        "{stdout:#}"
    );
    let requests = backend.join().expect("stable partial backend");
    assert_eq!(
        requests.last().expect("continuation issue")["params"]["action"],
        "ISSUE"
    );
}

#[test]
fn complete_index_evidence_publishes_backend_only_source_as_not_indexed() {
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
    let index = create_workspace_index(&home, &workspace, "not-indexed", 0);
    index.seed_progress("app", "COMPLETE", 0, 0);
    let backend_only = workspace.join("src/main/kotlin/sample/BackendOnly.kt");
    std::fs::create_dir_all(backend_only.parent().expect("source parent")).expect("source parent");
    std::fs::write(&backend_only, "package sample\nclass BackendOnly\n")
        .expect("backend-only source");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("not-indexed.sock"),
        &backend_only,
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
            "--fields",
            "path,index,drift",
        ])
        .output()
        .expect("not-indexed discovery");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("not-indexed backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("not-indexed JSON");
    assert_eq!(
        stdout["result"]["files"],
        serde_json::json!([{
            "filePath": backend_only.display().to_string(),
            "relativePath": "src/main/kotlin/sample/BackendOnly.kt",
            "sourceIndex": "NOT_INDEXED",
            "drift": "FILESYSTEM_ONLY"
        }]),
        "NOT_INDEXED requires complete source-index evidence: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1})
    );

    let count_backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("not-indexed-count.sock"),
        &backend_only,
    );
    let count_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
            "--count",
        ])
        .output()
        .expect("not-indexed count");
    assert!(
        count_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&count_output.stdout),
        String::from_utf8_lossy(&count_output.stderr),
    );
    count_backend.join().expect("not-indexed count backend");
    let count_stdout: serde_json::Value =
        serde_json::from_slice(&count_output.stdout).expect("not-indexed count JSON");
    assert_eq!(
        grouped_cardinality(&count_stdout, "index", "NOT_INDEXED")["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1}),
        "count projection must agree with the detailed index state: {count_stdout:#}"
    );
}

#[test]
fn incomplete_index_evidence_counts_backend_only_source_as_unknown() {
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
    let index = create_workspace_index(&home, &workspace, "unknown-index", 0);
    index.seed_progress("app", "INDEXING", 0, 1);
    let backend_only = workspace.join("src/main/kotlin/sample/BackendOnly.kt");
    std::fs::create_dir_all(backend_only.parent().expect("source parent")).expect("source parent");
    std::fs::write(&backend_only, "package sample\nclass BackendOnly\n")
        .expect("backend-only source");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unknown-index-count.sock"),
        &backend_only,
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
            "--count",
        ])
        .output()
        .expect("unknown index count");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("unknown index count backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("unknown index count JSON");
    assert_eq!(
        grouped_cardinality(&stdout, "index", "UNKNOWN")["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 1}),
        "incomplete source-index evidence must remain UNKNOWN: {stdout:#}"
    );
}

#[test]
fn gradle_module_filter_is_partial_when_candidate_ownership_is_unknown() {
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
    let index = create_workspace_index(&home, &workspace, "unknown-gradle-owner", 0);
    index.seed_progress("app", "INDEXING", 0, 1);
    let backend_only = workspace.join("src/main/kotlin/sample/BackendOnly.kt");
    std::fs::create_dir_all(backend_only.parent().expect("source parent")).expect("source parent");
    std::fs::write(&backend_only, "package sample\nclass BackendOnly\n")
        .expect("backend-only source");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unknown-gradle-owner.sock"),
        &backend_only,
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
            "--module",
            "gradle:.#:app",
        ])
        .output()
        .expect("unknown Gradle owner filter");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("unknown Gradle owner backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("unknown Gradle owner JSON");
    assert_eq!(
        stdout["result"]["coverage"]["filterEvidence"], "PARTIAL",
        "unknown indexed Gradle ownership cannot prove a negative match: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 0}),
        "unknown indexed Gradle ownership cannot produce exact zero: {stdout:#}"
    );
}

#[test]
fn backend_module_filter_is_partial_when_backend_page_is_incomplete() {
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
    let _index = create_workspace_index(&home, &workspace, "partial-backend-owner", 1);
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let source_root = workspace.join("src/main/kotlin");
    let module = |files: serde_json::Value, returned_file_count: usize| {
        serde_json::json!({
            "snapshotToken": "snapshot-partial-backend-owner",
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
            "schemaVersion": 3
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-partial-backend-owner",
        "modules": [],
        "schemaVersion": 3
    });
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("partial-backend-owner.sock"),
        vec![
            ("raw/workspace-files", module(serde_json::json!([]), 0)),
            (
                "raw/workspace-files",
                module(serde_json::json!([source.display().to_string()]), 1),
            ),
            ("raw/workspace-files", validation.clone()),
            ("raw/workspace-files", validation),
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
            "--backend",
            "idea",
            "--kind",
            "source",
            "--module",
            "backend:fixture.main",
        ])
        .output()
        .expect("partial backend owner filter");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("partial backend owner backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("partial backend owner JSON");
    assert_eq!(
        stdout["result"]["coverage"]["filterEvidence"], "PARTIAL",
        "partial backend coverage cannot prove a negative module match: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 0}),
        "partial backend coverage cannot produce exact zero: {stdout:#}"
    );
}

#[test]
fn gradle_module_filter_is_exact_when_complete_index_proves_candidates_have_no_owner() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin/sample")).expect("workspace sources");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle settings");
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    std::fs::write(&source, "package sample\nclass Source0000\n").expect("Kotlin source");
    let script = workspace.join("src/main/kotlin/sample/Script.kts");
    std::fs::write(&script, "println(\"fixture\")\n").expect("Kotlin script");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = create_workspace_index(&home, &workspace, "proven-no-gradle-owner", 0);
    index.seed_progress("app", "COMPLETE", 0, 0);
    let backend = spawn_small_mixed_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("proven-no-gradle-owner.sock"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--module",
            "gradle:.#:app",
        ])
        .output()
        .expect("proven absent Gradle owner filter");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("proven absent Gradle owner backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("proven absent Gradle owner JSON");
    assert_eq!(
        stdout["result"]["coverage"]["filterEvidence"], "COMPLETE",
        "complete index and script non-applicability prove both negative matches: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 0}),
        "proven negative Gradle ownership must retain exact zero: {stdout:#}"
    );
}

#[test]
fn structured_filters_are_conjunctive_and_never_match_legacy_labels() {
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
    let index = create_workspace_index(&home, &workspace, "structured-filters", 0);
    seed_structured_filter_evidence(&index);
    let server = spawn_structured_filter_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("structured-filters.sock"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--module",
            "gradle:.#:app",
            "--source-set",
            "integrationTest",
            "--package",
            "named:sample.target",
            "--fields",
            "path,module,source-set,package,index",
        ])
        .output()
        .expect("structured filter query");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("structured filter backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("structured filter JSON");
    assert_eq!(
        stdout["result"]["files"],
        serde_json::json!([{
            "filePath": workspace.join("src/main/kotlin/sample/Good.kt").display().to_string(),
            "relativePath": "src/main/kotlin/sample/Good.kt",
            "backendModules": ["fixture.main"],
            "indexedGradleProjects": [{"buildRoot": ".", "projectPath": ":app"}],
            "sourceSets": {
                "type": "PROVEN",
                "sourceSets": [{
                    "buildRoot": ".",
                    "projectPath": ":app",
                    "sourceSetName": "integrationTest"
                }]
            },
            "package": {"type": "PROVEN_NAMED", "name": "sample.target"},
            "sourceIndex": "INDEXED"
        }]),
        "each non-result fixture matches only a strict subset or legacy labels: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 1}),
        "{stdout:#}"
    );
    assert_eq!(stdout["result"]["coverage"]["filterEvidence"], "PARTIAL");
}

#[test]
fn discovered_file_path_composes_with_diagnostics_and_exact_symbol_lookup() {
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
    let _index = create_workspace_index(&home, &workspace, "direct-composition", 1);
    let owned_file = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let unowned_file = workspace.join("src/main/kotlin/sample/Unowned.kt");
    std::fs::write(&unowned_file, "package sample\nclass Unowned\n").expect("unowned source");

    let discovery_backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("composition-discovery.sock"),
        &owned_file,
    );
    let discovery_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--kind",
            "source",
            "--fields",
            "path",
        ])
        .output()
        .expect("workspace discovery");
    assert!(
        discovery_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&discovery_output.stdout),
        String::from_utf8_lossy(&discovery_output.stderr),
    );
    discovery_backend.join().expect("discovery backend");
    let discovery: serde_json::Value =
        serde_json::from_slice(&discovery_output.stdout).expect("discovery JSON");
    assert_eq!(
        discovery["result"]["files"].as_array().map(Vec::len),
        Some(1),
        "an existing but unowned .kt file must not enter semantic discovery: {discovery:#}"
    );
    let discovered_file_path = discovery["result"]["files"][0]["filePath"]
        .as_str()
        .expect("discovered filePath")
        .to_string();
    assert_eq!(discovered_file_path, owned_file.display().to_string());
    assert_ne!(discovered_file_path, unowned_file.display().to_string());

    let diagnostics_backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("composition-diagnostics.sock"),
        vec![
            (
                "raw/workspace-refresh",
                serde_json::json!({
                    "refreshedFiles": [discovered_file_path],
                    "removedFiles": [],
                    "fullRefresh": false,
                    "fileStatuses": [{
                        "filePath": discovered_file_path,
                        "fileSystemDiscovery": "DISCOVERED",
                        "sourceModuleOwnership": "OWNED",
                        "indexAdmission": "ADMITTED",
                        "analysisAvailability": "AVAILABLE",
                        "analysisStatus": {"filePath": discovered_file_path, "state": "ANALYZED"}
                    }],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "removedFileCount": 0,
                    "attemptCount": 1,
                    "elapsedMillis": 0,
                    "schemaVersion": 3
                }),
            ),
            (
                "raw/diagnostics",
                serde_json::json!({
                    "diagnostics": [],
                    "fileStatuses": [{"filePath": discovered_file_path, "state": "ANALYZED"}],
                    "fileHashes": [{
                        "filePath": discovered_file_path,
                        "hash": "a".repeat(64)
                    }],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
                    "cardinality": {"type": "EXACT", "totalCount": 0}
                }),
            ),
        ],
    );
    let diagnostics_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "diagnostics",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--file-path",
            &discovered_file_path,
        ])
        .output()
        .expect("composed diagnostics");
    assert!(
        diagnostics_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&diagnostics_output.stdout),
        String::from_utf8_lossy(&diagnostics_output.stderr),
    );
    let diagnostics_requests = diagnostics_backend.join().expect("diagnostics backend");
    assert_eq!(
        diagnostics_requests
            .iter()
            .find(|request| request["method"] == "raw/diagnostics")
            .expect("diagnostics request")["params"]["filePaths"],
        serde_json::json!([discovered_file_path])
    );

    let symbol_backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("composition-symbol.sock"),
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "sample.Source0000",
                    "kind": "CLASS",
                    "location": {
                        "filePath": discovered_file_path,
                        "startOffset": 15,
                        "endOffset": 25,
                        "startLine": 2,
                        "startColumn": 7,
                        "preview": "Source0000"
                    }
                }
            }),
        )],
    );
    let symbol_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Source0000",
            "--file-hint",
            &discovered_file_path,
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("composed exact symbol lookup");
    assert!(
        symbol_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&symbol_output.stdout),
        String::from_utf8_lossy(&symbol_output.stderr),
    );
    let symbol: serde_json::Value =
        serde_json::from_slice(&symbol_output.stdout).expect("symbol JSON");
    assert_eq!(symbol["result"]["mode"], "exact", "{symbol:#}");
    assert_eq!(symbol["result"]["outcome"], "RESOLVED", "{symbol:#}");
    let symbol_requests = symbol_backend.join().expect("symbol backend");
    assert_eq!(
        symbol_requests[2]["params"]["fileHint"], discovered_file_path,
        "exact lookup must consume the discovery filePath verbatim"
    );
}

#[test]
fn mixed_count_keeps_the_script_group_exact_when_source_inventory_is_partial() {
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
    let index = create_workspace_index(&home, &workspace, "mixed-count", 1);
    index.seed_progress("app", "INDEXING", 1, 2);
    let script = workspace.join("src/main/kotlin/sample/Script.kts");
    std::fs::write(&script, "println(\"fixture\")\n").expect("Kotlin script");
    let server = spawn_small_mixed_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("mixed-count.sock"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "idea",
            "--count",
        ])
        .output()
        .expect("mixed workspace-file count");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("mixed count backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("mixed count JSON");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_WORKSPACE_FILES_COUNT");
    assert_eq!(stdout["result"]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(stdout["result"]["cardinality"]["knownMinimumCount"], 2);
    assert_eq!(stdout["result"]["returnedCount"], 0);
    assert!(stdout["result"].get("files").is_none(), "{stdout:#}");
    assert_eq!(
        grouped_cardinality(&stdout, "kind", "KOTLIN_SOURCE")["cardinality"]["type"],
        "KNOWN_MINIMUM"
    );
    assert_eq!(
        grouped_cardinality(&stdout, "kind", "KOTLIN_SCRIPT")["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1})
    );
    assert_eq!(
        grouped_cardinality(&stdout, "index", "NOT_APPLICABLE")["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1})
    );
}

#[test]
fn selected_verbose_and_explain_views_add_only_their_typed_evidence() {
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
    let index = create_workspace_index(&home, &workspace, "projection-views", 1);
    index.seed_progress("app", "INDEXING", 1, 2);
    std::fs::write(
        workspace.join("src/main/kotlin/sample/Script.kts"),
        "println(\"fixture\")\n",
    )
    .expect("Kotlin script");

    for (view_name, view_args) in [
        ("fields", vec!["--fields", "path,kind"]),
        ("verbose", vec!["--verbose"]),
        ("explain", vec!["--explain"]),
    ] {
        let server = spawn_small_mixed_workspace_files_backend(
            &home,
            &config_home,
            &workspace,
            &temp.path().join(format!("{view_name}.sock")),
        );
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "workspace-files",
                "--workspace-root",
                workspace.to_str().expect("UTF-8 workspace"),
                "--backend",
                "idea",
            ])
            .args(view_args)
            .output()
            .expect("workspace-file projection");
        assert!(
            output.status.success(),
            "view={view_name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        server.join().expect("projection backend");
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("workspace-file projection JSON");
        assert_eq!(
            stdout["result"]["returnedCount"].as_u64(),
            stdout["result"]["files"]
                .as_array()
                .map(|files| files.len() as u64),
            "{stdout:#}"
        );
        match view_name {
            "fields" => {
                assert_eq!(
                    stdout["result"]["type"],
                    "KAST_AGENT_WORKSPACE_FILES_SELECTION"
                );
                for file in stdout["result"]["files"]
                    .as_array()
                    .expect("selected files")
                {
                    let keys = file
                        .as_object()
                        .expect("selected file")
                        .keys()
                        .cloned()
                        .collect::<Vec<_>>();
                    assert_eq!(keys, vec!["filePath", "relativePath", "kind"]);
                }
                assert!(stdout["result"].get("backendPageCoverage").is_none());
            }
            "verbose" => {
                assert_eq!(stdout["result"]["view"], "VERBOSE");
                assert_eq!(
                    stdout["result"]["backendPageCoverage"]["workspace"],
                    "COMPLETE"
                );
                assert_eq!(
                    stdout["result"]["backendPageCoverage"]["modules"][0],
                    serde_json::json!({
                        "moduleName": "fixture.main",
                        "declaredFileCount": 2,
                        "coverage": "COMPLETE"
                    })
                );
                assert!(stdout["result"].get("classificationEvidence").is_none());
                assert!(stdout["result"].get("normalizedQuery").is_none());
            }
            "explain" => {
                assert_eq!(stdout["result"]["view"], "EXPLAIN");
                assert!(stdout["result"]["normalizedQuery"].is_string());
                assert!(stdout["result"]["compositionDigest"].is_string());
                assert_eq!(
                    stdout["result"]["classificationEvidence"]
                        .as_array()
                        .map(Vec::len),
                    Some(2)
                );
                assert_eq!(
                    stdout["result"]["classificationEvidence"][1]["package"],
                    "PROVEN_NAMED"
                );
            }
            _ => unreachable!("closed projection fixture"),
        }
    }
}

#[test]
fn workspace_files_is_public() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = kast(&home, &config_home)
        .args(["agent", "workspace-files", "--help"])
        .output()
        .expect("workspace-files help");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let help = String::from_utf8_lossy(&output.stdout);
    for example in [
        "kast agent workspace-files --workspace-root /workspace --module backend:kast.analysis-api.main --package root",
        "kast agent workspace-files --workspace-root /workspace --module gradle:included/tools#:app --package named:com.example",
        "kast agent workspace-files --workspace-root /workspace --kind script --fields path,module",
    ] {
        assert!(
            help.contains(example),
            "missing example `{example}`: {help}"
        );
    }
    for selector_grammar in [
        "backend:<name>",
        "gradle:<root>#<path>",
        "root",
        "named:<fq-name>",
    ] {
        assert!(
            help.contains(selector_grammar),
            "missing selector grammar `{selector_grammar}`: {help}"
        );
    }
}

#[test]
fn documented_workspace_file_arguments_reach_the_typed_boundary() {
    let workspace = std::fs::canonicalize(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .expect("workspace parent"),
    )
    .expect("canonical workspace");
    let workspace = workspace.to_str().expect("UTF-8 workspace");
    let stdout = assert_typed_boundary(&[
        "--workspace-root",
        workspace,
        "--backend",
        "idea",
        "--module",
        "gradle:included/tools#:app",
        "--source-set",
        "integrationTest",
        "--kind",
        "source",
        "--package",
        "named:例子.`when`",
        "--dirty",
        "dirty",
        "--drift",
        "not-applicable",
        "--path-prefix",
        "src/main",
        "--glob",
        "**/*.kt",
        "--limit",
        "200",
        "--page-token",
        "123e4567-e89b-42d3-a456-426614174000",
        "--fields",
        "path,evidence",
    ]);
    let query = &stdout["error"]["details"]["admittedQuery"];
    assert_eq!(query["canonicalWorkspaceRoot"], workspace, "{stdout:#}");
    assert_eq!(query["backend"], "idea", "{stdout:#}");
    assert_eq!(
        query["filters"]["package"], "named:例子.`when`",
        "{stdout:#}"
    );
    assert_eq!(query["filters"]["packageName"], "例子.when", "{stdout:#}");
    assert_eq!(query["filters"]["kind"], "source", "{stdout:#}");
    assert_eq!(query["kindDomain"], "source-only", "{stdout:#}");
    assert_eq!(query["view"], "fields", "{stdout:#}");
    assert_eq!(
        query["orderedFields"],
        serde_json::json!(["path", "evidence"])
    );
    assert_eq!(query["limit"], 200, "{stdout:#}");
    assert!(query.get("pageHandle").is_none(), "{stdout:#}");
    assert_eq!(
        stdout["error"]["details"]["pageHandle"]["token"], "123e4567-e89b-42d3-a456-426614174000",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["error"]["details"]["nextAction"]["arguments"],
        serde_json::json!([
            "agent",
            "verify",
            "--workspace-root",
            workspace,
            "--backend",
            "idea"
        ]),
        "{stdout:#}"
    );
}

#[test]
fn workspace_root_must_be_canonicalized_and_admitted() {
    let fixture = tempfile::tempdir().expect("workspace fixture");
    let unresolved = fixture.path().join("missing");
    let output = run_workspace_files(&[
        "--workspace-root",
        unresolved.to_str().expect("UTF-8 unresolved root"),
    ]);
    assert_eq!(output.status.code(), Some(1));
    let document: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace admission JSON");
    assert_eq!(document["error"]["code"], "AGENT_WORKSPACE_INVALID");
    assert!(
        document["error"]["details"].get("admittedQuery").is_none(),
        "{document:#}"
    );
}

#[test]
fn module_selectors_are_closed_and_build_qualified() {
    for accepted in [
        "backend:kast.analysis-api.main",
        "gradle:.#:app",
        "gradle:included/tools#:app",
    ] {
        let stdout = assert_typed_boundary(&["--module", accepted]);
        assert_eq!(
            stdout["error"]["details"]["admittedQuery"]["filters"]["module"], accepted,
            "{stdout:#}"
        );
    }

    for rejected in [
        "analysis-api",
        "backend:",
        "gradle:/absolute#:app",
        "gradle:../outside#:app",
        "gradle:included/tools#app",
        "gradle:C:/workspace#:app",
        "gradle:C:workspace#:app",
        "gradle:C:\\workspace#:app",
        "gradle://server/share#:app",
        "gradle:\\\\server\\share#:app",
    ] {
        assert_usage_error(&["--module", rejected]);
    }
}

#[test]
fn package_selectors_normalize_kotlin_semantic_names() {
    for (accepted, canonical) in [
        ("root", "root"),
        ("named:com.example", "named:com.example"),
        ("named:com.example.`when`", "named:com.example.`when`"),
        ("named:com.`non-identifier`", "named:com.`non-identifier`"),
        ("named:例子.工具", "named:例子.工具"),
    ] {
        let stdout = assert_typed_boundary(&["--package", accepted]);
        assert_eq!(
            stdout["error"]["details"]["admittedQuery"]["filters"]["package"], canonical,
            "{stdout:#}"
        );
        assert_typed_boundary(&["--package", canonical]);
    }

    for rejected in [
        "com.example",
        "named:",
        "named:com..example",
        "named:com.`unterminated",
        "named:com.non-identifier",
        "named:com.when",
        "named:com.`bad:name`",
        "named:com.`bad[name]`",
    ] {
        assert_usage_error(&["--package", rejected]);
    }
}

#[test]
fn plain_package_segments_match_the_kotlin_l_and_nd_producer_boundary() {
    for accepted in [
        "named:ǅelta.ʰello",
        "named:例子.工具",
        "named:क.a١",
        "named:_private.a9",
    ] {
        assert_typed_boundary(&["--package", accepted]);
    }

    for rejected in [
        "named:ͅmark",
        "named:a.ͅmark",
        "named:Ⅻvalue",
        "named:a.Ⅻvalue",
        "named:²value",
        "named:a.²value",
    ] {
        assert_usage_error(&["--package", rejected]);
    }
}

#[test]
fn path_filters_are_normalized_and_workspace_relative() {
    let stdout = assert_typed_boundary(&["--path-prefix", "./src/main", "--glob", "src/**/*.kt"]);
    assert_eq!(
        stdout["error"]["details"]["admittedQuery"]["filters"]["pathPrefix"], "src/main",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["error"]["details"]["admittedQuery"]["filters"]["glob"], "src/**/*.kt",
        "{stdout:#}"
    );

    for rejected in [
        "/absolute",
        "../outside",
        "src/../outside",
        "C:/workspace/src",
        "C:workspace/src",
        "C:\\workspace\\src",
        "//server/share/src",
        "\\\\server\\share\\src",
        "",
    ] {
        assert_usage_error(&["--path-prefix", rejected]);
    }
    for rejected in [
        "regex:.*\\.kt",
        "/**/*.kt",
        "../**/*.kt",
        "C:/workspace/**/*.kt",
        "C:workspace/**/*.kt",
        "C:\\workspace\\**\\*.kt",
        "//server/share/**/*.kt",
        "\\\\server\\share\\**\\*.kt",
        "",
    ] {
        assert_usage_error(&["--glob", rejected]);
    }
}

#[test]
fn workspace_globs_have_explicit_resource_bounds() {
    let max_bytes = "a".repeat(512);
    assert_typed_boundary(&["--glob", &max_bytes]);
    let over_max_bytes = "a".repeat(513);
    assert_usage_error(&["--glob", &over_max_bytes]);

    let max_segments = vec!["a"; 32].join("/");
    assert_typed_boundary(&["--glob", &max_segments]);
    let over_max_segments = vec!["a"; 33].join("/");
    assert_usage_error(&["--glob", &over_max_segments]);

    let max_metacharacters = "?".repeat(64);
    assert_typed_boundary(&["--glob", &max_metacharacters]);
    let over_max_metacharacters = "?".repeat(65);
    assert_usage_error(&["--glob", &over_max_metacharacters]);
}

#[test]
fn workspace_file_limit_is_typed_and_bounded() {
    let default = assert_typed_boundary(&[]);
    assert_eq!(
        default["error"]["details"]["admittedQuery"]["limit"], 20,
        "{default:#}"
    );
    for accepted in ["1", "200"] {
        assert_typed_boundary(&["--limit", accepted]);
    }
    for rejected in ["0", "201", "not-a-number"] {
        assert_usage_error(&["--limit", rejected]);
    }
}

#[test]
fn public_page_tokens_are_canonical_and_file_view_bound() {
    let canonical = "123e4567-e89b-42d3-a456-426614174000";
    let stdout = assert_typed_boundary(&["--page-token", canonical]);
    assert_eq!(
        stdout["error"]["details"]["pageHandle"]["token"], canonical,
        "{stdout:#}"
    );
    assert!(
        stdout["error"]["details"]["admittedQuery"]
            .get("pageHandle")
            .is_none(),
        "{stdout:#}"
    );

    for rejected in [
        "",
        "123e4567e89b42d3a456426614174000",
        "123E4567-E89B-42D3-A456-426614174000",
        "123e4567-e89b-12d3-a456-426614174000",
        "00000000-0000-0000-0000-000000000000",
    ] {
        assert_usage_error(&["--page-token", rejected]);
    }
    assert_usage_error(&["--page-token", canonical, "--count"]);
}

#[test]
fn workspace_file_result_views_are_family_specific_and_exclusive() {
    for (accepted, view, fields) in [
        (vec!["--verbose"], "verbose", serde_json::json!([])),
        (vec!["--explain"], "explain", serde_json::json!([])),
        (vec!["--count"], "count", serde_json::json!([])),
        (
            vec![
                "--fields",
                "path,module,source-set,kind,package,index,drift,dirty,evidence",
            ],
            "fields",
            serde_json::json!([
                "path",
                "module",
                "source-set",
                "kind",
                "package",
                "index",
                "drift",
                "dirty",
                "evidence"
            ]),
        ),
    ] {
        let stdout = assert_typed_boundary(&accepted);
        let query = &stdout["error"]["details"]["admittedQuery"];
        assert_eq!(query["view"], view, "{stdout:#}");
        assert_eq!(query["orderedFields"], fields, "{stdout:#}");
    }

    for rejected in [
        vec!["--verbose", "--explain"],
        vec!["--fields", "path", "--count"],
        vec!["--fields", "identity"],
    ] {
        assert_usage_error(&rejected);
    }
}

#[test]
fn source_set_names_are_typed_without_directory_assumptions() {
    let stdout = assert_typed_boundary(&["--source-set", "integrationTest"]);
    assert_eq!(
        stdout["error"]["details"]["admittedQuery"]["filters"]["sourceSet"], "integrationTest",
        "{stdout:#}"
    );
    for rejected in ["", "src/integrationTest", ":integrationTest"] {
        assert_usage_error(&["--source-set", rejected]);
    }
}

#[test]
fn kind_filter_derives_a_closed_collection_domain() {
    for (arguments, expected) in [
        (vec![], "mixed"),
        (vec!["--kind", "source"], "source-only"),
        (vec!["--kind", "script"], "script-only"),
    ] {
        let stdout = assert_typed_boundary(&arguments);
        assert_eq!(
            stdout["error"]["details"]["admittedQuery"]["kindDomain"], expected,
            "{stdout:#}"
        );
        let filters = stdout["error"]["details"]["admittedQuery"]["filters"]
            .as_object()
            .expect("typed filters");
        if arguments.is_empty() {
            assert!(!filters.contains_key("kind"), "{stdout:#}");
        }
    }
}

#[test]
fn unavailable_error_has_structured_next_action_and_toon_stdout_discipline() {
    let workspace = tempfile::tempdir().expect("workspace");
    let workspace = std::fs::canonicalize(workspace.path()).expect("canonical workspace");
    let workspace = workspace.to_str().expect("UTF-8 workspace");
    let output = run_workspace_files_with_output(
        "toon",
        &["--workspace-root", workspace, "--kind", "source"],
    );
    assert_eq!(output.status.code(), Some(1));
    assert!(
        output.stderr.is_empty(),
        "machine-readable failure must keep stderr empty: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let toon = std::str::from_utf8(&output.stdout).expect("TOON UTF-8");
    assert!(
        !output.stdout.ends_with(b"\n"),
        "TOON stdout must not have a trailing newline: {toon:?}"
    );
    let document: serde_json::Value =
        toon_format::decode_default(toon).expect("workspace-files TOON");
    assert_eq!(document["error"]["code"], "SEMANTIC_WORKSPACE_UNSUPPORTED");
    assert_eq!(
        document["error"]["details"]["semanticWorkspace"]["nextActions"],
        serde_json::json!([]),
        "{document:#}"
    );
    assert_eq!(
        document["error"]["details"]["nextAction"],
        serde_json::json!({
            "kind": "VERIFY_WORKSPACE",
            "command": "kast",
            "arguments": ["agent", "verify", "--workspace-root", workspace],
            "mutatesGlobalInstallAuthority": false
        }),
        "{document:#}"
    );
}
