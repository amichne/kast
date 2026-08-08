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
