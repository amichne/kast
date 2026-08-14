use super::*;

pub(super) fn unified_refresh(paths: &[serde_json::Value]) -> serde_json::Value {
    let refreshed_files = paths
        .iter()
        .filter(|path| Path::new(path.as_str().expect("refresh path")).is_file())
        .cloned()
        .collect::<Vec<_>>();
    let removed_files = paths
        .iter()
        .filter(|path| !Path::new(path.as_str().expect("refresh path")).exists())
        .cloned()
        .collect::<Vec<_>>();
    serde_json::json!({
        "refreshedFiles": refreshed_files,
        "removedFiles": removed_files,
        "fullRefresh": false,
        "fileStatuses": paths.iter().map(|path| {
            if Path::new(path.as_str().expect("refresh path")).is_file() {
                serde_json::json!({
                    "filePath": path,
                    "fileSystemDiscovery": "DISCOVERED",
                    "sourceModuleOwnership": "OWNED",
                    "indexAdmission": "ADMITTED",
                    "analysisAvailability": "AVAILABLE",
                    "analysisStatus": {"filePath": path, "state": "ANALYZED"},
                })
            } else {
                serde_json::json!({
                    "filePath": path,
                    "fileSystemDiscovery": "REMOVED",
                    "sourceModuleOwnership": "NOT_APPLICABLE",
                    "indexAdmission": "NOT_APPLICABLE",
                    "analysisAvailability": "NOT_APPLICABLE",
                })
            }
        }).collect::<Vec<_>>(),
        "externalFailureOutcomes": [],
        "relationshipFailures": [],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": refreshed_files.len(),
        "analyzedFileCount": refreshed_files.len(),
        "skippedFileCount": 0,
        "removedFileCount": removed_files.len(),
        "attemptCount": 1,
        "elapsedMillis": 1,
        "schemaVersion": api_schema_version(),
    })
}

pub(super) fn unified_diagnostics(paths: &[serde_json::Value]) -> serde_json::Value {
    let file_hashes = paths
        .iter()
        .map(|path| {
            let path = path.as_str().expect("diagnostic path");
            let bytes = std::fs::read(path).expect("diagnostic source");
            serde_json::json!({"filePath": path, "hash": unified_source_sha256(&bytes)})
        })
        .collect::<Vec<_>>();
    serde_json::json!({
        "diagnostics": [],
        "fileStatuses": paths.iter().map(|path| {
            serde_json::json!({"filePath": path, "state": "ANALYZED"})
        }).collect::<Vec<_>>(),
        "fileHashes": file_hashes,
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": paths.len(),
        "analyzedFileCount": paths.len(),
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": api_schema_version(),
    })
}

pub(super) fn unified_postcondition(authority: &serde_json::Value) -> serde_json::Value {
    let operation = authority["type"].as_str().expect("postcondition operation");
    let (postimages, evidence) = match operation {
        "ADD_FILE" => (
            vec![serde_json::json!({
                "filePath": authority["proof"]["targetPath"],
                "sha256": authority["postimage"]["sha256"],
            })],
            serde_json::json!({
                "type": "ADD_FILE",
                "owner": authority["proof"]["owner"],
                "packageIdentity": authority["proof"]["packageIdentity"],
                "declarations": authority["proof"]["declarations"],
                "outboundEvidence": authority["proof"]["outboundEvidence"],
            }),
        ),
        "ADD_DECLARATION" => (
            vec![serde_json::json!({
                "filePath": authority["image"]["filePath"],
                "sha256": authority["image"]["postimage"]["sha256"],
            })],
            serde_json::json!({
                "type": "ADD_DECLARATION",
                "owner": authority["proof"]["owner"],
                "packageIdentity": authority["proof"]["packageIdentity"],
                "declaration": authority["proof"]["declaration"],
                "outboundEvidence": authority["proof"]["outboundEvidence"],
            }),
        ),
        "RENAME" => (
            authority["images"].as_array().expect("rename images").iter().map(|image| serde_json::json!({
                "filePath": image["filePath"],
                "sha256": image["postimage"]["sha256"],
            })).collect(),
            unified_rename_postcondition_evidence(authority),
        ),
        "REPLACEMENT" => (
            authority["images"].as_array().expect("replacement images").iter().map(|image| serde_json::json!({
                "filePath": image["filePath"],
                "sha256": image["postimage"]["sha256"],
            })).collect(),
            serde_json::json!({
                "type": "REPLACEMENT",
                "resultingTarget": authority["proof"]["target"],
                "sourceRange": {
                    "filePath": authority["edit"]["filePath"],
                    "startOffset": authority["edit"]["startOffset"].as_u64().expect("replacement start")
                        + authority["proof"]["declarationSlice"]["startOffset"].as_u64()
                            .expect("replacement declaration start"),
                    "endOffset": authority["edit"]["startOffset"].as_u64().expect("replacement start")
                        + authority["proof"]["declarationSlice"]["endOffset"].as_u64()
                            .expect("replacement declaration end"),
                    "startLine": 1,
                    "startColumn": 1,
                    "preview": authority["edit"]["newText"],
                },
                "signature": authority["proof"]["proposedSignature"],
                "outboundEvidence": authority["proof"]["evidence"],
                "outboundReferences": authority["proof"]["outboundReferences"],
            }),
        ),
        other => panic!("unsupported unified postcondition {other}"),
    };
    serde_json::json!({
        "status": "VERIFIED",
        "operation": operation,
        "currentGeneration": 8,
        "postimages": postimages,
        "evidence": evidence,
        "schemaVersion": api_schema_version(),
    })
}

pub(super) fn unified_rename_postcondition_evidence(authority: &serde_json::Value) -> serde_json::Value {
    let edits = authority["edits"].as_array().expect("rename edits");
    let proof = &authority["proof"];
    let target = &proof["target"];
    let target_file = target["declarationFile"].as_str().expect("rename target file");
    let target_start = target["declarationStartOffset"]
        .as_u64()
        .expect("rename target offset");
    let declaration_edit = edits
        .iter()
        .find(|edit| {
            edit["filePath"] == target_file && edit["startOffset"].as_u64() == Some(target_start)
        })
        .expect("rename declaration edit");
    let adjusted = |edit: &serde_json::Value| {
        let file = edit["filePath"].as_str().expect("rename edit file");
        let start = edit["startOffset"].as_i64().expect("rename edit start");
        let delta = edits
            .iter()
            .filter(|prior| {
                prior["filePath"] == file
                    && prior["startOffset"].as_i64().expect("prior start") < start
            })
            .map(|prior| {
                prior["newText"].as_str().expect("prior text").encode_utf16().count() as i64
                    - (prior["endOffset"].as_i64().expect("prior end")
                        - prior["startOffset"].as_i64().expect("prior start"))
            })
            .sum::<i64>();
        let current_start = start + delta;
        let current_end = current_start
            + edit["newText"].as_str().expect("rename edit text").encode_utf16().count() as i64;
        (current_start, current_end)
    };
    let (target_current_start, _) = adjusted(declaration_edit);
    let mut resulting_target = target.clone();
    let old_fq_name = target["fqName"].as_str().expect("rename fq name");
    let new_name = declaration_edit["newText"].as_str().expect("rename new name");
    let renamed_fq_name = old_fq_name
        .rsplit_once('.')
        .map_or_else(|| new_name.to_string(), |(owner, _)| format!("{owner}.{new_name}"));
    resulting_target["fqName"] = serde_json::json!(renamed_fq_name);
    resulting_target["declarationStartOffset"] = serde_json::json!(target_current_start);
    let occurrences = proof["occurrences"]
        .as_array()
        .expect("rename occurrences")
        .iter()
        .map(|occurrence| {
            let location = &occurrence["reference"]["location"];
            let edit = edits
                .iter()
                .find(|edit| {
                    edit["filePath"] == location["filePath"]
                        && edit["startOffset"] == location["startOffset"]
                        && edit["endOffset"] == location["endOffset"]
                })
                .expect("rename occurrence edit");
            let (start, end) = adjusted(edit);
            let mut current = occurrence.clone();
            current["reference"]["location"]["startOffset"] = serde_json::json!(start);
            current["reference"]["location"]["endOffset"] = serde_json::json!(end);
            current["resolvedTarget"] = resulting_target.clone();
            current
        })
        .collect::<Vec<_>>();
    serde_json::json!({
        "type": "RENAME",
        "resultingTarget": resulting_target,
        "evidence": proof["evidence"],
        "occurrences": occurrences,
    })
}
