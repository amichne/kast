mod support;

use support::metrics::seed_source_index;
use support::{ScriptedCliAuthority, kast_at, spawn_scripted_idea_backend_for_invocations};

const INSTALLED_BINARY_ENV: &str = "KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY";

fn decode_default_toon(
    operation: &str,
    output: std::process::Output,
) -> (String, serde_json::Value) {
    assert!(
        output.status.success(),
        "{operation} failed: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let rendered = String::from_utf8(output.stdout).expect("installed CLI output is UTF-8");
    assert!(
        !rendered.trim_start().starts_with('{'),
        "{operation} must use default TOON instead of JSON",
    );
    let decoded = toon_format::decode_default(rendered.trim())
        .unwrap_or_else(|error| panic!("{operation} emitted invalid TOON: {error}"));
    (rendered, decoded)
}

fn installed_cli_version(binary: &std::path::Path) -> String {
    let output = std::process::Command::new(binary)
        .arg("--version")
        .output()
        .expect("read installed CLI version");
    assert!(output.status.success(), "installed CLI version command");
    String::from_utf8(output.stdout)
        .expect("installed CLI version is UTF-8")
        .trim()
        .strip_prefix("kast ")
        .expect("installed CLI version prefix")
        .to_string()
}

fn complete_relationship_evidence(total_count: usize) -> serde_json::Value {
    serde_json::json!({
        "type": "COMPLETE",
        "cardinality": {"type": "EXACT", "totalCount": total_count},
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
    })
}

#[test]
fn installed_cli_resolves_once_and_reuses_handle_across_default_toon_operations() {
    let Some(installed_binary) = std::env::var_os(INSTALLED_BINARY_ENV) else {
        eprintln!(
            "skipped: run .github/scripts/test-selector-handle-installed-workflow.sh for installed proof"
        );
        return;
    };
    let installed_binary = std::path::PathBuf::from(installed_binary);
    assert!(
        installed_binary.is_file(),
        "installed CLI does not exist: {}",
        installed_binary.display(),
    );
    let installed_version = installed_cli_version(&installed_binary);
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"selector-handle-installed-workflow\"\n",
    )
    .expect("Gradle marker");
    seed_source_index(&workspace);
    let declaration_file = workspace.join("lib/Bar.kt");
    assert!(declaration_file.is_file(), "indexed declaration fixture");

    let selector_handle = "ksh1.installed-workflow-handle";
    let identity = serde_json::json!({
        "fqName": "lib.Bar",
        "kind": "FUNCTION",
        "declarationFile": declaration_file,
        "declarationStartOffset": 1
    });
    let backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("idea.sock"),
        ScriptedCliAuthority::new(&installed_binary, &installed_version),
        3,
        vec![
            (
                "symbol/resolve",
                serde_json::json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "selectorHandle": selector_handle,
                    "symbol": {
                        "fqName": "lib.Bar",
                        "kind": "FUNCTION",
                        "location": {
                            "filePath": declaration_file,
                            "startOffset": 1,
                            "endOffset": 2
                        }
                    }
                }),
            ),
            (
                "symbol/references",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": identity,
                    "references": [],
                    "evidence": complete_relationship_evidence(0),
                    "schemaVersion": 3
                }),
            ),
            (
                "symbol/callers",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": identity,
                    "records": [],
                    "page": {
                        "evidence": complete_relationship_evidence(0),
                        "returnedCount": 0,
                        "visitedCandidateCount": 0,
                        "truncated": false
                    },
                    "schemaVersion": 3
                }),
            ),
        ],
    );

    let workspace_root = workspace.to_str().expect("workspace path");
    let resolved = kast_at(&installed_binary, &home, &config_home)
        .args([
            "agent",
            "symbol",
            "--query",
            "lib.Bar",
            "--workspace-root",
            workspace_root,
        ])
        .output()
        .expect("run installed selector resolve");
    let (resolved_toon, resolved) = decode_default_toon("resolve", resolved);
    assert_eq!(
        resolved["result"]["identity"], identity,
        "resolved output={resolved:#}",
    );
    assert_eq!(
        resolved["result"]["selectorHandle"], selector_handle,
        "exact resolution must expose its opaque reusable handle; raw={resolved_toon}; decoded={resolved:#}",
    );
    for forbidden in [
        "steps",
        "documentation",
        "context",
        "surroundingMembers",
        "resolution",
    ] {
        assert!(
            resolved["result"].get(forbidden).is_none(),
            "compact resolution leaked {forbidden}",
        );
    }

    let references = kast_at(&installed_binary, &home, &config_home)
        .args([
            "agent",
            "references",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace_root,
        ])
        .output()
        .expect("run installed references");
    let (references_toon, references) = decode_default_toon("references", references);
    assert_eq!(references["result"]["outcome"], "AVAILABLE");
    assert_eq!(references["result"]["subject"], identity);
    assert_eq!(references["result"]["coverage"]["type"], "COMPLETE");
    assert_eq!(references["result"]["limitations"], serde_json::json!([]));

    let callers = kast_at(&installed_binary, &home, &config_home)
        .args([
            "agent",
            "callers",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace_root,
        ])
        .output()
        .expect("run installed callers");
    let (callers_toon, callers) = decode_default_toon("callers", callers);
    assert_eq!(callers["result"]["outcome"], "AVAILABLE");
    assert_eq!(callers["result"]["subject"], identity);
    assert_eq!(callers["result"]["coverage"]["type"], "COMPLETE");
    assert_eq!(callers["result"]["limitations"], serde_json::json!([]));

    let toon_bytes = resolved_toon.len() + references_toon.len() + callers_toon.len();
    let pretty_json_bytes = [&resolved, &references, &callers]
        .into_iter()
        .map(|value| serde_json::to_vec_pretty(value).expect("pretty JSON").len())
        .sum::<usize>();
    assert!(
        toon_bytes < pretty_json_bytes,
        "default TOON must stay smaller than pretty JSON: toon={toon_bytes} json={pretty_json_bytes}",
    );

    let requests = backend.join().expect("scripted backend");
    let semantic_requests = requests
        .iter()
        .filter(|request| {
            request["method"]
                .as_str()
                .is_some_and(|method| method.starts_with("symbol/"))
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec!["symbol/resolve", "symbol/references", "symbol/callers"],
        "installed workflow must resolve once and never rediscover by name",
    );
    assert_eq!(
        semantic_requests
            .iter()
            .filter(|request| request["method"] == "symbol/resolve")
            .count(),
        1,
    );
    for request in &semantic_requests[1..] {
        assert_eq!(request["params"]["selectorHandle"], selector_handle);
        for reconstructed in [
            "selector",
            "symbol",
            "declarationFile",
            "declarationStartOffset",
        ] {
            assert!(
                request["params"].get(reconstructed).is_none(),
                "handle reuse reconstructed {reconstructed}",
            );
        }
    }
}
