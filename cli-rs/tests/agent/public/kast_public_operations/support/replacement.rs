use super::*;

pub(crate) struct ReplacementFixture {
    pub(crate) preview: Value,
    pub(crate) proposed: String,
    pub(crate) postimage: Vec<u8>,
    selector_identity: Value,
}

pub(crate) fn replacement_fixture(target: &Path, preimage: &[u8]) -> ReplacementFixture {
    let source = std::str::from_utf8(preimage).expect("UTF-8 replacement preimage");
    assert!(!source.is_empty(), "replacement preimage must be present");
    let proposed = "fun recoveredReplacement() = 2\n".to_string();
    let target_identity = json!({
        "fqName": "sample.recoveredReplacement",
        "kind": "FUNCTION",
        "declarationFile": target,
        "declarationStartOffset": 0,
    });
    let signature = json!({
        "type": "function",
        "name": "recoveredReplacement",
        "receiverType": null,
        "contextReceiverTypes": [],
        "typeParameters": [],
        "valueParameters": [],
        "returnType": "kotlin.Int",
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
    });
    let source_length = source.encode_utf16().count();
    let proposed_length = proposed.encode_utf16().count();
    let preimage_sha256 = source_sha256(preimage);
    let postimage = proposed.as_bytes().to_vec();
    let preview = json!({
        "edit": {
            "filePath": target,
            "startOffset": 0,
            "endOffset": source_length,
            "newText": proposed,
        },
        "proof": {
            "target": target_identity,
            "requiredGeneration": 7,
            "sourceRange": {
                "filePath": target,
                "startOffset": 0,
                "endOffset": source_length,
                "startLine": 1,
                "startColumn": 1,
                "preview": source,
            },
            "fileHashes": [{"filePath": target, "hash": preimage_sha256}],
            "oldSignature": signature,
            "proposedSignature": signature,
            "proposedDeclarationHash": source_sha256(proposed.as_bytes()),
            "proposedDeclarationLength": proposed_length,
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
                    "SEMANTIC_GENERATION_UNCHANGED",
                ],
            },
            "outboundReferences": [],
        },
        "fileImages": [{
            "filePath": target,
            "preimage": {
                "contentBase64": STANDARD_BASE64.encode(preimage),
                "sha256": preimage_sha256,
            },
            "postimage": {
                "contentBase64": STANDARD_BASE64.encode(&postimage),
                "sha256": source_sha256(&postimage),
            },
        }],
        "schemaVersion": 7,
    });
    ReplacementFixture {
        preview,
        proposed,
        postimage,
        selector_identity: json!({"type": "AVAILABLE", "identity": target_identity}),
    }
}

pub(crate) fn plan_replacement(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket: &Path,
    fixture: &ReplacementFixture,
) -> String {
    let selector = "ksh1.issued-recovery-replacement-selector";
    let backend = spawn_scripted_indexer_backend(
        home,
        config_home,
        workspace,
        socket,
        vec![
            ("selector/identity", fixture.selector_identity.clone()),
            ("selector/identity", fixture.selector_identity.clone()),
            ("raw/plan-replacement", fixture.preview.clone()),
        ],
    );
    let mut command = installed_public_kast(binary, home, config_home, workspace);
    command.args(["change", "plan", "replace", "--selector", selector]);
    let output = run_with_stdin(command, &fixture.proposed);
    backend.join().expect("replacement planning backend");
    assert!(
        output.status.success(),
        "replacement plan failed: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    decode(&output)["planId"]
        .as_str()
        .expect("replacement plan id")
        .to_string()
}
