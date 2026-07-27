fn incomplete_refresh(file: &Path) -> Value {
    json!({
        "refreshedFiles": [],
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "fileSystemDiscovery": "DISCOVERED",
            "sourceModuleOwnership": "OWNED",
            "indexAdmission": "PENDING",
            "analysisAvailability": "PENDING",
            "analysisStatus": {
                "filePath": file.display().to_string(),
                "state": "PENDING_INDEX",
                "message": "IDEA is indexing"
            }
        }],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "removedFileCount": 0,
        "attemptCount": 3,
        "elapsedMillis": 50,
        "schemaVersion": 5
    })
}

fn incomplete_diagnostics(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "ERROR",
            "message": "File not found after refresh",
            "code": "ANALYSIS_FAILURE"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "MISSING_ON_DISK",
            "message": "File not found after refresh"
        }],
        "fileHashes": [],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
    })
}

fn complete_compiler_diagnostics(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "ERROR",
            "message": "Type mismatch",
            "code": "TYPE_MISMATCH"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
    })
}

fn complete_clean_diagnostics(file: &Path) -> Value {
    complete_clean_diagnostics_for(&[file.display().to_string()])
}

fn complete_clean_diagnostics_for(file_paths: &[String]) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": file_paths
            .iter()
            .map(|file_path| json!({
                "filePath": file_path,
                "state": "ANALYZED"
            }))
            .collect::<Vec<_>>(),
        "fileHashes": file_paths
            .iter()
            .map(|file_path| diagnostic_file_hash_for_path(file_path))
            .collect::<Vec<_>>(),
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": file_paths.len(),
        "analyzedFileCount": file_paths.len(),
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn incomplete_diagnostics_with_truncated_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": true,
            "nextPageToken": "00000000-0000-4000-8000-000000000337"
        })),
    )
}

fn incomplete_diagnostics_with_untruncated_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": false
        })),
    )
}

fn incomplete_diagnostics_without_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(file, None)
}

fn incomplete_diagnostics_with_malformed_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": true,
            "nextPageToken": 0
        })),
    )
}

fn incomplete_diagnostics_with_page(file: &Path, page: Option<Value>) -> Value {
    let mut result = json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "WARNING",
            "message": "Visible warning before hidden analysis failure",
            "code": "VISIBLE_WARNING"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 1, "info": 0, "total": 2},
        "cardinality": {"type": "EXACT", "totalCount": 2},
        "schemaVersion": 5
    });
    if let Some(page) = page {
        result["page"] = page;
    }
    result
}

fn omitted_completeness_proof(_file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn complete_outcome_with_skipped_file(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "MISSING_ON_DISK",
            "message": "File not found"
        }],
        "fileHashes": [],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn missing_file_status_ledger(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn mismatched_file_status_ledger(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn unknown_file_analysis_state(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "NOT_A_STATE"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn malformed_diagnostic_code(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "ERROR",
            "message": "Malformed code",
            "code": 42
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
    })
}

fn malformed_diagnostic_structure(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "severity": "ERROR",
            "message": "Missing location",
            "code": "TYPE_MISMATCH"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
    })
}

fn malformed_completeness_evidence(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn diagnostic_location(file: &Path) -> Value {
    json!({
        "filePath": file.display().to_string(),
        "startOffset": 0,
        "endOffset": 0,
        "startLine": 0,
        "startColumn": 0,
        "preview": ""
    })
}

fn diagnostic_file_hash(file: &Path) -> Value {
    diagnostic_file_hash_for_path(&file.display().to_string())
}

fn diagnostic_file_hash_for_path(file_path: &str) -> Value {
    json!({
        "filePath": file_path,
        "hash": "a".repeat(64)
    })
}
