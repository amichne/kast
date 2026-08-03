use base64::{Engine as _, engine::general_purpose::STANDARD as STANDARD_BASE64};
use serde_json::{Value, json};
use sha2::{Digest as _, Sha256};
use support::metrics::seed_source_index;
use support::*;

fn symbol_result(workspace: &Path, fq_name: &str) -> Value {
    json!({
        "type": "RESOLVE_SUCCESS",
        "ok": true,
        "source": "compiler",
        "symbol": {
            "fqName": fq_name,
            "kind": "FUNCTION",
            "containingType": "io.example.OrderService",
            "location": {
                "filePath": workspace.join("Keywords.kt").display().to_string(),
                "startOffset": 10,
                "endOffset": 17,
                "startLine": 1,
                "startColumn": 1,
                "preview": "fun when()"
            }
        }
    })
}

fn exact_file_image_value(file_path: &str, preimage: &[u8], postimage: &[u8]) -> Value {
    json!({
        "filePath": file_path,
        "preimage": {
            "contentBase64": STANDARD_BASE64.encode(preimage),
            "sha256": hex::encode(Sha256::digest(preimage)),
        },
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(postimage),
            "sha256": hex::encode(Sha256::digest(postimage)),
        },
    })
}

fn rename_preview(workspace: &Path, new_name: &str) -> Value {
    let file_path = workspace.join("Keywords.kt").display().to_string();
    let preimage = b"0123456789process\n";
    let postimage = format!("0123456789{new_name}\n");
    json!({
        "edits": [{
            "filePath": file_path,
            "startOffset": 10,
            "endOffset": 17,
            "newText": new_name,
        }],
        "fileHashes": [{
            "filePath": file_path,
            "hash": hex::encode(Sha256::digest(preimage)),
        }],
        "affectedFiles": [file_path],
        "proof": exact_rename_proof(workspace, Vec::new()),
        "fileImages": [exact_file_image_value(&file_path, preimage, postimage.as_bytes())],
        "schemaVersion": api_schema_version(),
    })
}

fn exact_rename_proof(workspace: &Path, occurrences: Vec<Value>) -> Value {
    json!({
        "target": {
            "fqName": "io.example.OrderService.process",
            "kind": "FUNCTION",
            "declarationFile": workspace.join("Keywords.kt").display().to_string(),
            "declarationStartOffset": 10,
            "containingType": "io.example.OrderService"
        },
        "requiredGeneration": 7,
        "evidence": {
            "type": "COMPLETE",
            "cardinality": {
                "type": "EXACT",
                "totalCount": occurrences.len()
            },
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
        "occurrences": occurrences
    })
}

fn rename_preview_with_exact_reference(workspace: &Path, new_name: &str) -> Value {
    let declaration_file = workspace.join("Keywords.kt").display().to_string();
    let reference_file = workspace.join("Usage.kt").display().to_string();
    let target = json!({
        "fqName": "io.example.OrderService.process",
        "kind": "FUNCTION",
        "declarationFile": declaration_file,
        "declarationStartOffset": 10,
        "containingType": "io.example.OrderService"
    });
    let occurrence = json!({
        "reference": {
            "location": {
                "filePath": reference_file,
                "startOffset": 21,
                "endOffset": 28,
                "startLine": 2,
                "startColumn": 13,
                "preview": "service.process()"
            },
            "containingSymbol": {"type": "TOP_LEVEL"}
        },
        "resolvedTarget": target,
        "provenance": "COMPILER"
    });
    let declaration_preimage = b"0123456789process\n";
    let declaration_postimage = format!("0123456789{new_name}\n");
    let reference_preimage = b"012345678901234567890process\n";
    let reference_postimage = format!("012345678901234567890{new_name}\n");
    json!({
        "edits": [
            {
                "filePath": declaration_file,
                "startOffset": 10,
                "endOffset": 17,
                "newText": new_name,
            },
            {
                "filePath": reference_file,
                "startOffset": 21,
                "endOffset": 28,
                "newText": new_name,
            }
        ],
        "fileHashes": [
            {"filePath": declaration_file, "hash": hex::encode(Sha256::digest(declaration_preimage))},
            {"filePath": reference_file, "hash": hex::encode(Sha256::digest(reference_preimage))}
        ],
        "affectedFiles": [declaration_file, reference_file],
        "proof": exact_rename_proof(workspace, vec![occurrence]),
        "fileImages": [
            exact_file_image_value(&declaration_file, declaration_preimage, declaration_postimage.as_bytes()),
            exact_file_image_value(&reference_file, reference_preimage, reference_postimage.as_bytes())
        ],
        "schemaVersion": api_schema_version(),
    })
}
fn run_agent_symbol(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    extra_args: &[&str],
) -> std::process::Output {
    let mut command = kast(home, config_home);
    command.args([
        "--output",
        "json",
        "agent",
        "symbol",
        "--query",
        "`when`",
        "--workspace-root",
        workspace.to_str().expect("workspace"),
    ]);
    command.args(extra_args).output().expect("agent symbol")
}

#[test]
fn agent_symbol_defaults_to_exact_and_returns_compiler_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let handle = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![("symbol/resolve", symbol_result(&workspace, "sample.when"))],
    );

    let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("symbol json");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_SYMBOL_RESULT");
    assert_eq!(stdout["result"]["mode"], "exact");
    assert_eq!(stdout["result"]["outcome"], "RESOLVED");
    assert_eq!(stdout["result"]["source"], "compiler");
    assert_eq!(stdout["result"]["identity"]["fqName"], "sample.when");
    let requests = handle.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[2]["params"]["symbol"], "`when`");
}

#[test]
fn agent_symbol_not_found_and_ambiguous_do_not_discover() {
    for result in [
        json!({"type":"RESOLVE_NOT_FOUND","ok":true,"source":"compiler"}),
        json!({
            "type":"RESOLVE_AMBIGUOUS",
            "ok":true,
            "source":"compiler",
            "candidates":[{"fqName":"alpha.Parser.parse"},{"fqName":"beta.Parser.parse"}]
        }),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("indexer.sock");
        let handle = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &socket_path,
            vec![("symbol/resolve", result)],
        );

        let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("symbol json");
        assert!(matches!(
            stdout["result"]["outcome"].as_str(),
            Some("NOT_FOUND" | "AMBIGUOUS")
        ));
        let requests = handle.join().expect("scripted backend");
        assert_eq!(
            requests.len(),
            3,
            "expected only runtime probes plus resolve"
        );
        assert_eq!(requests[2]["method"], "symbol/resolve");
    }
}

#[path = "cases/symbol_fallbacks.rs"]
mod symbol_fallbacks;
