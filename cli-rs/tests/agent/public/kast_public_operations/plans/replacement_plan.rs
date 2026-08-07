use super::*;

#[test]
fn change_replace_persists_restart_safe_exact_file_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");

    let source = "fun process(): String = \"old\"\r\n";
    let source_document = source.replace("\r\n", "\n");
    let proposed = "fun process(): String = \"new 🚀\"\n";
    let mut preimage = b"\xef\xbb\xbf".to_vec();
    preimage.extend_from_slice(source.as_bytes());
    let mut postimage = b"\xef\xbb\xbf".to_vec();
    postimage.extend_from_slice(proposed.replace('\n', "\r\n").as_bytes());
    std::fs::write(workspace.join("Keywords.kt"), &preimage).expect("source");

    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("Keywords.kt");
    let symbol = "io.example.OrderService.process";
    let selector = "ksh1.issued-replacement-selector";
    let target = json!({
        "fqName": symbol,
        "kind": "FUNCTION",
        "declarationFile": declaration_file,
        "declarationStartOffset": 4,
        "containingType": "io.example.OrderService"
    });
    let signature = json!({
        "type": "function",
        "name": "process",
        "receiverType": null,
        "contextReceiverTypes": [],
        "typeParameters": [],
        "valueParameters": [],
        "returnType": "kotlin.String",
        "visibility": "PUBLIC",
        "modality": "FINAL",
        "hasStableParameterNames": true,
        "suspend": false,
        "operator": false,
        "inline": false,
        "override": false,
        "infix": false,
        "static": false,
        "tailrec": false,
        "external": false,
        "expect": false,
        "actual": false
    });
    let proof = json!({
        "target": target,
        "requiredGeneration": 7,
        "sourceRange": {
            "filePath": declaration_file,
            "startOffset": 0,
            "endOffset": source_document.encode_utf16().count(),
            "startLine": 1,
            "startColumn": 1,
            "preview": source_document
        },
        "fileHashes": [{
            "filePath": declaration_file,
            "hash": source_sha256(&preimage)
        }],
        "oldSignature": signature,
        "proposedSignature": signature,
        "proposedDeclarationHash": source_sha256(proposed.as_bytes()),
        "proposedDeclarationLength": proposed.encode_utf16().count(),
        "declarationSlice": {
            "startOffset": 0,
            "endOffset": proposed.trim_end().encode_utf16().count(),
        },
        "evidence": {
            "type": "complete",
            "cardinality": {"type": "EXACT", "totalCount": 0},
            "dimensions": [
                "EXACT_TARGET_IDENTITY",
                "SUPPORTED_TARGET_KIND",
                "SINGLE_SUPPORTED_PROPOSED_DECLARATION",
                "COMPILER_SIGNATURE_EQUAL",
                "PROPOSED_PSI_TRAVERSAL_EXHAUSTIVE",
                "EVERY_REFERENCE_COMPILER_RESOLVED",
                "EVERY_REFERENCE_TARGET_MATCHED",
                "EVERY_CALL_EXACT",
                "NO_UNSUPPORTED_REFERENCE_KIND",
                "EXACT_OUTBOUND_CARDINALITY",
                "SOURCE_CONTEXT_HASH_BOUND",
                "SEMANTIC_GENERATION_UNCHANGED"
            ]
        },
        "outboundReferences": []
    });
    let edit = json!({
        "filePath": declaration_file,
        "startOffset": 0,
        "endOffset": source_document.encode_utf16().count(),
        "newText": proposed
    });
    let file_images = json!([{
        "filePath": declaration_file,
        "preimage": {
            "contentBase64": STANDARD_BASE64.encode(&preimage),
            "sha256": source_sha256(&preimage)
        },
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(&postimage),
            "sha256": source_sha256(&postimage)
        }
    }]);

    let selector_identity = json!({"type": "AVAILABLE", "identity": target});
    let replacement_preview = json!({
        "edit": edit,
        "proof": proof,
        "fileImages": file_images.clone(),
        "schemaVersion": 6
    });
    let socket = fixture.path().join("replacement-authority.sock");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            ("selector/identity", selector_identity.clone()),
            ("selector/identity", selector_identity.clone()),
            ("raw/plan-replacement", replacement_preview.clone()),
        ],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut change = installed_public_kast(&binary, &home, &config_home, &workspace);
    change.args(["change", "plan", "replace", "--selector", selector]);
    let change = run_with_stdin(change, proposed);
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public replacement plan must redact exact image bytes"
    );
    let planning_requests = backend.join().expect("replacement planning backend");
    assert_selector_forwarding(&planning_requests, selector, "REPLACE_DECLARATION");

    let public = decode(&change);
    assert_eq!(public["selector"], selector, "{public:#}");
    assert_eq!(
        public["plan"]["preview"]["proof"]["oldSignature"]["type"], "function",
        "public replacement proof must retain its semantic discriminator"
    );
    let plan_id = public["planId"].as_str().expect("plan id").to_string();
    let plan_directory = home.join(".local/share/kast/state/agent-plans");
    let stored: Value = serde_json::from_slice(
        &std::fs::read(plan_directory.join(format!("{plan_id}.json")))
            .expect("restart-safe stored replacement plan"),
    )
    .expect("stored replacement JSON");
    assert_eq!(
        stored["operation"],
        json!({
            "operation": "replace",
            "authority": {
                "target": target,
                "proof": proof,
                "edits": [edit],
                "fileImages": file_images
            }
        }),
        "replacement storage must retain exact write authority, not a symbol lookup request"
    );
    assert!(stored["operation"].get("symbol").is_none());
    assert!(stored["operation"].get("selectorHandle").is_none());
    assert_eq!(stored["contentSha256"], source_sha256(proposed.as_bytes()));
    assert_eq!(
        std::fs::read(plan_directory.join(format!("{plan_id}.content")))
            .expect("stored proposed replacement content"),
        proposed.as_bytes()
    );

    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("replacement-apply.sock"),
        vec![("raw/plan-replacement", replacement_preview.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified replacement apply");
    assert!(applied.status.success(), "{applied:?}");
    let verified_receipt = decode(&applied);
    assert_eq!(
        verified_receipt["outcome"], "VERIFIED",
        "{verified_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&declaration_file).expect("replacement postimage"),
        postimage
    );
    let apply_requests = apply_backend.join().expect("replacement apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        1
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified replacement replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);

    std::fs::write(&declaration_file, &preimage).expect("reset replacement preimage");
    let tamper_plan_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("replacement-tamper-plan.sock"),
        vec![
            ("selector/identity", selector_identity.clone()),
            ("selector/identity", selector_identity),
            ("raw/plan-replacement", replacement_preview),
        ],
    );
    let mut tamper_change = installed_public_kast(&binary, &home, &config_home, &workspace);
    tamper_change.args(["change", "plan", "replace", "--selector", selector]);
    let tamper_change = run_with_stdin(tamper_change, proposed);
    assert!(tamper_change.status.success(), "{tamper_change:?}");
    tamper_plan_backend
        .join()
        .expect("replacement tamper planner");
    let plan_id = decode(&tamper_change)["planId"]
        .as_str()
        .expect("tamper plan id")
        .to_string();
    let stored: Value = serde_json::from_slice(
        &std::fs::read(plan_directory.join(format!("{plan_id}.json")))
            .expect("stored tamper replacement plan"),
    )
    .expect("stored tamper replacement JSON");

    let tampered_proposed = "fun process(): String = \"tampered\"\n";
    let mut tampered_postimage = b"\xef\xbb\xbf".to_vec();
    tampered_postimage.extend_from_slice(tampered_proposed.replace('\n', "\r\n").as_bytes());
    let mut tampered = stored;
    tampered["operation"]["authority"]["edits"][0]["newText"] = json!(tampered_proposed);
    tampered["operation"]["authority"]["proof"]["proposedDeclarationHash"] =
        json!(source_sha256(tampered_proposed.as_bytes()));
    tampered["operation"]["authority"]["proof"]["proposedDeclarationLength"] =
        json!(tampered_proposed.encode_utf16().count());
    tampered["operation"]["authority"]["fileImages"][0]["postimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(&tampered_postimage),
        "sha256": source_sha256(&tampered_postimage)
    });
    let plan_path = plan_directory.join(format!("{plan_id}.json"));
    let mut encoded = serde_json::to_vec(&tampered).expect("tampered plan JSON");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("rewrite private plan for restart tamper proof");

    let tampered_apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("restart with tampered replacement authority");
    assert_eq!(tampered_apply.status.code(), Some(1), "{tampered_apply:?}");
    assert_eq!(
        decode(&tampered_apply)["error"],
        "KAST_PLAN_INVALID",
        "tampered authority must fail before private content or runtime use"
    );
}
