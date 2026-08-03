#[test]
fn refresh_bootstraps_clean_pending_graph_files() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    assert!(
        Command::new("git")
            .args(["init", "--quiet"])
            .current_dir(&workspace)
            .status()
            .expect("git init")
            .success()
    );
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = WorkspaceIndexFixture::at_database_path(
        &workspace,
        &workspace_database_path_for_test(&workspace),
    );
    index.seed_high_cardinality_sources(1);
    index.seed_progress("app", "COMPLETE", 1, 1);
    index
        .connection()
        .execute(
            "DELETE FROM file_stage_outcomes WHERE stage = 'SEMANTIC_GRAPH'",
            [],
        )
        .expect("remove semantic graph outcome");
    index
        .connection()
        .execute_batch(
            "CREATE TABLE semantic_files(
                 id INTEGER PRIMARY KEY,
                 path TEXT NOT NULL UNIQUE,
                 package_name TEXT,
                 module_name TEXT,
                 content_hash TEXT,
                 refresh_status TEXT NOT NULL,
                 diagnostics_json TEXT NOT NULL
             );",
        )
        .expect("semantic graph table");
    assert!(
        Command::new("git")
            .args(["add", "settings.gradle.kts", "src"])
            .current_dir(&workspace)
            .status()
            .expect("git add")
            .success()
    );
    assert!(
        Command::new("git")
            .args([
                "-c",
                "user.name=Kast Test",
                "-c",
                "user.email=kast@example.invalid",
                "commit",
                "--quiet",
                "-m",
                "fixture",
            ])
            .current_dir(&workspace)
            .status()
            .expect("git commit")
            .success()
    );
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let failure_id = uuid::Uuid::new_v4().hyphenated().to_string();
    let socket = fixture.path().join("clean-refresh.sock");
    let backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        2,
        vec![
            (
                "raw/workspace-refresh",
                complete_refresh(&source, &failure_id),
            ),
            ("raw/diagnostics", diagnostics_with_error(&source)),
        ],
    );

    let refresh = kast(&home, &config_home, &workspace)
        .arg("refresh")
        .output()
        .expect("refresh");
    assert!(
        refresh.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&refresh.stdout),
        String::from_utf8_lossy(&refresh.stderr)
    );
    let refresh = decode(&refresh);
    assert_eq!(refresh["fileCount"], 1, "{refresh:#}");
    assert_eq!(refresh["files"], json!([source]), "{refresh:#}");
    let requests = backend.join().expect("clean refresh backend");
    let raw = requests
        .iter()
        .find(|request| request["method"] == "raw/workspace-refresh")
        .expect("workspace refresh");
    assert_eq!(raw["params"]["filePaths"], json!([source]));
}

#[test]
fn refresh_external_projects_only_actionable_outcomes() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let socket = fixture.path().join("external.sock");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![(
            "raw/workspace-refresh",
            json!({
                "refreshedFiles": [],
                "removedFiles": [],
                "fullRefresh": false,
                "fileStatuses": [],
                "externalFailureOutcomes": [
                    {"failureId": "failure-a", "status": "EXTERNALIZED"},
                    {"failureId": "failure-b", "status": "ALREADY_EXTERNAL"}
                ],
                "semanticOutcome": "COMPLETE",
                "requestedFileCount": 0,
                "analyzedFileCount": 0,
                "skippedFileCount": 0,
                "removedFileCount": 0,
                "attemptCount": 1,
                "elapsedMillis": 0,
                "schemaVersion": api_schema_version()
            }),
        )],
    );

    let external = kast(&home, &config_home, &workspace)
        .args(["refresh", "external", "failure-a", "failure-b"])
        .output()
        .expect("external refresh");
    assert!(
        external.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&external.stdout),
        String::from_utf8_lossy(&external.stderr)
    );
    let external = decode(&external);
    assert_eq!(
        external,
        json!({
            "external": [
                {"failureId": "failure-a", "status": "EXTERNALIZED"},
                {"failureId": "failure-b", "status": "ALREADY_EXTERNAL"}
            ]
        })
    );
    let requests = backend.join().expect("external backend");
    let request = requests
        .iter()
        .find(|request| request["method"] == "raw/workspace-refresh")
        .expect("workspace refresh");
    assert_eq!(
        request["params"]["externalFailureIds"],
        json!(["failure-a", "failure-b"])
    );
    assert_eq!(request["params"]["filePaths"], json!([]));
}

#[test]
fn refresh_external_not_found_is_an_actionable_failure() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let socket = fixture.path().join("external-not-found.sock");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![(
            "raw/workspace-refresh",
            json!({
                "refreshedFiles": [],
                "removedFiles": [],
                "externalFailureOutcomes": [
                    {"failureId": "failure-a", "status": "EXTERNALIZED"},
                    {"failureId": "stale-failure", "status": "NOT_FOUND"}
                ]
            }),
        )],
    );

    let external = kast(&home, &config_home, &workspace)
        .args(["refresh", "external", "failure-a", "stale-failure"])
        .output()
        .expect("external refresh");
    assert_eq!(external.status.code(), Some(1), "{external:?}");
    let external = decode(&external);
    assert_eq!(
        external,
        json!({
            "external": [
                {"failureId": "failure-a", "status": "EXTERNALIZED"},
                {"failureId": "stale-failure", "status": "NOT_FOUND"}
            ],
            "next": "Run `kast refresh <path>` for the affected file, then externalize the new failure ID."
        })
    );
    backend.join().expect("external backend");
}

#[test]
fn refresh_removes_graph_facts_without_diagnosing_a_deleted_file() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src")).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_empty_graph_scope(&workspace);
    let removed = workspace.join("src/Removed.kt");
    let socket = fixture.path().join("removed-refresh.sock");
    let backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        2,
        vec![
            (
                "raw/workspace-refresh",
                json!({
                    "refreshedFiles": [],
                    "removedFiles": [removed],
                    "relationshipFailures": []
                }),
            ),
            (
                "raw/semantic-graph",
                json!({
                    "generation": 10,
                    "scopeFingerprint": "a".repeat(64),
                    "coverage": {
                        "files": [{
                            "path": removed,
                            "status": "REMOVED",
                            "diagnostics": []
                        }],
                        "omittedExternalTargetCount": 0
                    },
                    "symbolCount": 0,
                    "edgeOccurrenceCount": 0
                }),
            ),
        ],
    );

    let refresh = kast(&home, &config_home, &workspace)
        .args(["refresh", removed.to_str().expect("removed path")])
        .output()
        .expect("refresh");
    assert!(
        refresh.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&refresh.stdout),
        String::from_utf8_lossy(&refresh.stderr)
    );
    let refresh = decode(&refresh);
    assert_eq!(refresh["fileCount"], 1);
    assert_eq!(refresh["files"], json!([]));
    assert_eq!(refresh["removedFiles"], json!([removed]));
    assert_eq!(refresh["diagnostics"]["cardinality"]["totalCount"], 0);

    let requests = backend.join().expect("removed refresh backend");
    assert!(
        requests
            .iter()
            .all(|request| request["method"] != "raw/diagnostics")
    );
    let graph = requests
        .iter()
        .find(|request| request["method"] == "raw/semantic-graph")
        .expect("graph refresh");
    assert_eq!(graph["params"]["filePaths"], json!([]));
    assert_eq!(graph["params"]["removedFilePaths"], json!([removed]));
}

fn complete_refresh(file: &Path, failure_id: &str) -> Value {
    let file = file.display().to_string();
    json!({
        "refreshedFiles": [file],
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file,
            "fileSystemDiscovery": "DISCOVERED",
            "sourceModuleOwnership": "OWNED",
            "indexAdmission": "ADMITTED",
            "analysisAvailability": "AVAILABLE",
            "analysisStatus": {"filePath": file, "state": "ANALYZED"}
        }],
        "relationshipFailures": [{
            "failureId": failure_id,
            "filePath": file,
            "code": "PSI_UNAVAILABLE"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "removedFileCount": 0,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": api_schema_version()
    })
}

fn seed_empty_graph_scope(workspace: &Path) -> WorkspaceIndexFixture {
    let index = WorkspaceIndexFixture::at_database_path(
        workspace,
        &workspace_database_path_for_test(workspace),
    );
    index
        .connection()
        .execute_batch(
            "CREATE TABLE semantic_files(
                 id INTEGER PRIMARY KEY,
                 path TEXT NOT NULL UNIQUE,
                 package_name TEXT,
                 module_name TEXT,
                 content_hash TEXT,
                 refresh_status TEXT NOT NULL,
                 diagnostics_json TEXT NOT NULL
             );",
        )
        .expect("empty semantic graph scope");
    index
}

fn diagnostics_with_error(file: &Path) -> Value {
    let file = file.display().to_string();
    json!({
        "diagnostics": [{
            "location": {
                "filePath": file,
                "startOffset": 12,
                "endOffset": 19,
                "startLine": 1,
                "startColumn": 13,
                "preview": "missing"
            },
            "severity": "ERROR",
            "message": "Unresolved reference",
            "code": "UNRESOLVED_REFERENCE"
        }],
        "fileStatuses": [{"filePath": file, "state": "ANALYZED"}],
        "fileHashes": [{"filePath": file, "hash": "c".repeat(64)}],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1}
    })
}
