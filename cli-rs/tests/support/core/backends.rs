fn unified_mutation_capabilities() -> Vec<&'static str> {
    vec![
        "RENAME",
        "APPLY_EDITS",
        "FILE_OPERATIONS",
        "EXACT_FILE_OBSERVATION",
        "EXACT_FILE_IMAGE_CAS",
        "PLAN_ADD_FILE",
        "PLAN_ADD_DECLARATION",
        "PLAN_REPLACEMENT",
        "VERIFY_MUTATION_POSTCONDITION",
        "MUTATION_SCRATCH_RECOVERY",
        "REFRESH_WORKSPACE",
    ]
}

fn unified_source_sha256(bytes: &[u8]) -> String {
    use sha2::{Digest as _, Sha256};
    hex::encode(Sha256::digest(bytes))
}

fn unified_base64(bytes: &[u8]) -> String {
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    STANDARD.encode(bytes)
}

fn unified_add_file_plan(
    workspace: &Path,
    target: &Path,
    proposed: &str,
) -> serde_json::Value {
    let sha256 = unified_source_sha256(proposed.as_bytes());
    serde_json::json!({
        "proposedContent": proposed,
        "postimage": {
            "contentBase64": unified_base64(proposed.as_bytes()),
            "sha256": sha256,
        },
        "proof": {
            "targetPath": target,
            "targetState": "ABSENT",
            "owner": {
                "sourceRoot": target.parent().expect("add-file source root"),
                "ideaModuleName": "root.main",
                "gradleBuildRoot": workspace,
                "gradleProjectPath": ":",
                "sourceSetName": "main",
            },
            "packageIdentity": {"type": "ROOT"},
            "declarations": [{
                "packageIdentity": {"type": "ROOT"},
                "name": "Added",
                "kind": "CLASS",
                "relativeRange": {
                    "startOffset": 0,
                    "endOffset": proposed.encode_utf16().count(),
                },
                "collisionSignature": "1".repeat(64),
            }],
            "context": {
                "requiredGeneration": 7,
                "projectModelFingerprint": "2".repeat(64),
                "classpathFingerprint": "3".repeat(64),
                "contextFileHashes": [],
            },
            "collisionEvidence": {
                "declarationCardinality": 1,
                "dimensions": [
                    "EXACT_DECLARATION_IDENTITIES",
                    "COMPLETE_OWNING_SOURCE_SCOPE",
                    "COMPLETE_DEPENDENT_SCOPE",
                    "NO_COMPILER_COLLISION",
                ],
            },
            "outboundEvidence": {"cardinality": 0, "occurrences": []},
            "rebindingBaseline": {
                "cardinality": 0,
                "dimensions": [
                    "EXACT_OCCURRENCE_CARDINALITY",
                    "COMPLETE_DEPENDENT_SCOPE",
                    "COMPLETE_IMPLICIT_LOOKUP_SCOPE",
                    "COMPLETE_JAVA_LOOKUP_SCOPE",
                    "EVERY_CURRENT_BINDING_CAPTURED",
                    "VIRTUAL_PROPOSED_BINDINGS_EQUAL_BASELINE",
                ],
                "occurrences": [],
            },
            "postimageSha256": sha256,
        },
        "schemaVersion": 6,
    })
}

fn unified_exact_observation(
    workspace: &Path,
    relative_path: &str,
) -> serde_json::Value {
    let target = workspace.join(relative_path);
    match std::fs::read(&target) {
        Ok(bytes) => serde_json::json!({
            "type": "PRESENT",
            "filePath": relative_path,
            "image": {
                "contentBase64": unified_base64(&bytes),
                "sha256": unified_source_sha256(&bytes),
            },
        }),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => serde_json::json!({
            "type": "ABSENT",
            "filePath": relative_path,
        }),
        Err(error) => panic!("unified observer {}: {error}", target.display()),
    }
}

fn unified_scratch_observation(
    path: &Path,
    ownership: &str,
    role: &str,
) -> serde_json::Value {
    match std::fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_file() => {
            let bytes = std::fs::read(path).expect("read mutation scratch");
            serde_json::json!({
                "filePath": path,
                "ownership": ownership,
                "role": role,
                "state": "PRESENT",
                "sha256": unified_source_sha256(&bytes),
            })
        }
        Ok(_) => serde_json::json!({
            "filePath": path,
            "ownership": ownership,
            "role": role,
            "state": "UNSAFE",
        }),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => serde_json::json!({
            "filePath": path,
            "ownership": ownership,
            "role": role,
            "state": "ABSENT",
        }),
        Err(error) => panic!("inspect mutation scratch {}: {error}", path.display()),
    }
}

fn unified_scratch_inspect(workspace: &Path, params: &serde_json::Value) -> serde_json::Value {
    let owned_sets = params["ownedScratchSets"]
        .as_array()
        .expect("owned scratch sets");
    let mut owned_paths = std::collections::BTreeSet::new();
    let mut observations = Vec::new();
    for scratch in owned_sets {
        for (field, role) in [
            ("quarantinePath", "QUARANTINE"),
            ("preparedPath", "PREPARED"),
            ("preparedCleanupPath", "PREPARED_CLEANUP"),
            ("quarantineCleanupPath", "QUARANTINE_CLEANUP"),
        ] {
            let path = Path::new(scratch[field].as_str().expect("owned scratch path"));
            assert!(path.is_absolute(), "owned scratch path must be absolute");
            assert!(path.starts_with(workspace), "owned scratch escaped workspace");
            assert!(owned_paths.insert(path.to_path_buf()), "duplicate owned scratch path");
            observations.push(unified_scratch_observation(path, "OWNED", role));
        }
    }
    for relative_parent in params["workspaceRelativeParentPaths"]
        .as_array()
        .expect("scratch parent paths")
    {
        let relative_parent = relative_parent.as_str().expect("scratch parent path");
        let parent = if relative_parent == "." {
            workspace.to_path_buf()
        } else {
            workspace.join(relative_parent)
        };
        for entry in std::fs::read_dir(&parent).expect("enumerate scratch parent") {
            let entry = entry.expect("scratch parent entry");
            let name = entry.file_name();
            let name = name.to_str().expect("UTF-8 scratch entry");
            if ![
                ".kast-quarantine-",
                ".kast-prepared-",
                ".kast-cleanup-",
            ]
            .iter()
            .any(|prefix| name.starts_with(prefix))
            {
                continue;
            }
            let path = entry.path();
            if !owned_paths.contains(&path) {
                observations.push(unified_scratch_observation(
                    &path,
                    "UNOWNED",
                    "UNOWNED_INTERNAL",
                ));
            }
        }
    }
    observations.sort_by(|left, right| {
        left["filePath"]
            .as_str()
            .expect("left scratch path")
            .cmp(right["filePath"].as_str().expect("right scratch path"))
    });
    serde_json::json!({
        "mutationAttemptId": params["mutationAttemptId"],
        "observations": observations,
        "schemaVersion": 6,
    })
}

fn unified_scratch_recover(workspace: &Path, params: &serde_json::Value) -> serde_json::Value {
    assert_eq!(params["action"], "RESTORE_PREIMAGE");
    let target = Path::new(
        params["targetFilePath"]
            .as_str()
            .expect("scratch recovery target"),
    );
    assert!(target.is_absolute(), "scratch recovery target must be absolute");
    assert!(target.starts_with(workspace), "scratch recovery target escaped workspace");
    let postimage = &params["postimage"];
    let post_sha = postimage["sha256"]
        .as_str()
        .expect("scratch recovery postimage hash");
    let preimage = &params["preimage"];
    let pre_sha = (preimage["state"] == "PRESENT")
        .then(|| preimage["image"]["sha256"].as_str().expect("scratch recovery preimage hash"));
    let empty_sha = unified_source_sha256(&[]);
    let scratch = &params["scratch"];
    let roles = [
        ("quarantinePath", "QUARANTINE"),
        ("preparedPath", "PREPARED"),
        ("preparedCleanupPath", "PREPARED_CLEANUP"),
        ("quarantineCleanupPath", "QUARANTINE_CLEANUP"),
    ];
    for (field, _) in roles {
        let path = Path::new(scratch[field].as_str().expect("scratch recovery path"));
        assert!(path.starts_with(workspace), "scratch recovery path escaped workspace");
        match std::fs::symlink_metadata(path) {
            Ok(metadata) if metadata.file_type().is_file() => {
                let actual = unified_source_sha256(
                    &std::fs::read(path).expect("read recoverable mutation scratch"),
                );
                assert!(
                    actual == post_sha || Some(actual.as_str()) == pre_sha || actual == empty_sha,
                    "scratch recovery rejected a foreign image"
                );
                std::fs::remove_file(path).expect("remove owned mutation scratch");
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Ok(_) => panic!("scratch recovery rejected an unsafe role"),
            Err(error) => panic!("inspect recoverable scratch {}: {error}", path.display()),
        }
    }
    let (target_state, target_sha256) = match preimage["state"].as_str() {
        Some("ABSENT") => {
            match std::fs::symlink_metadata(target) {
                Ok(metadata) if metadata.file_type().is_file() => {
                    let actual = unified_source_sha256(
                        &std::fs::read(target).expect("read scratch recovery target"),
                    );
                    assert_eq!(actual, post_sha, "scratch recovery target was foreign");
                    std::fs::remove_file(target).expect("restore absent preimage");
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Ok(_) => panic!("scratch recovery target was unsafe"),
                Err(error) => panic!("inspect scratch recovery target: {error}"),
            }
            ("ABSENT", None)
        }
        Some("PRESENT") => {
            use base64::{Engine as _, engine::general_purpose::STANDARD};
            let image = &preimage["image"];
            let bytes = STANDARD
                .decode(image["contentBase64"].as_str().expect("preimage Base64"))
                .expect("decode scratch recovery preimage");
            let expected = image["sha256"].as_str().expect("preimage hash");
            assert_eq!(unified_source_sha256(&bytes), expected);
            if target.exists() {
                let actual = unified_source_sha256(
                    &std::fs::read(target).expect("read scratch recovery target"),
                );
                assert!(actual == expected || actual == post_sha, "scratch recovery target was foreign");
            }
            std::fs::write(target, bytes).expect("restore present preimage");
            ("PRESENT", Some(expected))
        }
        other => panic!("unknown scratch recovery preimage state {other:?}"),
    };
    let observations = roles.map(|(field, role)| {
        serde_json::json!({
            "filePath": scratch[field],
            "ownership": "OWNED",
            "role": role,
            "state": "ABSENT",
        })
    });
    let mut result = serde_json::json!({
        "mutationAttemptId": params["mutationAttemptId"],
        "action": params["action"],
        "outcome": "RESTORED_PREIMAGE",
        "targetState": target_state,
        "scratchObservations": observations,
        "schemaVersion": 6,
    });
    if let Some(target_sha256) = target_sha256 {
        result
            .as_object_mut()
            .expect("scratch recovery result object")
            .insert(
                "targetSha256".to_owned(),
                serde_json::Value::String(target_sha256.to_owned()),
            );
    }
    result
}

fn assert_unified_mutation_scratch(
    params: &serde_json::Value,
    target: &str,
    scratch: &serde_json::Value,
) {
    let raw_attempt = params["mutationAttemptId"]
        .as_str()
        .expect("verified mutation attempt id");
    let attempt = uuid::Uuid::parse_str(raw_attempt).expect("mutation attempt UUID");
    assert_eq!(attempt.get_version(), Some(uuid::Version::Random));
    assert_eq!(attempt.hyphenated().to_string(), raw_attempt);
    assert_eq!(scratch["targetFilePath"], target);
    let parent = Path::new(target).parent().expect("mutation target parent");
    for field in [
        "quarantinePath",
        "preparedPath",
        "preparedCleanupPath",
        "quarantineCleanupPath",
    ] {
        let path = Path::new(scratch[field].as_str().expect("mutation scratch role"));
        assert_eq!(path.parent(), Some(parent));
        assert!(
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.contains(&attempt.hyphenated().to_string())),
            "active mutation scratch did not bind its fence epoch"
        );
    }
}

fn unified_refresh(paths: &[serde_json::Value]) -> serde_json::Value {
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
        "schemaVersion": 6,
    })
}

fn unified_diagnostics(paths: &[serde_json::Value]) -> serde_json::Value {
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
    })
}

fn unified_postcondition(authority: &serde_json::Value) -> serde_json::Value {
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
                    "startOffset": authority["edit"]["startOffset"],
                    "endOffset": authority["edit"]["startOffset"].as_u64().expect("replacement start")
                        + authority["edit"]["newText"].as_str().expect("replacement text").encode_utf16().count() as u64,
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
        "schemaVersion": 6,
    })
}

fn unified_rename_postcondition_evidence(authority: &serde_json::Value) -> serde_json::Value {
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

fn unified_raw_result(
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

pub(crate) fn scripted_json_rpc_error(
    code: &str,
    message: &str,
    details: serde_json::Value,
    apply_default_mutation: bool,
) -> serde_json::Value {
    serde_json::json!({
        "__kastTestApplyDefaultMutation": apply_default_mutation,
        "__kastTestJsonRpcError": {
            "code": -32500,
            "message": message,
            "data": {
                "schemaVersion": api_schema_version(),
                "requestId": "scripted-test-request",
                "code": code,
                "message": message,
                "retryable": false,
                "details": details,
            },
        },
    })
}

pub(crate) fn scripted_json_rpc_error_with_retained_artifact(
    code: &str,
    message: &str,
    details: serde_json::Value,
    apply_default_mutation: bool,
    artifact_path: &Path,
    artifact_contents: &[u8],
) -> serde_json::Value {
    let mut reply = scripted_json_rpc_error(code, message, details, apply_default_mutation);
    reply["__kastTestRetainedArtifactPath"] =
        serde_json::json!(artifact_path.display().to_string());
    reply["__kastTestRetainedArtifactBase64"] =
        serde_json::json!(unified_base64(artifact_contents));
    reply
}

pub(crate) fn spawn_scripted_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        vec![],
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_scripted_mutating_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_lease_only_mutating_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    shutdown_marker: &Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        Some(shutdown_marker.to_path_buf()),
        None,
        vec![],
    )
}

pub(crate) fn spawn_scripted_mutating_indexer_backend_with_file_write(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    file_path: &Path,
    contents: &[u8],
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        Some((file_path.to_path_buf(), contents.to_vec())),
        None,
        None,
        None,
        scripted_results,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_mutating_indexer_backend_with_file_write(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    file_path: &Path,
    contents: &[u8],
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        Some((file_path.to_path_buf(), contents.to_vec())),
        Some((entered_marker.to_path_buf(), release_marker.to_path_buf())),
        None,
        None,
        scripted_results,
    )
}

#[derive(Clone, Copy)]
enum ScriptedScratchCrash {
    PreparedPostimage,
    PreparedForeign,
    QuarantinePreimage,
}

struct ScriptedScratchCrashGate {
    mode: ScriptedScratchCrash,
    entered_marker: PathBuf,
    release_marker: PathBuf,
}

impl ScriptedScratchCrash {
    fn method(self) -> &'static str {
        match self {
            Self::PreparedPostimage | Self::PreparedForeign => "raw/apply-edits",
            Self::QuarantinePreimage => "raw/exact-file-image-cas",
        }
    }
}

fn retain_declared_scratch_until_release(
    request: &serde_json::Value,
    gate: ScriptedScratchCrashGate,
) -> serde_json::Value {
    let params = &request["params"];
    let retained = match gate.mode {
        ScriptedScratchCrash::PreparedPostimage | ScriptedScratchCrash::PreparedForeign => {
            let operation = &params["fileOperations"][0];
            assert_eq!(operation["type"], "CREATE_FILE");
            let target = Path::new(operation["filePath"].as_str().expect("create target"));
            assert!(!target.exists(), "prepared crash target must retain absent preimage");
            let scratch = &params["mutationScratchSets"][0];
            let prepared = PathBuf::from(
                scratch["preparedPath"]
                    .as_str()
                    .expect("declared prepared path"),
            );
            let content = match gate.mode {
                ScriptedScratchCrash::PreparedPostimage => {
                    operation["content"].as_str().expect("create content").as_bytes()
                }
                ScriptedScratchCrash::PreparedForeign => b"foreign scratch image",
                ScriptedScratchCrash::QuarantinePreimage => unreachable!("closed crash mode"),
            };
            std::fs::write(&prepared, content).expect("retain declared prepared postimage");
            prepared
        }
        ScriptedScratchCrash::QuarantinePreimage => {
            let target = Path::new(params["filePath"].as_str().expect("CAS target"));
            let quarantine = PathBuf::from(
                params["mutationScratch"]["quarantinePath"]
                    .as_str()
                    .expect("declared quarantine path"),
            );
            std::fs::rename(target, &quarantine).expect("retain declared quarantine preimage");
            quarantine
        }
    };
    std::fs::write(&gate.entered_marker, retained.display().to_string())
        .expect("scratch crash entered marker");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while !gate.release_marker.is_file() && std::time::Instant::now() < deadline {
        thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(gate.release_marker.is_file(), "scratch crash release marker");
    scripted_json_rpc_error(
        "UNSAFE_WORKSPACE_MUTATION",
        "The test backend retained exact journal-owned scratch before responding",
        serde_json::json!({
            "recoveryFilePathCount": "1",
            "recoveryFilePath.0": retained,
        }),
        false,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_prepared_scratch_crash_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        Some(ScriptedScratchCrashGate {
            mode: ScriptedScratchCrash::PreparedPostimage,
            entered_marker: entered_marker.to_path_buf(),
            release_marker: release_marker.to_path_buf(),
        }),
        scripted_results,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_quarantine_scratch_crash_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        Some(ScriptedScratchCrashGate {
            mode: ScriptedScratchCrash::QuarantinePreimage,
            entered_marker: entered_marker.to_path_buf(),
            release_marker: release_marker.to_path_buf(),
        }),
        scripted_results,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_foreign_prepared_scratch_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        Some(ScriptedScratchCrashGate {
            mode: ScriptedScratchCrash::PreparedForeign,
            entered_marker: entered_marker.to_path_buf(),
            release_marker: release_marker.to_path_buf(),
        }),
        scripted_results,
    )
}

pub(crate) fn runtime_descriptor_for_test(
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    backend_version: &str,
) -> serde_json::Value {
    runtime_descriptor_for_process_test(
        workspace,
        socket_path,
        backend_name,
        backend_version,
        std::process::id(),
    )
}

pub(crate) fn runtime_descriptor_for_process_test(
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    backend_version: &str,
    pid: u32,
) -> serde_json::Value {
    use std::os::unix::fs::MetadataExt;

    let socket = std::fs::metadata(socket_path).expect("bound runtime socket identity");
    let output = Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()
        .expect("process start observation");
    assert!(output.status.success(), "process start observation");
    let started_at = std::ffi::CString::new(String::from_utf8(output.stdout).expect("UTF-8 ps output").trim())
        .expect("process start contains no NUL");
    let mut parsed = unsafe { std::mem::zeroed::<libc::tm>() };
    parsed.tm_isdst = -1;
    assert!(
        !unsafe { libc::strptime(started_at.as_ptr(), c"%a %b %e %T %Y".as_ptr(), &mut parsed) }
            .is_null(),
        "parse process start"
    );
    let start_epoch_seconds = unsafe { libc::mktime(&mut parsed) };
    assert!(start_epoch_seconds > 0, "positive process start");
    serde_json::json!({
        "workspaceRoot": workspace.display().to_string(),
        "backendName": backend_name,
        "backendVersion": backend_version,
        "runtimeInstanceId": format!("test-{pid}-{}", socket.ino()),
        "processStartEpochMillis": u64::try_from(start_epoch_seconds).expect("process start") * 1_000,
        "ownerUid": u64::from(unsafe { libc::geteuid() }),
        "socketFileIdentity": {"device": socket.dev(), "inode": socket.ino()},
        "transport": "uds",
        "socketPath": socket_path.display().to_string(),
        "pid": pid,
        "schemaVersion": 6
    })
}

pub(crate) fn spawn_scripted_indexer_backend_for_invocations(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        invocation_count,
        false,
        vec![],
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_ready_indexer_backend_after_marker(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    marker: &Path,
    invocation_count: usize,
) -> std::thread::JoinHandle<Option<Vec<serde_json::Value>>> {
    let home = home.to_path_buf();
    let config_home = config_home.to_path_buf();
    let workspace = workspace.to_path_buf();
    let socket_path = socket_path.to_path_buf();
    let marker = marker.to_path_buf();
    thread::spawn(move || {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !marker.is_file() && std::time::Instant::now() < deadline {
            thread::sleep(std::time::Duration::from_millis(10));
        }
        if !marker.is_file() {
            return None;
        }
        let observation_deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
        while std::time::Instant::now() < observation_deadline {
            let launches = std::fs::read_to_string(&marker)
                .unwrap_or_default()
                .lines()
                .filter(|line| *line == "__KAST_SIDECAR_LAUNCH__")
                .count();
            if launches >= invocation_count {
                break;
            }
            thread::sleep(std::time::Duration::from_millis(10));
        }
        Some(
            spawn_scripted_backend(
                &home,
                &config_home,
                &workspace,
                &socket_path,
                "indexer",
                invocation_count,
                true,
                vec![],
                None,
                None,
                None,
                None,
                vec![],
            )
            .join()
            .expect("ready indexer"),
        )
    })
}

#[allow(clippy::too_many_arguments)]
fn spawn_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    invocation_count: usize,
    semantic_ready: bool,
    mutation_capabilities: Vec<&'static str>,
    _mutation_file_write: Option<(PathBuf, Vec<u8>)>,
    mutation_gate: Option<(PathBuf, PathBuf)>,
    keepalive_until: Option<PathBuf>,
    scratch_crash_gate: Option<ScriptedScratchCrashGate>,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    assert!(invocation_count > 0, "scripted backend needs an invocation");
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical scripted workspace");
    let listener = UnixListener::bind(socket_path).expect("bind scripted backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            backend_name,
            "scripted-test",
        )]))
        .expect("descriptor json"),
    )
    .expect("descriptor");
    listener
        .set_nonblocking(true)
        .expect("nonblocking scripted backend");
    let server_workspace = workspace;
    let server_backend_name = backend_name.to_string();
    thread::spawn(move || {
        let mut requests = Vec::new();
        let mut mutation_gate = mutation_gate;
        let mut scratch_crash_gate = scratch_crash_gate;
        let mut scripted_results = scripted_results.into_iter();
        let expected_requests = 2 * invocation_count + scripted_results.len();
        let mut unified_session_active = false;
        let mut unified_session_complete = false;
        let mut unified_semantic_verification_complete = false;
        let mut idle_deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        while requests.len() < expected_requests
            || scripted_results.len() > 0
            || (unified_session_active
                && !unified_session_complete
                && std::time::Instant::now() < idle_deadline)
            || keepalive_until
                .as_ref()
                .is_some_and(|marker| !marker.exists())
        {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if std::time::Instant::now() >= idle_deadline {
                        return requests;
                    }
                    thread::sleep(std::time::Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept scripted backend client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking scripted backend stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"].as_str().expect("method");
            let result = match method {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": server_backend_name.as_str(),
                    "backendVersion": "scripted-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "sourceModuleNames": if semantic_ready { vec![":fixture"] } else { vec![] },
                    "referenceIndexReady": semantic_ready,
                    "schemaVersion": 6
                }),
                "capabilities" => serde_json::json!({
                    "backendName": server_backend_name.as_str(),
                    "backendVersion": "scripted-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [
                        "symbol/resolve",
                        "symbol/references",
                        "symbol/callers",
                        "symbol/implementations",
                        "symbol/hierarchy",
                        "raw/call-hierarchy",
                        "raw/implementations",
                        "raw/type-hierarchy"
                    ],
                    "mutationCapabilities": mutation_capabilities.clone(),
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 6
                }),
                _ => {
                    if matches!(
                        method,
                        "raw/rename"
                            | "raw/plan-replacement"
                            | "raw/plan-add-file"
                            | "raw/plan-add-declaration"
                            | "raw/exact-file-observation"
                            | "raw/inspect-mutation-scratch"
                            | "raw/recover-mutation-scratch"
                            | "raw/exact-file-image-cas"
                            | "raw/apply-edits"
                            | "raw/workspace-refresh"
                            | "raw/diagnostics"
                            | "raw/verify-mutation-postcondition"
                    )
                    {
                        unified_session_active = true;
                    }
                    if method == "raw/apply-edits"
                        && let Some((entered_marker, release_marker)) = mutation_gate.take()
                    {
                        std::fs::write(&entered_marker, "entered\n")
                            .expect("mutation gate entered marker");
                        let deadline =
                            std::time::Instant::now() + std::time::Duration::from_secs(10);
                        while !release_marker.is_file() && std::time::Instant::now() < deadline {
                            thread::sleep(std::time::Duration::from_millis(10));
                        }
                        assert!(release_marker.is_file(), "mutation gate release marker");
                    }
                    let scratch_crash_result = scratch_crash_gate
                        .as_ref()
                        .is_some_and(|gate| gate.mode.method() == method)
                        .then(|| {
                            retain_declared_scratch_until_release(
                                &request,
                                scratch_crash_gate.take().expect("matching scratch crash gate"),
                            )
                        });
                    let scripted_method = scripted_results
                        .as_slice()
                        .first()
                        .map(|(expected, _)| *expected);
                    let legacy_mutation_result = (scratch_crash_result.is_none()
                        && method == "raw/apply-edits"
                        && scripted_method == Some("mutation/submit"))
                        .then(|| scripted_results.next().expect("legacy mutation result").1);
                    let result = if let Some(result) = scratch_crash_result {
                        result
                    } else if scripted_method == Some(method) {
                        let scripted = scripted_results.next().expect("scripted result").1;
                        if scripted["__kastTestApplyDefaultMutation"] == true {
                            unified_raw_result(
                                &server_workspace,
                                &request,
                                legacy_mutation_result,
                            )
                            .expect("default mutation side effect for scripted JSON-RPC error");
                        }
                        if let Some(path) = scripted["__kastTestRetainedArtifactPath"].as_str() {
                            use base64::{Engine as _, engine::general_purpose::STANDARD};
                            let contents = STANDARD
                                .decode(
                                    scripted["__kastTestRetainedArtifactBase64"]
                                        .as_str()
                                        .expect("retained artifact Base64"),
                                )
                                .expect("retained artifact bytes");
                            std::fs::write(path, contents).expect("retained backend artifact");
                        }
                        scripted
                    } else if let Some(result) = unified_raw_result(
                        &server_workspace,
                        &request,
                        legacy_mutation_result,
                    ) {
                        result
                    } else {
                        panic!(
                            "unexpected scripted method: {method}; next={scripted_method:?}"
                        );
                    };
                    if method == "raw/verify-mutation-postcondition"
                        && result.get("__kastTestJsonRpcError").is_none()
                    {
                        unified_semantic_verification_complete = true;
                    } else if method == "raw/inspect-mutation-scratch"
                        && unified_semantic_verification_complete
                    {
                        unified_session_complete = true;
                    }
                    result
                }
            };
            requests.push(request);
            idle_deadline = std::time::Instant::now()
                + if unified_session_active {
                    std::time::Duration::from_secs(1)
                } else {
                    std::time::Duration::from_secs(10)
                };
            let response = if let Some(error) = result.get("__kastTestJsonRpcError") {
                serde_json::json!({"jsonrpc":"2.0","id":1,"error":error})
            } else {
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            };
            if let Err(error) = writeln!(stream, "{}", response) {
                if error.kind() == std::io::ErrorKind::BrokenPipe {
                    return requests;
                }
                panic!("write scripted response: {error}");
            }
        }
        requests
    })
}

pub(crate) fn spawn_sequenced_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    responses: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical sequenced workspace");
    let listener = UnixListener::bind(socket_path).expect("bind sequenced backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            "indexer",
            "scripted-test",
        )]))
        .expect("descriptor json"),
    )
    .expect("descriptor");
    listener
        .set_nonblocking(true)
        .expect("nonblocking sequenced backend");

    thread::spawn(move || {
        let mut requests = Vec::with_capacity(responses.len());
        for (expected_method, result) in responses {
            let idle_deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
            let (mut stream, _) = loop {
                match listener.accept() {
                    Ok(connection) => break connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= idle_deadline {
                            return requests;
                        }
                        thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("accept sequenced client: {error}"),
                }
            };
            stream
                .set_nonblocking(false)
                .expect("blocking sequenced backend stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            assert_eq!(request["method"], expected_method, "scripted method order");
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write sequenced response");
            requests.push(request);
        }
        requests
    })
}

#[cfg(target_os = "macos")]
fn default_socket_path_for_test(workspace: &Path) -> PathBuf {
    use sha2::{Digest, Sha256};

    let normalized: PathBuf = workspace.components().collect();
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    std::env::temp_dir().join(format!(
        "kast-indexer-{}.sock",
        &hex::encode(digest)[0..12]
    ))
}

pub(crate) fn path_report_entry<'a>(
    report: &'a serde_json::Value,
    key: &str,
) -> &'a serde_json::Value {
    report["entries"]
        .as_array()
        .expect("path report entries")
        .iter()
        .find(|entry| entry["key"] == key)
        .unwrap_or_else(|| panic!("missing path report entry {key}: {report:#?}"))
}
