fn exact_rename_plan_result() -> Value {
    let declaration_preimage = "\u{feff}a\r\nfun greet() = \"😀\"\nc\rd".as_bytes();
    let declaration_postimage = "\u{feff}a\r\nfun welcome() = \"😀\"\nc\rd".as_bytes();
    let usage_preimage = b"fun use() = greet()\n";
    let usage_postimage = b"fun use() = welcome()\n";
    let target = json!({
        "fqName": "sample.greet",
        "kind": "FUNCTION",
        "declarationFile": "/workspace/src/Sample.kt",
        "declarationStartOffset": 6
    });
    json!({
        "type": "KAST_AGENT_RENAME_PLAN",
        "applyRequired": true,
        "request": {
            "method": "symbol/rename",
            "params": {
                "type": "RENAME_BY_SYMBOL_REQUEST",
                "symbol": "sample.greet",
                "newName": "welcome"
            }
        },
        "preview": {
            "edits": [
                {
                    "filePath": "/workspace/src/Sample.kt",
                    "startOffset": 6,
                    "endOffset": 11,
                    "newText": "welcome"
                },
                {
                    "filePath": "/workspace/src/Usage.kt",
                    "startOffset": 12,
                    "endOffset": 17,
                    "newText": "welcome"
                }
            ],
            "fileHashes": [
                {
                    "filePath": "/workspace/src/Sample.kt",
                    "hash": exact_file_sha256(declaration_preimage)
                },
                {
                    "filePath": "/workspace/src/Usage.kt",
                    "hash": exact_file_sha256(usage_preimage)
                }
            ],
            "affectedFiles": [
                "/workspace/src/Sample.kt",
                "/workspace/src/Usage.kt"
            ],
            "proof": {
                "target": target,
                "requiredGeneration": 7,
                "evidence": {
                    "type": "COMPLETE",
                    "cardinality": {"type": "EXACT", "totalCount": 1},
                    "coverage": {
                        "type": "COMPLETE",
                        "identity": "COMPLETE",
                        "projectScope": "COMPLETE",
                        "sourceSetScope": "COMPLETE",
                        "indexFreshness": "COMPLETE",
                        "backend": "COMPLETE",
                        "requestedFamily": "COMPLETE",
                        "limitations": []
                    }
                },
                "occurrences": [{
                    "reference": {
                        "location": {
                            "filePath": "/workspace/src/Usage.kt",
                            "startOffset": 12,
                            "endOffset": 17,
                            "startLine": 3,
                            "startColumn": 9,
                            "preview": "greet()"
                        },
                        "containingSymbol": {"type": "TOP_LEVEL"}
                    },
                    "resolvedTarget": target,
                    "provenance": "COMPILER"
                }]
            },
            "fileImages": [
                {
                    "filePath": "/workspace/src/Sample.kt",
                    "preimage": {
                        "contentBase64": STANDARD_BASE64.encode(declaration_preimage),
                        "sha256": exact_file_sha256(declaration_preimage)
                    },
                    "postimage": {
                        "contentBase64": STANDARD_BASE64.encode(declaration_postimage),
                        "sha256": exact_file_sha256(declaration_postimage)
                    }
                },
                {
                    "filePath": "/workspace/src/Usage.kt",
                    "preimage": {
                        "contentBase64": STANDARD_BASE64.encode(usage_preimage),
                        "sha256": exact_file_sha256(usage_preimage)
                    },
                    "postimage": {
                        "contentBase64": STANDARD_BASE64.encode(usage_postimage),
                        "sha256": exact_file_sha256(usage_postimage)
                    }
                }
            ],
            "schemaVersion": 6
        }
    })
}

#[test]
fn rename_plan_projection_rejects_proofless_limited_malformed_and_inconsistent_evidence() {
    let valid = project_mutation_envelope(
        result_envelope("agent/rename".to_string(), exact_rename_plan_result()),
        AgentResultView::Compact,
    );
    let valid_result = valid.result.expect("valid rename projection");
    assert_eq!(
        valid_result["plan"]["preview"]["proof"]["requiredGeneration"],
        7
    );
    assert_eq!(
        valid_result["plan"]["preview"]["proof"]["occurrences"][0]["provenance"],
        "COMPILER"
    );

    let mut proofless = exact_rename_plan_result();
    proofless["preview"]
        .as_object_mut()
        .expect("preview object")
        .remove("proof");

    let mut limited = exact_rename_plan_result();
    limited["preview"]["proof"]["evidence"] = json!({
        "type": "LIMITED",
        "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 1},
        "coverage": {
            "type": "LIMITED",
            "identity": "COMPLETE",
            "projectScope": "COMPLETE",
            "sourceSetScope": "COMPLETE",
            "indexFreshness": "COMPLETE",
            "backend": "PARTIAL",
            "requestedFamily": "PARTIAL",
            "limitations": ["BACKEND_INCOMPLETE"]
        }
    });

    let mut limited_coverage = exact_rename_plan_result();
    limited_coverage["preview"]["proof"]["evidence"]["coverage"] = json!({
        "type": "LIMITED",
        "identity": "COMPLETE",
        "projectScope": "COMPLETE",
        "sourceSetScope": "COMPLETE",
        "indexFreshness": "COMPLETE",
        "backend": "PARTIAL",
        "requestedFamily": "PARTIAL",
        "limitations": ["BACKEND_INCOMPLETE"]
    });

    let mut malformed = exact_rename_plan_result();
    malformed["preview"]["proof"]["requiredGeneration"] = json!(-1);

    let mut unknown_nested_field = exact_rename_plan_result();
    unknown_nested_field["preview"]["proof"]["target"]["selectorHandle"] =
        json!("opaque-but-not-part-of-symbol-identity");

    let mut inconsistent_cardinality = exact_rename_plan_result();
    inconsistent_cardinality["preview"]["proof"]["evidence"]["cardinality"]["totalCount"] =
        json!(2);

    let mut inconsistent_target = exact_rename_plan_result();
    inconsistent_target["preview"]["proof"]["occurrences"][0]["resolvedTarget"]["fqName"] =
        json!("sample.other");

    let mut inconsistent_edits = exact_rename_plan_result();
    inconsistent_edits["preview"]["edits"][1]["startOffset"] = json!(32);

    let mut missing_file_images = exact_rename_plan_result();
    missing_file_images["preview"]
        .as_object_mut()
        .expect("preview object")
        .remove("fileImages");

    let mut malformed_base64 = exact_rename_plan_result();
    malformed_base64["preview"]["fileImages"][0]["preimage"]["contentBase64"] = json!("not base64");

    let mut duplicate_file_image = exact_rename_plan_result();
    let duplicate = duplicate_file_image["preview"]["fileImages"][0].clone();
    duplicate_file_image["preview"]["fileImages"]
        .as_array_mut()
        .expect("file images")
        .push(duplicate);

    let mut unknown_image_field = exact_rename_plan_result();
    unknown_image_field["preview"]["fileImages"][0]["source"] = json!("untrusted");

    let mut legacy_preimage_mismatch = exact_rename_plan_result();
    legacy_preimage_mismatch["preview"]["fileHashes"][0]["hash"] = json!("0".repeat(64));

    let mut unchanged_image = exact_rename_plan_result();
    let unchanged_preimage = unchanged_image["preview"]["fileImages"][0]["preimage"].clone();
    unchanged_image["preview"]["fileImages"][0]["postimage"] = unchanged_preimage;

    let mut valid_hash_but_inconsistent_postimage = exact_rename_plan_result();
    let unrelated = b"unrelated but internally hashed bytes\n";
    valid_hash_but_inconsistent_postimage["preview"]["fileImages"][0]["postimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(unrelated),
        "sha256": exact_file_sha256(unrelated)
    });

    for (case, result) in [
        ("proof-less", proofless),
        ("limited", limited),
        ("limited-coverage", limited_coverage),
        ("malformed", malformed),
        ("unknown-nested-field", unknown_nested_field),
        ("cardinality-inconsistent", inconsistent_cardinality),
        ("target-inconsistent", inconsistent_target),
        ("edit-inconsistent", inconsistent_edits),
        ("missing-file-images", missing_file_images),
        ("malformed-base64", malformed_base64),
        ("duplicate-file-image", duplicate_file_image),
        ("unknown-image-field", unknown_image_field),
        ("legacy-preimage-mismatch", legacy_preimage_mismatch),
        ("unchanged-image", unchanged_image),
        (
            "postimage-edit-inconsistent",
            valid_hash_but_inconsistent_postimage,
        ),
    ] {
        let projected = project_mutation_envelope(
            result_envelope("agent/rename".to_string(), result),
            AgentResultView::Compact,
        );

        assert!(!projected.ok, "{case} rename proof was accepted");
        assert_eq!(
            projected.error.as_ref().map(|error| error.code.as_str()),
            Some("AGENT_RESULT_INVALID"),
            "{case}"
        );
    }
}

#[path = "mutation_and_evidence/semantic_evidence.rs"]
mod semantic_evidence;
#[path = "mutation_and_evidence/projection_consistency.rs"]
mod projection_consistency;
