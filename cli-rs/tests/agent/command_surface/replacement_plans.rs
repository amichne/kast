fn replacement_sha256(bytes: &[u8]) -> String {
    use sha2::Digest as _;

    hex::encode(sha2::Sha256::digest(bytes))
}

fn replacement_utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

fn replacement_utf16_offset(value: &str, byte_offset: usize) -> usize {
    value[..byte_offset].encode_utf16().count()
}

fn replacement_dimensions() -> Value {
    json!([
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
        "SEMANTIC_GENERATION_UNCHANGED",
    ])
}

fn replacement_function_signature() -> Value {
    json!({
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
        "actual": false,
    })
}

fn exact_replacement_preview(workspace: &Path, source: &str, proposed: &str) -> Value {
    let declaration_file = workspace.join("Keywords.kt").display().to_string();
    let helper_file = workspace.join("Helpers.kt").display().to_string();
    let source_body = "\"old\"";
    let proposed_body = "\"😀\" + helper()";
    let source_body_start = replacement_utf16_offset(
        source,
        source.find(source_body).expect("source body occurrence"),
    );
    let source_body_end = source_body_start + replacement_utf16_len(source_body);
    let postimage = source.replacen(source_body, proposed_body, 1);
    let helper_start = replacement_utf16_offset(
        proposed_body,
        proposed_body.find("helper").expect("helper occurrence"),
    );
    let proposed_body_start = replacement_utf16_offset(
        proposed,
        proposed.find(proposed_body).expect("proposed body occurrence"),
    );
    let proposed_declaration_length = replacement_utf16_len(proposed.trim());
    let signature = replacement_function_signature();
    json!({
        "edit": {
            "filePath": declaration_file,
            "startOffset": source_body_start,
            "endOffset": source_body_end,
            "newText": proposed_body,
        },
        "proof": {
            "target": {
                "fqName": "io.example.OrderService.process",
                "kind": "FUNCTION",
                "declarationFile": declaration_file,
                "declarationStartOffset": 10,
                "containingType": "io.example.OrderService",
            },
            "requiredGeneration": 7,
            "sourceRange": {
                "filePath": declaration_file,
                "startOffset": source_body_start,
                "endOffset": source_body_end,
                "startLine": 1,
                "startColumn": 1,
                "preview": source_body,
            },
            "fileHashes": [{
                "filePath": declaration_file,
                "hash": replacement_sha256(source.as_bytes()),
            }],
            "compilerContext": {
                "modelGeneration": 1,
                "files": [{
                    "filePath": helper_file,
                    "sha256": replacement_sha256(b"fun helper() = \"ok\"\n"),
                }],
            },
            "oldSignature": signature,
            "proposedSignature": signature,
            "proposedDeclarationHash": replacement_sha256(proposed.as_bytes()),
            "proposedDeclarationLength": replacement_utf16_len(proposed),
            "proposedBodyHash": replacement_sha256(proposed_body.as_bytes()),
            "proposedBodyLength": replacement_utf16_len(proposed_body),
            "declarationSlice": {
                "startOffset": 0,
                "endOffset": proposed_declaration_length,
            },
            "proposedBodySlice": {
                "startOffset": proposed_body_start,
                "endOffset": proposed_body_start + replacement_utf16_len(proposed_body),
            },
            "evidence": {
                "type": "complete",
                "cardinality": {"type": "EXACT", "totalCount": 1},
                "dimensions": replacement_dimensions(),
            },
            "outboundReferences": [
                {
                    "relativeStartOffset": helper_start,
                    "relativeEndOffset": helper_start + replacement_utf16_len("helper"),
                    "sourceText": "helper",
                    "resolvedTarget": {
                        "type": "source",
                        "symbol": {
                            "fqName": "io.example.helper",
                            "kind": "FUNCTION",
                            "declarationFile": helper_file,
                            "declarationStartOffset": 0,
                        },
                    },
                    "provenance": "COMPILER",
                },
            ],
        },
        "fileImages": [exact_file_image_value(
            &declaration_file,
            source.as_bytes(),
            postimage.as_bytes(),
        )],
        "schemaVersion": api_schema_version(),
    })
}

fn run_symbol_replacement_preview(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    content_file: &Path,
) -> std::process::Output {
    kast(home, config_home)
        .args([
            "--output",
            "json",
            "agent",
            "replace-declaration",
            "--symbol",
            "io.example.OrderService.process",
            "--kind",
            "function",
            "--content-file",
            content_file.to_str().expect("replacement content path"),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("replacement preview")
}

#[test]
fn agent_replacement_preview_projects_exact_compiler_proof_and_inline_request() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("replacement.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let source = "class OrderService { fun process(): String = \"old\" }\n";
    let proposed = "fun process(): String = \"😀\" + helper()\n";
    std::fs::write(workspace.join("Keywords.kt"), source).expect("source fixture");
    std::fs::write(workspace.join("Helpers.kt"), "fun helper() = \"ok\"\n")
        .expect("helper fixture");
    let canonical_workspace = workspace.canonicalize().expect("canonical workspace");
    let content_file = temp.path().join("replacement.kt");
    std::fs::write(&content_file, proposed).expect("replacement fixture");
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
            (
                "raw/plan-replacement",
                exact_replacement_preview(&canonical_workspace, source, proposed),
            ),
        ],
    );

    let output = run_symbol_replacement_preview(&home, &config_home, &workspace, &content_file);

    assert!(
        output.status.success(),
        "replacement preview should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("replacement preview JSON");
    assert_eq!(stdout["method"], "agent/replace-declaration", "{stdout:#}");
    assert_eq!(
        stdout["result"]["execution"]["outcome"], "PLANNED_REPLACE_DECLARATION",
        "{stdout:#}",
    );
    let preview = &stdout["result"]["plan"]["preview"];
    assert_eq!(
        preview["proof"]["target"]["fqName"],
        "io.example.OrderService.process",
    );
    assert_eq!(preview["proof"]["requiredGeneration"], 7);
    assert_eq!(
        preview["proof"]["proposedDeclarationHash"],
        replacement_sha256(proposed.as_bytes()),
    );
    assert_eq!(
        preview["proof"]["proposedDeclarationLength"],
        replacement_utf16_len(proposed),
    );
    assert_eq!(
        preview["proof"]["proposedBodyHash"],
        replacement_sha256("\"😀\" + helper()".as_bytes()),
    );
    assert_eq!(
        preview["proof"]["proposedBodyLength"],
        replacement_utf16_len("\"😀\" + helper()"),
    );
    assert_eq!(
        preview["proof"]["declarationSlice"],
        json!({
            "startOffset": 0,
            "endOffset": replacement_utf16_len(proposed.trim()),
        }),
    );
    assert_eq!(
        preview["proof"]["proposedBodySlice"],
        json!({
            "startOffset": replacement_utf16_offset(proposed, proposed.find("\"😀\" + helper()").expect("body")),
            "endOffset": replacement_utf16_offset(proposed, proposed.find("\"😀\" + helper()").expect("body"))
                + replacement_utf16_len("\"😀\" + helper()"),
        }),
    );
    assert_eq!(
        preview["edit"]["newText"],
        "\"😀\" + helper()",
    );
    assert_ne!(replacement_utf16_len(proposed), proposed.len());
    assert_eq!(
        preview["proof"]["evidence"]["dimensions"],
        replacement_dimensions(),
    );
    assert_eq!(
        preview["proof"]["outboundReferences"][0]["resolvedTarget"]["type"],
        "source",
    );

    let requests = backend.join().expect("replacement backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[3]["method"], "raw/plan-replacement");
    assert_eq!(requests[3]["params"]["proposedDeclaration"], proposed);
    assert_eq!(requests[3]["params"]["target"], preview["proof"]["target"],);
    assert!(
        requests[3]["params"].get("contentFile").is_none(),
        "raw proof request must carry exact inline content: {requests:#?}",
    );
}

#[path = "cases/replacement_negative.rs"]
mod replacement_negative;
