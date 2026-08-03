use super::*;

pub(super) fn unified_raw_result(
    workspace: &Path,
    request: &serde_json::Value,
    legacy_mutation_result: Option<serde_json::Value>,
) -> Option<serde_json::Value> {
    let method = request["method"].as_str()?;
    let params = &request["params"];
    match method {
        "raw/plan-add-file" => {
            let target = Path::new(params["targetPath"].as_str().expect("add-file target"));
            let proposed = params["proposedContent"].as_str().expect("add-file content");
            Some(unified_add_file_plan(workspace, target, proposed))
        }
        "raw/exact-file-observation" => Some(unified_exact_observation(
            workspace,
            params["filePath"].as_str().expect("observer path"),
        )),
        "raw/inspect-mutation-scratch" => Some(unified_scratch_inspect(workspace, params)),
        "raw/recover-mutation-scratch" => Some(unified_scratch_recover(workspace, params)),
        "raw/exact-file-image-cas" => {
            use base64::{Engine as _, engine::general_purpose::STANDARD};
            let path = params["filePath"].as_str().expect("CAS path");
            if params.get("mutationAttemptId").is_some() {
                assert_unified_mutation_scratch(params, path, &params["mutationScratch"]);
            }
            let previous = std::fs::read(path).expect("CAS preimage");
            let previous_sha256 = unified_source_sha256(&previous);
            assert_eq!(
                previous_sha256,
                params["expectedCurrentSha256"].as_str().expect("CAS expected preimage")
            );
            let result = STANDARD
                .decode(params["contentBase64"].as_str().expect("CAS content"))
                .expect("CAS base64");
            let result_sha256 = unified_source_sha256(&result);
            assert_eq!(
                result_sha256,
                params["expectedResultSha256"].as_str().expect("CAS expected result")
            );
            std::fs::write(path, result).expect("CAS write");
            Some(serde_json::json!({
                "filePath": path,
                "status": "COMMITTED",
                "previousSha256": previous_sha256,
                "resultSha256": result_sha256,
                "schemaVersion": 6,
            }))
        }
        "raw/apply-edits" => {
            if legacy_mutation_result
                .as_ref()
                .is_some_and(|result| result["type"] == "FAILED")
            {
                return legacy_mutation_result;
            }
            let operations = params["fileOperations"]
                .as_array()
                .expect("file operations");
            assert_eq!(operations.len(), 1, "one file operation");
            let operation = &operations[0];
            let path = operation["filePath"].as_str().expect("file operation path");
            if params.get("mutationAttemptId").is_some() {
                let scratch = params["mutationScratchSets"]
                    .as_array()
                    .expect("verified file-operation scratch sets");
                assert_eq!(scratch.len(), 1, "one verified file-operation scratch set");
                assert_unified_mutation_scratch(params, path, &scratch[0]);
            }
            let (created_files, deleted_files) = match operation["type"].as_str() {
                Some("CREATE_FILE") => {
                    assert_eq!(operation["parentPolicy"], "REQUIRE_EXISTING_PARENTS");
                    assert!(!Path::new(path).exists(), "create destination must be absent");
                    std::fs::write(
                        path,
                        operation["content"].as_str().expect("create content"),
                    )
                    .expect("raw create");
                    (vec![path], Vec::new())
                }
                Some("DELETE_FILE") => {
                    let bytes = std::fs::read(path).expect("delete preimage");
                    assert_eq!(
                        unified_source_sha256(&bytes),
                        operation["expectedHash"].as_str().expect("delete hash")
                    );
                    std::fs::remove_file(path).expect("raw delete");
                    (Vec::new(), vec![path])
                }
                other => panic!("unsupported file operation {other:?}"),
            };
            Some(serde_json::json!({
                "applied": [],
                "affectedFiles": [path],
                "createdFiles": created_files,
                "deletedFiles": deleted_files,
                "schemaVersion": 6,
            }))
        }
        "raw/workspace-refresh" => Some(unified_refresh(
            params["filePaths"].as_array().expect("refresh paths"),
        )),
        "raw/diagnostics" => Some(unified_diagnostics(
            params["filePaths"].as_array().expect("diagnostic paths"),
        )),
        "raw/verify-mutation-postcondition" => {
            Some(unified_postcondition(&params["authority"]))
        }
        _ => None,
    }
}
