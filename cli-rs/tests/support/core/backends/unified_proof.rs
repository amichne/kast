use super::*;

pub(super) fn unified_mutation_capabilities() -> Vec<&'static str> {
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

pub(super) fn unified_source_sha256(bytes: &[u8]) -> String {
    use sha2::{Digest as _, Sha256};
    hex::encode(Sha256::digest(bytes))
}

pub(super) fn unified_base64(bytes: &[u8]) -> String {
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    STANDARD.encode(bytes)
}

pub(super) fn unified_add_file_plan(
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
        "schemaVersion": 7,
    })
}

pub(super) fn unified_exact_observation(
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

pub(super) fn unified_scratch_observation(
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

pub(super) fn unified_scratch_inspect(workspace: &Path, params: &serde_json::Value) -> serde_json::Value {
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
        "schemaVersion": 7,
    })
}

pub(super) fn unified_scratch_recover(workspace: &Path, params: &serde_json::Value) -> serde_json::Value {
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
        "schemaVersion": 7,
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

pub(super) fn assert_unified_mutation_scratch(
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
