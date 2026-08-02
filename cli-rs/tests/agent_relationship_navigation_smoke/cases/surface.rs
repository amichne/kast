use std::ffi::OsStr;
use std::path::Path;
use support::metrics::{seed_high_cardinality_impact, seed_source_index};
use support::{kast, spawn_scripted_indexer_backend};

fn run_agent_json<I, S>(home: &Path, config: &Path, args: I) -> serde_json::Value
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let output = kast(home, config)
        .args(["--output", "json", "agent"])
        .args(args)
        .output()
        .expect("agent command");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    serde_json::from_slice(&output.stdout).expect("agent JSON")
}

fn exact_selector() -> [&'static str; 6] {
    [
        "--symbol",
        "sample.Service.run",
        "--declaration-file",
        "src/main/kotlin/sample/Service.kt",
        "--declaration-start-offset",
        "42",
    ]
}

fn help_lists_command(stdout: &str, command: &str) -> bool {
    stdout
        .lines()
        .any(|line| line.trim_start().starts_with(command))
}

fn relation_identity(
    fq_name: &str,
    kind: &str,
    file: &std::path::Path,
    start_offset: u64,
) -> serde_json::Value {
    serde_json::json!({
        "fqName": fq_name,
        "kind": kind,
        "declarationFile": file,
        "declarationStartOffset": start_offset
    })
}

fn relation_location(file: &std::path::Path, start_offset: u64) -> serde_json::Value {
    serde_json::json!({
        "filePath": file,
        "startOffset": start_offset,
        "endOffset": start_offset + 1
    })
}

fn exact_relation_page(total_count: usize) -> serde_json::Value {
    serde_json::json!({
        "evidence": complete_relationship_evidence(total_count),
        "returnedCount": total_count,
        "visitedCandidateCount": total_count,
        "truncated": false
    })
}

fn proofless_exact_relation_page(total_count: usize) -> serde_json::Value {
    serde_json::json!({
        "cardinality": {"type": "EXACT", "totalCount": total_count},
        "returnedCount": total_count,
        "visitedCandidateCount": total_count,
        "truncated": false
    })
}

fn complete_relationship_coverage() -> serde_json::Value {
    serde_json::json!({
        "type": "COMPLETE",
        "identity": "COMPLETE",
        "projectScope": "COMPLETE",
        "sourceSetScope": "COMPLETE",
        "indexFreshness": "COMPLETE",
        "backend": "COMPLETE",
        "requestedFamily": "COMPLETE",
        "limitations": []
    })
}

fn complete_relationship_evidence(total_count: usize) -> serde_json::Value {
    serde_json::json!({
        "type": "COMPLETE",
        "cardinality": {"type": "EXACT", "totalCount": total_count},
        "coverage": complete_relationship_coverage()
    })
}

fn resumable_relationship_evidence(known_minimum_count: usize) -> serde_json::Value {
    serde_json::json!({
        "type": "RESUMABLE",
        "cardinality": {
            "type": "KNOWN_MINIMUM",
            "knownMinimumCount": known_minimum_count
        },
        "coverage": {
            "type": "RESUMABLE",
            "identity": "COMPLETE",
            "projectScope": "COMPLETE",
            "sourceSetScope": "COMPLETE",
            "indexFreshness": "COMPLETE",
            "backend": "COMPLETE",
            "requestedFamily": "IN_PROGRESS",
            "limitations": ["FAMILY_SEARCH_IN_PROGRESS"]
        }
    })
}

fn excluded_source_set_evidence(known_minimum_count: usize) -> serde_json::Value {
    serde_json::json!({
        "type": "LIMITED",
        "cardinality": {
            "type": "KNOWN_MINIMUM",
            "knownMinimumCount": known_minimum_count
        },
        "coverage": {
            "type": "LIMITED",
            "identity": "COMPLETE",
            "projectScope": "COMPLETE",
            "sourceSetScope": "EXCLUDED",
            "indexFreshness": "COMPLETE",
            "backend": "COMPLETE",
            "requestedFamily": "PARTIAL",
            "limitations": ["SOURCE_SET_EXCLUDED", "FAMILY_SEARCH_INCOMPLETE"]
        }
    })
}

fn generation_changed_evidence(known_minimum_count: usize) -> serde_json::Value {
    serde_json::json!({
        "type": "LIMITED",
        "cardinality": {
            "type": "KNOWN_MINIMUM",
            "knownMinimumCount": known_minimum_count
        },
        "coverage": {
            "type": "LIMITED",
            "identity": "COMPLETE",
            "projectScope": "COMPLETE",
            "sourceSetScope": "COMPLETE",
            "indexFreshness": "STALE",
            "backend": "COMPLETE",
            "requestedFamily": "PARTIAL",
            "limitations": ["GENERATION_CHANGED"]
        }
    })
}

fn call_relation_record(
    relation: &str,
    index: usize,
    workspace: &std::path::Path,
) -> serde_json::Value {
    let file = workspace.join(format!("Caller{index}.kt"));
    serde_json::json!({
        "relation": relation,
        "relatedSymbol": relation_identity(
            &format!("sample.Caller{index}.call"),
            "FUNCTION",
            &file,
            index as u64,
        ),
        "callSite": relation_location(&file, index as u64 + 10),
        "depth": 1,
        "containingSymbol": {"type": "TOP_LEVEL"}
    })
}

#[test]
fn standalone_relationship_commands_are_public() {
    let temp = tempfile::tempdir().expect("tempdir");
    let output = kast(&temp.path().join("home"), &temp.path().join("config"))
        .args(["agent", "--help"])
        .output()
        .expect("agent help");

    assert!(output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    for command in [
        "references",
        "callers",
        "callees",
        "implementations",
        "hierarchy",
    ] {
        assert!(
            help_lists_command(&stdout, command),
            "agent help should show {command}: {stdout}",
        );
    }
}

#[test]
fn one_shot_symbol_relationship_flags_are_retired() {
    for retired_flag in [
        "--references",
        "--reference-page-token",
        "--callers",
        "--caller-depth",
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(["agent", "symbol", "--query", "Service", retired_flag])
            .output()
            .expect("retired symbol flag");

        assert_eq!(
            output.status.code(),
            Some(2),
            "flag={retired_flag} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[test]
fn relationship_commands_accept_exact_identity_selectors() {
    for (command, command_args) in [
        ("callers", vec!["--depth", "2"]),
        ("callees", vec!["--depth", "2"]),
        ("implementations", Vec::new()),
        ("hierarchy", vec!["--direction", "both", "--depth", "2"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let declaration_file = workspace.join("src/main/kotlin/sample/Service.kt");
        std::fs::create_dir_all(declaration_file.parent().expect("declaration parent"))
            .expect("declaration directory");
        std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
        let mut invocation = vec!["--output", "json", "agent", command];
        invocation.extend(exact_selector());
        invocation.extend(command_args);
        invocation.extend(["--limit", "17", "--fields", "subject,page"]);
        invocation.extend([
            "--workspace-root",
            workspace.to_str().expect("workspace root"),
        ]);

        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(invocation)
            .output()
            .expect("typed relationship command");

        assert_eq!(
            output.status.code(),
            Some(1),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("relationship error json");
        assert!(
            stdout["error"]["code"].is_string(),
            "command={command} output={stdout}"
        );
    }
}

#[test]
fn relationship_types_reject_invalid_values_before_runtime_io() {
    for (command, extra_args) in [
        ("references", vec!["--limit", "0"]),
        ("references", vec!["--limit", "201"]),
        ("references", vec!["--page-token", "not-a-token"]),
        ("callers", vec!["--depth", "0"]),
        ("callees", vec!["--depth", "9"]),
        ("hierarchy", vec!["--direction", "sideways"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut invocation = vec!["agent", command];
        invocation.extend(exact_selector());
        invocation.extend(extra_args);

        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(invocation)
            .output()
            .expect("invalid relationship command");

        assert_eq!(
            output.status.code(),
            Some(2),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
