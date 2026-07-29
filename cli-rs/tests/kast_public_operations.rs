#[path = "support/mod.rs"]
mod support;

use serde_json::{Value, json};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};
use support::{
    ScriptedCliAuthority, spawn_scripted_idea_backend, spawn_scripted_idea_backend_for_invocations,
};

fn kast(home: &Path, config_home: &Path, workspace: &Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_HOME", home.join(".local/share/kast"))
        .env("KAST_CONFIG_HOME", config_home);
    command
}

fn run_with_stdin(mut command: Command, stdin: &str) -> Output {
    let mut child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn kast");
    child
        .stdin
        .take()
        .expect("stdin")
        .write_all(stdin.as_bytes())
        .expect("write stdin");
    child.wait_with_output().expect("wait for kast")
}

fn decode(output: &Output) -> Value {
    toon_format::decode_default(
        std::str::from_utf8(&output.stdout)
            .expect("UTF-8 output")
            .trim(),
    )
    .unwrap_or_else(|error| {
        panic!(
            "valid TOON: {error}; stdout={}",
            String::from_utf8_lossy(&output.stdout)
        )
    })
}

#[test]
fn change_persists_a_private_root_bound_plan_and_apply_consumes_it_after_success() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"plan-test\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");

    let mut change = kast(&home, &config_home, &workspace);
    change.args(["change", "add-file", "src/main/kotlin/Added.kt"]);
    let change = run_with_stdin(change, "package sample\nclass Added\n");
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr)
    );
    let change = decode(&change);
    let plan_id = change["planId"].as_str().expect("plan id");
    uuid::Uuid::parse_str(plan_id).expect("UUID plan id");
    assert_eq!(change["operation"], "add-file");
    assert_eq!(
        change["next"],
        format!("kast apply {plan_id}"),
        "{change:#}"
    );

    let plans = home.join(".local/share/kast/state/agent-plans");
    let plan_path = plans.join(format!("{plan_id}.json"));
    let content_path = plans.join(format!("{plan_id}.content"));
    assert!(plan_path.is_file(), "persisted plan");
    assert!(content_path.is_file(), "persisted content");
    assert_eq!(
        std::fs::metadata(&plan_path)
            .expect("plan metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    assert_eq!(
        std::fs::metadata(&content_path)
            .expect("content metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600
    );

    let other = fixture.path().join("other");
    std::fs::create_dir_all(&other).expect("other root");
    std::fs::write(other.join("settings.gradle.kts"), "").expect("other settings");
    let wrong_root = kast(&home, &config_home, &other)
        .args(["apply", plan_id])
        .output()
        .expect("wrong-root apply");
    assert_eq!(wrong_root.status.code(), Some(1), "{wrong_root:?}");
    assert!(plan_path.is_file(), "failed apply keeps plan");
    assert!(content_path.is_file(), "failed apply keeps content");

    let socket = fixture.path().join("apply.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![(
            "mutation/submit",
            json!({
                "type": "SUCCEEDED",
                "result": {
                    "type": "SCOPE_MUTATION_RESULT",
                    "response": {
                        "editCount": 1,
                        "affectedFiles": [],
                        "createdFiles": [workspace.join("src/main/kotlin/Added.kt")],
                        "diagnostics": {"errorCount": 0, "warningCount": 0}
                    }
                },
                "deduplicated": false
            }),
        )],
    );
    let apply = kast(&home, &config_home, &workspace)
        .args(["apply", plan_id])
        .output()
        .expect("apply");
    assert!(
        apply.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr)
    );
    let applied = decode(&apply);
    assert_eq!(applied["execution"]["outcome"], "SUCCEEDED", "{applied:#}");
    let requests = backend.join().expect("apply backend");
    let submit = requests
        .iter()
        .find(|request| request["method"] == "mutation/submit")
        .expect("mutation submission");
    assert_eq!(submit["params"]["idempotencyKey"], plan_id);
    assert_eq!(
        submit["params"]["request"]["contentFile"],
        content_path.display().to_string()
    );
    assert!(!plan_path.exists(), "successful apply consumes plan");
    assert!(!content_path.exists(), "successful apply consumes content");
}

#[test]
fn refresh_combines_diagnostics_and_graph_for_the_exact_files() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source = workspace.join("src/App.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source directory");
    std::fs::write(&source, "fun app() = missing\n").expect("source");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let source = source.canonicalize().expect("canonical source");
    let socket = fixture.path().join("refresh.sock");
    let backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        ScriptedCliAuthority::new(
            Path::new(env!("CARGO_BIN_EXE_kast")),
            env!("CARGO_PKG_VERSION"),
        ),
        2,
        vec![
            ("raw/workspace-refresh", complete_refresh(&source)),
            ("raw/diagnostics", diagnostics_with_error(&source)),
            (
                "raw/semantic-graph",
                json!({
                    "generation": 9,
                    "scopeFingerprint": "a".repeat(64),
                    "coverage": {
                        "files": [{
                            "path": source.display().to_string(),
                            "contentHash": "b".repeat(64),
                            "status": "REFRESHED",
                            "diagnostics": []
                        }],
                        "omittedExternalTargetCount": 0
                    },
                    "symbolCount": 2,
                    "edgeOccurrenceCount": 1
                }),
            ),
        ],
    );

    let refresh = kast(&home, &config_home, &workspace)
        .args(["refresh", source.to_str().expect("source")])
        .output()
        .expect("refresh");
    assert!(
        refresh.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&refresh.stdout),
        String::from_utf8_lossy(&refresh.stderr)
    );
    let refresh = decode(&refresh);
    assert_eq!(refresh["fileCount"], 1);
    assert_eq!(refresh["diagnostics"]["severityCounts"]["error"], 1);
    assert_eq!(
        refresh["diagnostics"]["diagnostics"][0]["severity"],
        "ERROR"
    );
    assert_eq!(refresh["graph"]["generation"], 9);
    let requests = backend.join().expect("refresh backend");
    let semantic_requests = requests
        .iter()
        .filter(|request| {
            matches!(
                request["method"].as_str(),
                Some("raw/workspace-refresh" | "raw/diagnostics" | "raw/semantic-graph")
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_requests
            .iter()
            .map(|request| request["method"].as_str().expect("method"))
            .collect::<Vec<_>>(),
        [
            "raw/workspace-refresh",
            "raw/diagnostics",
            "raw/semantic-graph"
        ]
    );
    for request in semantic_requests {
        assert_eq!(request["params"]["filePaths"], json!([source]));
    }
}

#[test]
fn refresh_external_projects_only_actionable_outcomes() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let socket = fixture.path().join("external.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![(
            "raw/workspace-refresh",
            json!({
                "refreshedFiles": [],
                "removedFiles": [],
                "fullRefresh": false,
                "fileStatuses": [],
                "externalFailureOutcomes": [
                    {"failureId": "failure-a", "status": "EXTERNALIZED"},
                    {"failureId": "failure-b", "status": "ALREADY_EXTERNAL"}
                ],
                "semanticOutcome": "COMPLETE",
                "requestedFileCount": 0,
                "analyzedFileCount": 0,
                "skippedFileCount": 0,
                "removedFileCount": 0,
                "attemptCount": 1,
                "elapsedMillis": 0,
                "schemaVersion": 5
            }),
        )],
    );

    let external = kast(&home, &config_home, &workspace)
        .args(["refresh", "external", "failure-a", "failure-b"])
        .output()
        .expect("external refresh");
    assert!(
        external.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&external.stdout),
        String::from_utf8_lossy(&external.stderr)
    );
    let external = decode(&external);
    assert_eq!(
        external,
        json!({
            "external": [
                {"failureId": "failure-a", "status": "EXTERNALIZED"},
                {"failureId": "failure-b", "status": "ALREADY_EXTERNAL"}
            ]
        })
    );
    let requests = backend.join().expect("external backend");
    let request = requests
        .iter()
        .find(|request| request["method"] == "raw/workspace-refresh")
        .expect("workspace refresh");
    assert_eq!(
        request["params"]["externalFailureIds"],
        json!(["failure-a", "failure-b"])
    );
    assert_eq!(request["params"]["filePaths"], json!([]));
}

fn complete_refresh(file: &Path) -> Value {
    let file = file.display().to_string();
    json!({
        "refreshedFiles": [file],
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file,
            "fileSystemDiscovery": "DISCOVERED",
            "sourceModuleOwnership": "OWNED",
            "indexAdmission": "ADMITTED",
            "analysisAvailability": "AVAILABLE",
            "analysisStatus": {"filePath": file, "state": "ANALYZED"}
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "removedFileCount": 0,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": 5
    })
}

fn diagnostics_with_error(file: &Path) -> Value {
    let file = file.display().to_string();
    json!({
        "diagnostics": [{
            "location": {
                "filePath": file,
                "startOffset": 12,
                "endOffset": 19,
                "startLine": 1,
                "startColumn": 13,
                "preview": "missing"
            },
            "severity": "ERROR",
            "message": "Unresolved reference",
            "code": "UNRESOLVED_REFERENCE"
        }],
        "fileStatuses": [{"filePath": file, "state": "ANALYZED"}],
        "fileHashes": [{"filePath": file, "hash": "c".repeat(64)}],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1}
    })
}
