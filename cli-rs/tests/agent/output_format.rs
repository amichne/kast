#[path = "../support/mod.rs"]
mod support;

use base64::{Engine as _, engine::general_purpose::STANDARD as STANDARD_BASE64};
use sha2::{Digest as _, Sha256};
use std::os::{fd::AsRawFd, unix::process::CommandExt};
use support::*;

fn rename_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"rename-output\"\n",
    )
    .expect("Gradle marker");
    let file_path = workspace.join("OrderService.kt");
    std::fs::write(
        &file_path,
        "package io.example\nclass OrderService { fun process() = Unit }\n",
    )
    .expect("Kotlin fixture");
    let preimage = std::fs::read(&file_path).expect("Kotlin fixture bytes");
    let postimage = String::from_utf8(preimage.clone())
        .expect("UTF-8 fixture")
        .replace("process", "processSafely")
        .into_bytes();
    let preimage_hash = hex::encode(Sha256::digest(&preimage));
    let file_path = file_path.display().to_string();
    spawn_scripted_indexer_backend(
        home,
        config_home,
        workspace,
        socket_path,
        vec![
            (
                "symbol/resolve",
                serde_json::json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "symbol": {
                        "fqName": "io.example.OrderService.process",
                        "kind": "FUNCTION",
                        "location": {
                            "filePath": file_path,
                            "startOffset": 44,
                            "endOffset": 51,
                        },
                    },
                }),
            ),
            (
                "raw/rename",
                serde_json::json!({
                    "edits": [{
                        "filePath": file_path,
                        "startOffset": 44,
                        "endOffset": 51,
                        "newText": "processSafely",
                    }],
                    "fileHashes": [{
                        "filePath": file_path,
                        "hash": preimage_hash,
                    }],
                    "affectedFiles": [file_path],
                    "proof": {
                        "target": {
                            "fqName": "io.example.OrderService.process",
                            "kind": "FUNCTION",
                            "declarationFile": file_path,
                            "declarationStartOffset": 44
                        },
                        "requiredGeneration": 7,
                        "evidence": {
                            "type": "COMPLETE",
                            "cardinality": {"type": "EXACT", "totalCount": 0},
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
                        "occurrences": []
                    },
                    "fileImages": [{
                        "filePath": file_path,
                        "preimage": {
                            "contentBase64": STANDARD_BASE64.encode(&preimage),
                            "sha256": preimage_hash,
                        },
                        "postimage": {
                            "contentBase64": STANDARD_BASE64.encode(&postimage),
                            "sha256": hex::encode(Sha256::digest(&postimage)),
                        },
                    }],
                    "schemaVersion": api_schema_version(),
                }),
            ),
        ],
    )
}

fn decode_toon(bytes: &[u8]) -> serde_json::Value {
    let output = std::str::from_utf8(bytes).expect("toon output should be utf-8");
    toon_format::decode_default(output.trim()).expect("toon output should decode")
}

#[derive(Clone, Copy, Debug)]
enum FailureCase {
    Parsing,
    Validation,
    LocalState,
    BackendRejection,
    InvalidContinuation,
    BusyPlan,
    RecoveryUnavailable,
    Unexpected,
}

impl FailureCase {
    fn marker(self) -> &'static str {
        match self {
            Self::Parsing | Self::LocalState => "CLI_USAGE",
            Self::Validation => "PLAN_ID_MALFORMED",
            Self::BackendRejection => "backend-rejected",
            Self::InvalidContinuation => "GRAPH_PAGE_TOKEN_MALFORMED",
            Self::BusyPlan => "KAST_PLAN_BUSY",
            Self::RecoveryUnavailable => "KAST_PLAN_UNAVAILABLE",
            Self::Unexpected => "IO_ERROR",
        }
    }

    fn args(self, format: &str, id: &str) -> Vec<String> {
        let tail: &[&str] = match self {
            Self::Parsing => &["--unknown-machine-flag"],
            Self::Validation => &["change", "apply", "--plan-id", "invalid"],
            Self::LocalState => &["file", "list", "--match", "["],
            Self::BackendRejection => &["symbol", "resolve", "--query", "sample.Missing"],
            Self::InvalidContinuation => {
                &["graph", "nodes", "--continuation", "not-a-continuation"]
            }
            Self::BusyPlan => &["change", "apply", "--plan-id", id],
            Self::RecoveryUnavailable => &["change", "recover", "--recovery-id", id],
            Self::Unexpected => &["change", "apply", "--plan-id", id],
        };
        ["--output", format]
            .into_iter()
            .chain(tail.iter().copied())
            .map(str::to_string)
            .collect()
    }
}

fn public_kast(home: &Path, config_home: &Path, workspace: &Path) -> std::process::Command {
    let mut command = std::process::Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_HOME", home.join(".local/share/kast"))
        .env("KAST_CONFIG_HOME", config_home);
    command
}

fn assert_canonical_machine_failure(
    case: FailureCase,
    format: &str,
    output: &std::process::Output,
) {
    assert!(!output.status.success(), "{case:?}/{format}: {output:?}");
    assert!(
        output.stdout.ends_with(b"\n") && !output.stdout.ends_with(b"\n\n"),
        "{case:?}/{format} must end with exactly one newline: {:?}",
        String::from_utf8_lossy(&output.stdout)
    );
    let value = match format {
        "json" => serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
            panic!(
                "{case:?} emitted invalid JSON: {error}; stdout={}",
                String::from_utf8_lossy(&output.stdout)
            )
        }),
        "toon" => decode_toon(&output.stdout),
        _ => unreachable!("closed test format"),
    };
    assert!(
        value["schemaVersion"] == 3 && value["status"] == "rejected"
            || value["error"].is_string()
                && value["message"].is_string()
                && value["next"].is_string(),
        "{case:?}/{format} emitted a non-canonical failure: {value:#}"
    );
    assert!(
        value.to_string().contains(case.marker()),
        "{case:?}/{format} missed {}: {value:#}",
        case.marker()
    );
}

#[test]
fn every_cli_failure_honors_the_selected_machine_output() {
    const ID: &str = "b4d176ef-f0b9-4d54-9a3a-1ab659924452";
    let cases = [
        FailureCase::Parsing,
        FailureCase::Validation,
        FailureCase::LocalState,
        FailureCase::BackendRejection,
        FailureCase::InvalidContinuation,
        FailureCase::BusyPlan,
        FailureCase::RecoveryUnavailable,
        FailureCase::Unexpected,
    ];
    for format in ["json", "toon"] {
        for case in cases {
            let temp = tempfile::tempdir().expect("failure fixture");
            let home = temp.path().join("home");
            let config_home = temp.path().join("config");
            let workspace = temp.path().join("workspace");
            std::fs::create_dir_all(&workspace).expect("workspace");
            let mut lock = None;
            if matches!(
                case,
                FailureCase::BusyPlan | FailureCase::RecoveryUnavailable
            ) {
                let directory = default_install_root(&home).join("state/agent-plans");
                std::fs::create_dir_all(&directory).expect("plan directory");
                if matches!(case, FailureCase::BusyPlan) {
                    let file = std::fs::OpenOptions::new()
                        .read(true)
                        .write(true)
                        .create(true)
                        .truncate(false)
                        .open(directory.join(format!("{ID}.lock")))
                        .expect("plan lock");
                    assert_eq!(unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) }, 0);
                    lock = Some(file);
                }
            }
            let mut command = public_kast(&home, &config_home, &workspace);
            if matches!(case, FailureCase::Unexpected) {
                let blocked = temp.path().join("not-a-directory");
                std::fs::write(&blocked, "blocked").expect("blocked install root");
                command.env("KAST_HOME", blocked);
            }
            let output = command
                .args(case.args(format, ID))
                .output()
                .expect("machine failure");
            assert_canonical_machine_failure(case, format, &output);
            drop(lock);
        }
    }
}

#[test]
fn agent_rename_plan_default_toon_matches_explicit_json() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let json_backend = rename_backend(&home, &config_home, &workspace, &socket_path);

    let json = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent rename json");
    assert!(
        json.status.success(),
        "agent rename json should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&json.stdout),
        String::from_utf8_lossy(&json.stderr)
    );
    assert_eq!(
        String::from_utf8(json.stderr.clone()).expect("agent rename stderr"),
        "warning: JSON output for `kast agent` is deprecated; omit `--output json` to use TOON.\n"
    );
    let json_value: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("agent rename json");
    json_backend.join().expect("JSON rename backend");
    std::fs::remove_file(&socket_path).expect("remove first socket");
    let toon_backend = rename_backend(&home, &config_home, &workspace, &socket_path);

    let toon = kast(&home, &config_home)
        .args([
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent rename toon");
    assert!(
        toon.status.success(),
        "agent rename toon should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&toon.stdout),
        String::from_utf8_lossy(&toon.stderr)
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&toon.stdout).is_err(),
        "toon output should not be parseable as JSON"
    );
    let toon_value = decode_toon(&toon.stdout);
    toon_backend.join().expect("TOON rename backend");

    assert_eq!(toon_value, json_value);
    assert!(
        toon.stdout.len() < json.stdout.len(),
        "toon agent rename output should be smaller than pretty JSON: json={}, toon={}",
        json.stdout.len(),
        toon.stdout.len()
    );
}

#[test]
fn agent_rename_plan_is_read_only_until_apply() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let backend = rename_backend(&home, &config_home, &workspace, &socket_path);
    let source_before = std::fs::read(workspace.join("OrderService.kt")).expect("source before");

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent rename plan");
    assert!(
        plan.status.success(),
        "rename plan should succeed through backend dry-run dispatch: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan json");
    assert_eq!(output["ok"], true, "{output:#}");
    assert_eq!(output["method"], "agent/rename", "{output:#}");
    assert_eq!(
        output["result"]["type"], "KAST_AGENT_MUTATION_RESULT",
        "{output:#}"
    );
    assert_eq!(
        output["result"]["execution"]["outcome"], "PLANNED_RENAME",
        "{output:#}"
    );
    assert_eq!(
        output["result"]["plan"]["method"], "symbol/rename",
        "{output:#}"
    );
    assert!(
        !output["result"]["plan"].to_string().contains("offset"),
        "{output:#}"
    );
    let requests = backend.join().expect("rename backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[3]["method"], "raw/rename");
    assert_eq!(requests[3]["params"]["dryRun"], true);
    assert_eq!(
        std::fs::read(workspace.join("OrderService.kt")).expect("source after"),
        source_before,
    );
}
