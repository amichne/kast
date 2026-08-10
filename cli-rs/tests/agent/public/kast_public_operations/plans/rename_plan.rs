use super::*;

#[test]
fn change_rename_persists_restart_safe_exact_file_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");

    let declaration_file = workspace.join("Keywords.kt");
    let reference_file = workspace.join("Usage.kt");
    let declaration_preimage = b"\xef\xbb\xbfpackage io.example\r\nclass OrderService {\r\n    fun process() = \"rocket \xf0\x9f\x9a\x80\"\r\n}\r\n".to_vec();
    let reference_preimage =
        b"package io.example\r\nfun use(service: OrderService) = service.process()\r\n".to_vec();
    std::fs::write(&declaration_file, &declaration_preimage).expect("declaration source");
    std::fs::write(&reference_file, &reference_preimage).expect("reference source");

    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("Keywords.kt");
    let reference_file = workspace.join("Usage.kt");
    let original_symbol = "io.example.OrderService.process";
    let selector = "ksh1.issued-rename-selector";
    let new_name = "processSafely";
    let declaration_text =
        std::str::from_utf8(&declaration_preimage).expect("UTF-8 declaration source");
    let reference_text = std::str::from_utf8(&reference_preimage).expect("UTF-8 reference source");
    let declaration_byte_start = declaration_text.find("process").expect("declaration name");
    let reference_byte_start = reference_text.find("process").expect("reference name");
    let declaration_document = declaration_text
        .strip_prefix('\u{feff}')
        .unwrap_or(declaration_text)
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let reference_document = reference_text
        .strip_prefix('\u{feff}')
        .unwrap_or(reference_text)
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let declaration_document_start = declaration_document
        .find("process")
        .expect("declaration document name");
    let reference_document_start = reference_document
        .find("process")
        .expect("reference document name");
    let declaration_start = declaration_document[..declaration_document_start]
        .encode_utf16()
        .count() as u32;
    let reference_start = reference_document[..reference_document_start]
        .encode_utf16()
        .count() as u32;
    let original_name_length = "process".encode_utf16().count() as u32;

    let mut declaration_postimage = declaration_preimage.clone();
    declaration_postimage.splice(
        declaration_byte_start..declaration_byte_start + "process".len(),
        new_name.bytes(),
    );
    let mut reference_postimage = reference_preimage.clone();
    reference_postimage.splice(
        reference_byte_start..reference_byte_start + "process".len(),
        new_name.bytes(),
    );

    let target = json!({
        "fqName": original_symbol,
        "kind": "FUNCTION",
        "declarationFile": declaration_file,
        "declarationStartOffset": declaration_start,
        "containingType": "io.example.OrderService"
    });
    let occurrence = json!({
        "reference": {
            "location": {
                "filePath": reference_file,
                "startOffset": reference_start,
                "endOffset": reference_start + original_name_length,
                "startLine": 2,
                "startColumn": 42,
                "preview": "service.process()"
            },
            "containingSymbol": {"type": "TOP_LEVEL"}
        },
        "resolvedTarget": target,
        "provenance": "COMPILER"
    });
    let proof = json!({
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
        "occurrences": [occurrence]
    });
    let edits = json!([
        {
            "filePath": declaration_file,
            "startOffset": declaration_start,
            "endOffset": declaration_start + original_name_length,
            "newText": new_name
        },
        {
            "filePath": reference_file,
            "startOffset": reference_start,
            "endOffset": reference_start + original_name_length,
            "newText": new_name
        }
    ]);
    let file_images = json!([
        {
            "filePath": declaration_file,
            "preimage": {
                "contentBase64": STANDARD_BASE64.encode(&declaration_preimage),
                "sha256": source_sha256(&declaration_preimage)
            },
            "postimage": {
                "contentBase64": STANDARD_BASE64.encode(&declaration_postimage),
                "sha256": source_sha256(&declaration_postimage)
            }
        },
        {
            "filePath": reference_file,
            "preimage": {
                "contentBase64": STANDARD_BASE64.encode(&reference_preimage),
                "sha256": source_sha256(&reference_preimage)
            },
            "postimage": {
                "contentBase64": STANDARD_BASE64.encode(&reference_postimage),
                "sha256": source_sha256(&reference_postimage)
            }
        }
    ]);

    let selector_identity = json!({"type": "AVAILABLE", "identity": target});
    let rename_preview = json!({
        "edits": edits,
        "fileHashes": [
            {
                "filePath": declaration_file,
                "hash": source_sha256(&declaration_preimage)
            },
            {
                "filePath": reference_file,
                "hash": source_sha256(&reference_preimage)
            }
        ],
        "affectedFiles": [declaration_file, reference_file],
        "proof": proof,
        "fileImages": file_images.clone(),
        "schemaVersion": 7
    });
    let socket = fixture.path().join("rename-authority.sock");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            ("selector/identity", selector_identity.clone()),
            ("selector/identity", selector_identity.clone()),
            ("raw/rename", rename_preview.clone()),
        ],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let change = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args([
            "change",
            "plan",
            "rename",
            "--selector",
            selector,
            "--name",
            new_name,
        ])
        .output()
        .expect("persist rename plan");
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public rename plan must redact exact image bytes"
    );
    let planning_requests = backend.join().expect("rename planning backend");
    assert_selector_forwarding(&planning_requests, selector, "RENAME");

    let public = decode(&change);
    assert_eq!(public["selector"], selector, "{public:#}");
    assert_eq!(
        public["plan"]["preview"]["proof"]["occurrences"][0]["reference"]["containingSymbol"]["type"],
        "TOP_LEVEL",
        "public rename proof must retain its semantic discriminator"
    );
    let plan_id = public["planId"].as_str().expect("plan id").to_string();
    let stored: Value = serde_json::from_slice(
        &std::fs::read(
            home.join(".local/share/kast/state/agent-plans")
                .join(format!("{plan_id}.json")),
        )
        .expect("restart-safe stored rename plan"),
    )
    .expect("stored rename JSON");
    let expected_operation = json!({
        "operation": "rename",
        "authority": {
            "target": target,
            "proof": proof,
            "edits": edits,
            "fileImages": file_images
        }
    });
    assert_eq!(
        stored["operation"], expected_operation,
        "rename storage must retain exact write authority, not the symbol lookup request"
    );
    assert!(stored["operation"].get("symbol").is_none());
    assert!(stored["operation"].get("newName").is_none());
    for image in stored["operation"]["authority"]["fileImages"]
        .as_array()
        .expect("exact file images")
    {
        for state in ["preimage", "postimage"] {
            let hash = image[state]["sha256"].as_str().expect("image SHA-256");
            assert_eq!(hash.len(), 64, "{state} SHA-256: {hash}");
            assert!(
                hash.bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
                "{state} SHA-256 must be lowercase hexadecimal: {hash}"
            );
        }
    }

    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-apply.sock"),
        vec![("raw/rename", rename_preview.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified rename apply");
    assert!(applied.status.success(), "{applied:?}");
    let verified_receipt = decode(&applied);
    assert_eq!(
        verified_receipt["outcome"], "VERIFIED",
        "{verified_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&declaration_file).expect("renamed declaration"),
        declaration_postimage
    );
    assert_eq!(
        std::fs::read(&reference_file).expect("renamed reference"),
        reference_postimage
    );
    let apply_requests = apply_backend.join().expect("rename apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        2,
        "rename must write both exact transitions once"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified rename replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);

    std::fs::write(&declaration_file, &declaration_preimage).expect("reset declaration preimage");
    std::fs::write(&reference_file, &reference_preimage).expect("reset reference preimage");
    let restart_plan_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-restart-plan.sock"),
        vec![
            ("selector/identity", selector_identity.clone()),
            ("selector/identity", selector_identity),
            ("raw/rename", rename_preview.clone()),
        ],
    );
    let restart_change = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args([
            "change",
            "plan",
            "rename",
            "--selector",
            selector,
            "--name",
            new_name,
        ])
        .output()
        .expect("persist restart rename plan");
    assert!(restart_change.status.success(), "{restart_change:?}");
    restart_plan_backend.join().expect("restart rename planner");
    let restart_plan_id = decode(&restart_change)["planId"]
        .as_str()
        .expect("restart rename plan id")
        .to_string();

    let interrupted_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-interrupted-apply.sock"),
        vec![("raw/rename", rename_preview)],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_WRITE_1")
        .args(["change", "apply", "--plan-id", &restart_plan_id])
        .output()
        .expect("interrupt multi-file rename");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    assert_eq!(decode(&interrupted)["outcome"], "RECOVERY_REQUIRED");
    assert_eq!(
        std::fs::read(&declaration_file).expect("first postimage"),
        declaration_postimage
    );
    assert_eq!(
        std::fs::read(&reference_file).expect("second preimage"),
        reference_preimage
    );
    interrupted_backend
        .join()
        .expect("interrupted rename backend");

    let recovery_shutdown = fixture.path().join("rename-mixed-recovery.shutdown");
    let recovery_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-mixed-recovery.sock"),
        &recovery_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &restart_plan_id])
        .output()
        .expect("restart mixed rename recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let rolled_back_receipt = decode(&recovered);
    assert_eq!(
        rolled_back_receipt["outcome"], "ROLLED_BACK",
        "{rolled_back_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&declaration_file).expect("restored declaration"),
        declaration_preimage
    );
    assert_eq!(
        std::fs::read(&reference_file).expect("retained reference"),
        reference_preimage
    );
    std::fs::write(&recovery_shutdown, "stop\n").expect("stop mixed recovery backend");
    let recovery_requests = recovery_backend
        .join()
        .expect("mixed rename recovery backend");
    assert_eq!(
        recovery_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        1,
        "mixed recovery must reverse only the written transition"
    );
    let rollback_replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &restart_plan_id])
        .output()
        .expect("rolled-back rename replay");
    assert_eq!(
        rollback_replay.status.code(),
        Some(1),
        "{rollback_replay:?}"
    );
    assert_eq!(decode(&rollback_replay), rolled_back_receipt);
}
