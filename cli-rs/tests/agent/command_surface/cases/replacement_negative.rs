use super::*;

#[test]
fn agent_replacement_preview_rejects_non_exact_or_inconsistent_proof() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let source = "class OrderService { fun process(): String = \"old\" }\n";
    let proposed = "fun process(): String = \"😀\" + helper()\n";
    std::fs::write(workspace.join("Keywords.kt"), source).expect("source fixture");
    std::fs::write(workspace.join("Helpers.kt"), "fun helper() = \"ok\"\n")
        .expect("helper fixture");
    let canonical_workspace = workspace.canonicalize().expect("canonical workspace");
    let content_file = temp.path().join("replacement.kt");
    std::fs::write(&content_file, proposed).expect("replacement fixture");
    let valid = exact_replacement_preview(&canonical_workspace, source, proposed);

    let mut proofless = valid.clone();
    proofless.as_object_mut().expect("preview").remove("proof");

    let mut limited = valid.clone();
    limited["proof"]["evidence"] = json!({
        "type": "limited",
        "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 2},
        "dimensions": ["EXACT_TARGET_IDENTITY"],
    });

    let mut unknown_field = valid.clone();
    unknown_field["proof"]["target"]["selectorHandle"] = json!("not-part-of-identity");

    let mut uppercase_file_hash = valid.clone();
    uppercase_file_hash["proof"]["fileHashes"][0]["hash"] = json!("A".repeat(64));

    let mut proposed_hash_mismatch = valid.clone();
    proposed_hash_mismatch["proof"]["proposedDeclarationHash"] = json!("0".repeat(64));

    let mut missing_declaration_slice = valid.clone();
    missing_declaration_slice["proof"]
        .as_object_mut()
        .expect("proof")
        .remove("declarationSlice");

    let mut declaration_slice_includes_envelope = valid.clone();
    declaration_slice_includes_envelope["proof"]["declarationSlice"]["endOffset"] =
        json!(replacement_utf16_len(proposed));

    let mut signature_mismatch = valid.clone();
    signature_mismatch["proof"]["proposedSignature"]["returnType"] = json!("kotlin.Int");

    let mut cardinality_mismatch = valid.clone();
    cardinality_mismatch["proof"]["evidence"]["cardinality"]["totalCount"] = json!(3);

    let mut edit_mismatch = valid.clone();
    edit_mismatch["edit"]["startOffset"] = json!(1);

    let mut target_mismatch = valid.clone();
    target_mismatch["proof"]["target"]["fqName"] = json!("io.example.Other.process");

    let mut source_text_mismatch = valid.clone();
    source_text_mismatch["proof"]["outboundReferences"][0]["sourceText"] = json!("helper");

    let mut duplicate_ranges = valid.clone();
    let first = duplicate_ranges["proof"]["outboundReferences"][0].clone();
    duplicate_ranges["proof"]["outboundReferences"][1]["relativeStartOffset"] =
        first["relativeStartOffset"].clone();
    duplicate_ranges["proof"]["outboundReferences"][1]["relativeEndOffset"] =
        first["relativeEndOffset"].clone();
    duplicate_ranges["proof"]["outboundReferences"][1]["sourceText"] = first["sourceText"].clone();

    let mut missing_file_images = valid.clone();
    missing_file_images
        .as_object_mut()
        .expect("preview")
        .remove("fileImages");

    let mut malformed_base64 = valid.clone();
    malformed_base64["fileImages"][0]["preimage"]["contentBase64"] = json!("not base64");

    let mut duplicate_file_image = valid.clone();
    let duplicate = duplicate_file_image["fileImages"][0].clone();
    duplicate_file_image["fileImages"]
        .as_array_mut()
        .expect("file images")
        .push(duplicate);

    let mut preimage_hash_mismatch = valid.clone();
    preimage_hash_mismatch["proof"]["fileHashes"][0]["hash"] = json!("0".repeat(64));

    let mut unknown_image_field = valid.clone();
    unknown_image_field["fileImages"][0]["source"] = json!("untrusted");

    let mut inconsistent_postimage = valid.clone();
    let unrelated = b"unrelated but internally hashed replacement bytes\n";
    inconsistent_postimage["fileImages"][0]["postimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(unrelated),
        "sha256": replacement_sha256(unrelated),
    });

    let cases = [
        ("proofless", proofless),
        ("limited", limited),
        ("unknown-field", unknown_field),
        ("uppercase-file-hash", uppercase_file_hash),
        ("proposed-hash-mismatch", proposed_hash_mismatch),
        ("missing-declaration-slice", missing_declaration_slice),
        (
            "declaration-slice-includes-envelope",
            declaration_slice_includes_envelope,
        ),
        ("signature-mismatch", signature_mismatch),
        ("cardinality-mismatch", cardinality_mismatch),
        ("edit-mismatch", edit_mismatch),
        ("target-mismatch", target_mismatch),
        ("source-text-mismatch", source_text_mismatch),
        ("duplicate-ranges", duplicate_ranges),
        ("missing-file-images", missing_file_images),
        ("malformed-base64", malformed_base64),
        ("duplicate-file-image", duplicate_file_image),
        ("preimage-hash-mismatch", preimage_hash_mismatch),
        ("unknown-image-field", unknown_image_field),
        ("inconsistent-postimage", inconsistent_postimage),
    ];

    for (index, (case, preview)) in cases.into_iter().enumerate() {
        let socket_path = temp.path().join(format!("replacement-{index}.sock"));
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &socket_path,
            vec![
                (
                    "symbol/resolve",
                    symbol_result(&workspace, "io.example.OrderService.process"),
                ),
                ("raw/plan-replacement", preview),
            ],
        );

        let output = run_symbol_replacement_preview(&home, &config_home, &workspace, &content_file);

        assert!(
            !output.status.success(),
            "{case} replacement proof should fail closed: stdout={}",
            String::from_utf8_lossy(&output.stdout),
        );
        let stdout: Value = serde_json::from_slice(&output.stdout)
            .unwrap_or_else(|error| panic!("{case} failure JSON: {error}"));
        assert_eq!(
            stdout["error"]["code"], "INVALID_REPLACEMENT_PREVIEW",
            "{case}: {stdout:#}",
        );
        backend.join().expect("invalid replacement backend");
    }
}

#[test]
fn agent_replacement_preview_rejects_non_utf8_content_before_resolution() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let content_file = temp.path().join("replacement.kt");
    std::fs::write(&content_file, [0xff, 0xfe]).expect("invalid UTF-8 fixture");

    let output = run_symbol_replacement_preview(&home, &config_home, &workspace, &content_file);

    assert!(
        !output.status.success(),
        "non-UTF-8 replacement was accepted"
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("content failure JSON");
    assert_eq!(stdout["error"]["code"], "INVALID_REPLACEMENT_CONTENT");
    assert!(
        stdout["error"]["message"]
            .as_str()
            .is_some_and(|message| message.contains("exact UTF-8")),
        "{stdout:#}",
    );
}
