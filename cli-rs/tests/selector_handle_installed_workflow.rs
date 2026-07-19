mod support;

use sha2::{Digest, Sha256};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use support::metrics::seed_source_index;
use support::{ScriptedCliAuthority, kast_at, spawn_scripted_idea_backend_for_invocations};

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
    let rendered = String::from_utf8(output.stdout).expect("CLI output is UTF-8");
    assert!(
        !rendered.trim_start().starts_with('{'),
        "{operation} must use default TOON instead of JSON",
    );
    let decoded = toon_format::decode_default(rendered.trim())
        .unwrap_or_else(|error| panic!("{operation} emitted invalid TOON: {error}"));
    (rendered, decoded)
}

fn cli_version(binary: &std::path::Path) -> String {
    let output = std::process::Command::new(binary)
        .arg("--version")
        .output()
        .expect("read CLI version");
    assert!(output.status.success(), "CLI version command");
    String::from_utf8(output.stdout)
        .expect("CLI version is UTF-8")
        .trim()
        .strip_prefix("kast ")
        .expect("CLI version prefix")
        .to_string()
}

fn selected_installed_binary() -> PathBuf {
    std::env::var_os("KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_BIN_EXE_kast")))
}

fn selected_installed_launcher(binary: &Path) -> Option<PathBuf> {
    std::env::var_os("KAST_INSTALLED_SELECTOR_WORKFLOW_LAUNCHER")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY").map(|_| {
                binary
                    .parent()
                    .expect("installed binary parent")
                    .join("kast-agent-task")
            })
        })
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

struct InstalledTaskFixture {
    home: PathBuf,
    config_home: PathBuf,
    workspace: PathBuf,
    binary: PathBuf,
    launcher: PathBuf,
    model_receipt: PathBuf,
    install_manifest: PathBuf,
}

impl InstalledTaskFixture {
    fn new(root: &Path, source_extension: &str) -> Self {
        let home = root.join("home");
        let config_home = root.join("config");
        let workspace = root.join("workspace");
        let bin = home.join(".local/bin");
        let binary = bin.join("kast");
        let launcher = bin.join("kast-agent-task");
        let install_root = home.join(".local/share/kast");
        let install_manifest = install_root.join("install.json");
        std::fs::create_dir_all(&bin).expect("installed bin");
        std::fs::create_dir_all(&workspace).expect("task workspace");
        let workspace = workspace.canonicalize().expect("canonical task workspace");
        std::fs::create_dir_all(&install_root).expect("install root");
        let selected_binary = selected_installed_binary();
        assert!(selected_binary.is_file(), "selected installed Kast CLI");
        std::fs::copy(&selected_binary, &binary).expect("installed Kast CLI");
        if let Some(selected_launcher) = selected_installed_launcher(&selected_binary) {
            assert!(
                selected_launcher.is_file(),
                "selected installed task launcher"
            );
            std::fs::copy(selected_launcher, &launcher).expect("installed task launcher");
        } else {
            std::fs::write(
                &launcher,
                include_bytes!("../resources/agent-task/kast-agent-task"),
            )
            .expect("installed task launcher");
        }
        for executable in [&binary, &launcher] {
            std::fs::set_permissions(executable, std::fs::Permissions::from_mode(0o755))
                .expect("installed executable mode");
        }
        let gradle_script_extension = ["k", "t", "s"].concat();
        std::fs::write(
            workspace.join(format!("settings.gradle.{gradle_script_extension}")),
            "rootProject.name = \"agent-task-installed-workflow\"\n",
        )
        .expect("settings");
        std::fs::write(
            workspace.join(format!("build.gradle.{gradle_script_extension}")),
            "plugins { java }\n",
        )
        .expect("build file");
        let source = workspace.join(format!(
            "src/main/{source_extension}/Example.{source_extension}"
        ));
        std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
        std::fs::write(&source, "class Example {}\n").expect("source baseline");

        let model_receipt = root.join("gradle-model.json");
        std::fs::write(
            &model_receipt,
            serde_json::to_vec(&serde_json::json!({
                "schemaVersion": 1,
                "workspaceRoot": workspace.display().to_string(),
                "builds": [{
                    "buildRoot": ".",
                    "projects": [{
                        "projectPath": ":",
                        "projectDirectory": ".",
                        "sourceSets": [{
                            "name": "main",
                            "sourceDirectories": [format!("src/main/{source_extension}")],
                            "buildTasks": [":classes"]
                        }],
                        "tasks": [
                            {"path": ":classes", "kind": "BUILD", "testReportDirectories": []},
                            {"path": ":test", "kind": "TEST", "testReportDirectories": ["build/test-results/test"]}
                        ]
                    }]
                }]
            }))
            .expect("model JSON"),
        )
        .expect("model receipt");

        let wrapper = workspace.join("gradlew");
        std::fs::write(
            &wrapper,
            r#"#!/bin/sh
set -eu
if [ -n "${KAST_AGENT_TASK_GRADLE_MODEL_RECEIPT:-}" ]; then
  cp "$KAST_TEST_MODEL_RECEIPT" "$KAST_AGENT_TASK_GRADLE_MODEL_RECEIPT"
  exit 0
fi
if [ -n "${KAST_AGENT_TASK_GRADLE_RECEIPT:-}" ]; then
  outcome="${KAST_TEST_GRADLE_OUTCOME:-SUCCESS}"
  if [ "${KAST_TEST_REPORT_MODE:-present}" = present ]; then
    mkdir -p "$KAST_TEST_WORKSPACE/build/test-results/test"
    printf '%s\n' '<testsuite tests="1" failures="0" />' > "$KAST_TEST_WORKSPACE/build/test-results/test/TEST-Example.xml"
  fi
  printf '{"schemaVersion":1,"inputSha256":"%s","buildFailed":false,"tasks":[{"path":":classes","outcome":"%s"},{"path":":test","outcome":"%s"}]}\n' "$KAST_AGENT_TASK_INPUT_SHA256" "$outcome" "$outcome" > "$KAST_AGENT_TASK_GRADLE_RECEIPT"
  if [ -n "${KAST_TEST_MUTATE_PATH:-}" ]; then
    printf '%s\n' '// concurrent edit' >> "$KAST_TEST_MUTATE_PATH"
  fi
  exit 0
fi
exit 2
"#,
        )
        .expect("Gradle wrapper");
        std::fs::set_permissions(&wrapper, std::fs::Permissions::from_mode(0o755))
            .expect("Gradle wrapper mode");
        let git = |args: &[&str]| {
            let output = Command::new("git")
                .args(args)
                .current_dir(&workspace)
                .output()
                .expect("run git fixture command");
            assert!(
                output.status.success(),
                "git {args:?} failed: {}",
                String::from_utf8_lossy(&output.stderr),
            );
        };
        git(&["init", "--quiet"]);
        git(&["add", "."]);
        git(&[
            "-c",
            "user.name=Kast Test",
            "-c",
            "user.email=kast@example.invalid",
            "commit",
            "--quiet",
            "-m",
            "fixture baseline",
        ]);

        let fixture = Self {
            home,
            config_home,
            workspace,
            binary,
            launcher,
            model_receipt,
            install_manifest,
        };
        fixture.write_install_manifest("task-fixture-generation-1");
        fixture
    }

    fn source(&self, extension: &str) -> PathBuf {
        self.workspace
            .join(format!("src/main/{extension}/Example.{extension}"))
    }

    fn write_install_manifest(&self, install_id: &str) {
        let install_root = self.home.join(".local/share/kast");
        std::fs::write(
            &self.install_manifest,
            serde_json::to_vec_pretty(&serde_json::json!({
                "tool": "kast",
                "installId": install_id,
                "profile": "user-local",
                "activeVersion": "test",
                "createdAt": "unix:1",
                "updatedAt": "unix:1",
                "roots": {
                    "install": install_root.display().to_string(),
                    "bin": self.binary.parent().expect("bin").display().to_string(),
                    "config": self.config_home.display().to_string(),
                    "data": install_root.join("state").display().to_string(),
                    "cache": self.home.join(".cache/kast").display().to_string(),
                    "runtime": install_root.join("runtime").display().to_string(),
                    "logs": self.home.join(".local/state/kast/logs").display().to_string(),
                    "locks": install_root.join("locks").display().to_string()
                },
                "entrypoints": {
                    "shim": self.binary.display().to_string(),
                    "activeBinary": self.binary.display().to_string(),
                    "taskLauncher": self.launcher.display().to_string()
                },
                "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
                "version": "test",
                "components": ["cli"],
                "schemaVersion": 3
            }))
            .expect("install manifest JSON"),
        )
        .expect("install manifest");
    }

    fn task(&self, session: &str, operation: &str) -> Command {
        let mut command = Command::new(&self.launcher);
        command
            .env("HOME", &self.home)
            .env("KAST_CONFIG_HOME", &self.config_home)
            .env("KAST_AGENT_SESSION_ID", session)
            .env("KAST_TEST_MODEL_RECEIPT", &self.model_receipt)
            .env("KAST_TEST_WORKSPACE", &self.workspace)
            .arg(operation)
            .arg("--workspace-root")
            .arg(&self.workspace);
        command
    }

    fn task_json(&self, session: &str, operation: &str) -> Output {
        let mut command = self.task(session, operation);
        command.args(["--output", "json"]);
        command.output().expect("task lifecycle command")
    }

    fn receipt_path(&self) -> PathBuf {
        find_task_receipt(&self.workspace.join(".gradle"))
            .or_else(|| find_task_receipt(&self.home.join(".local/share/kast/state/workspaces")))
            .expect("current task receipt")
    }
}

fn find_task_receipt(directory: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(directory).ok()?;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.ends_with("agent-tasks/current.json") {
            return Some(path);
        }
        if path.is_dir()
            && let Some(receipt) = find_task_receipt(&path)
        {
            return Some(receipt);
        }
    }
    None
}

fn decode_json_output(output: &Output) -> serde_json::Value {
    serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "task JSON output: {error}; stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        )
    })
}

fn sha256_file(path: &Path) -> String {
    hex::encode(Sha256::digest(
        std::fs::read(path).expect("hash fixture file"),
    ))
}

#[test]
fn installed_launcher_completes_one_toon_task_with_compact_current_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let fixture = InstalledTaskFixture::new(temp.path(), "kt");
    let begin = fixture
        .task("installed-task-session", "begin")
        .output()
        .expect("begin installed task");
    let (_, begin) = decode_default_toon("task begin", begin);
    assert_eq!(begin["result"]["state"], "ACTIVE", "{begin:#}");

    let source = fixture.source("kt");
    std::fs::write(&source, "class Example(val value: String)\n").expect("modify source");
    let content_hash = sha256_file(&source);
    let file_path = source.display().to_string();
    let backend = spawn_scripted_idea_backend_for_invocations(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &temp.path().join("agent-task-idea.sock"),
        ScriptedCliAuthority::new(&fixture.binary, &cli_version(&fixture.binary)),
        3,
        vec![
            (
                "mutation/finish-barrier/acquire",
                serde_json::json!({
                    "workspaceTaskId": begin["result"]["taskId"],
                    "coordinationToken": uuid::Uuid::nil().to_string(),
                    "state": "DRAINED"
                }),
            ),
            (
                "raw/workspace-refresh",
                serde_json::json!({
                    "refreshedFiles": [file_path],
                    "removedFiles": [],
                    "fullRefresh": false,
                    "fileStatuses": [{
                        "filePath": source.display().to_string(),
                        "fileSystemDiscovery": "DISCOVERED",
                        "sourceModuleOwnership": "OWNED",
                        "indexAdmission": "ADMITTED",
                        "analysisAvailability": "AVAILABLE",
                        "analysisStatus": {"filePath": source.display().to_string(), "state": "ANALYZED"}
                    }],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "removedFileCount": 0,
                    "attemptCount": 1,
                    "elapsedMillis": 0,
                    "schemaVersion": 3
                }),
            ),
            (
                "raw/diagnostics",
                serde_json::json!({
                    "diagnostics": [],
                    "fileStatuses": [{"filePath": source.display().to_string(), "state": "ANALYZED"}],
                    "fileHashes": [{"filePath": source.display().to_string(), "hash": content_hash}],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
                    "cardinality": {"type": "EXACT", "totalCount": 0},
                    "schemaVersion": 3
                }),
            ),
            (
                "mutation/finish-barrier/complete",
                serde_json::json!({
                    "workspaceTaskId": begin["result"]["taskId"],
                    "coordinationToken": uuid::Uuid::nil().to_string(),
                    "state": "COMPLETE"
                }),
            ),
        ],
    );

    let finish = fixture
        .task("installed-task-session", "finish")
        .output()
        .expect("finish installed task");
    let (_, finish) = decode_default_toon("task finish", finish);
    assert_eq!(finish["result"]["state"], "COMPLETE", "{finish:#}");
    assert_eq!(finish["result"]["taskId"], begin["result"]["taskId"]);
    assert_eq!(
        finish["result"]["workspaceRoot"],
        fixture.workspace.display().to_string(),
    );

    let current_receipt = fixture.receipt_path();
    let receipt_bytes = std::fs::read(&current_receipt).expect("current task state");
    assert!(receipt_bytes.len() < 2_048, "task state must stay compact");
    let receipt: serde_json::Value =
        serde_json::from_slice(&receipt_bytes).expect("task state JSON");
    for removed in [
        "baseline",
        "current",
        "gradleModel",
        "diagnostics",
        "gradle",
        "testReports",
        "validation",
        "completion",
    ] {
        assert!(
            receipt.get(removed).is_none(),
            "retained {removed}: {receipt:#}"
        );
    }

    let requests = backend.join().expect("task diagnostics backend");
    assert_eq!(
        requests
            .iter()
            .map(|request| request["method"].as_str().expect("method"))
            .collect::<Vec<_>>(),
        vec![
            "runtime/status",
            "capabilities",
            "mutation/finish-barrier/acquire",
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics",
            "runtime/status",
            "capabilities",
            "mutation/finish-barrier/complete",
        ],
    );
}

#[test]
fn task_lifecycle_is_idempotent_strict_and_retryable() {
    let temp = tempfile::tempdir().expect("tempdir");
    let fixture = InstalledTaskFixture::new(temp.path(), "java");
    let mut ownerless = Command::new(&fixture.launcher);
    ownerless
        .env("HOME", &fixture.home)
        .env("KAST_CONFIG_HOME", &fixture.config_home)
        .env("KAST_TEST_MODEL_RECEIPT", &fixture.model_receipt)
        .env("KAST_TEST_WORKSPACE", &fixture.workspace)
        .env_remove("KAST_AGENT_SESSION_ID")
        .env_remove("CODEX_THREAD_ID")
        .args(["begin", "--output", "json", "--workspace-root"])
        .arg(&fixture.workspace);
    let ownerless = ownerless.output().expect("ownerless task command");
    assert!(
        ownerless.status.success(),
        "ownerless begin stderr={}",
        String::from_utf8_lossy(&ownerless.stderr),
    );
    let ownerless = decode_json_output(&ownerless);
    let task_id = ownerless["result"]["taskId"].clone();
    assert_eq!(ownerless["result"]["schemaVersion"], 2);
    assert!(ownerless["result"].get("owner").is_none());
    assert!(ownerless["result"].get("lease").is_none());

    let begin_output = fixture.task_json("owner-a", "begin");
    assert!(
        begin_output.status.success(),
        "begin stderr={}",
        String::from_utf8_lossy(&begin_output.stderr)
    );
    let begin = decode_json_output(&begin_output);
    assert_eq!(begin["result"]["state"], "ACTIVE", "{begin:#}");
    assert_eq!(begin["result"]["taskId"], task_id);
    let baseline_sha256 = begin["result"]["baselineSha256"].clone();

    let source = fixture.source("java");
    std::fs::write(&source, "class Example { int value = 1; }\n").expect("modify source");
    let repeated = decode_json_output(&fixture.task_json("owner-a", "begin"));
    assert_eq!(repeated["result"]["taskId"], task_id);
    assert_eq!(repeated["result"]["baselineSha256"], baseline_sha256);
    assert_ne!(repeated["result"]["currentSha256"], baseline_sha256);

    let shared = decode_json_output(&fixture.task_json("owner-b", "begin"));
    assert_eq!(shared["result"]["taskId"], task_id);
    assert_eq!(shared["result"]["baselineSha256"], baseline_sha256);

    let home_output = Command::new(&fixture.binary)
        .env("HOME", &fixture.home)
        .env("KAST_CONFIG_HOME", &fixture.config_home)
        .env("KAST_AGENT_SESSION_ID", "owner-b")
        .arg("agent")
        .current_dir(&fixture.workspace)
        .output()
        .expect("shared task home");
    let (_, home) = decode_default_toon("task home", home_output);
    assert_eq!(home["activeTask"]["taskId"], task_id);
    assert_eq!(home["readiness"]["state"], "READY");

    let barrier_backend = spawn_scripted_idea_backend_for_invocations(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &temp.path().join("agent-task-barrier.sock"),
        ScriptedCliAuthority::new(&fixture.binary, &cli_version(&fixture.binary)),
        10,
        vec![
            ("mutation/finish-barrier/acquire", serde_json::json!({})),
            ("mutation/finish-barrier/reopen", serde_json::json!({})),
            ("mutation/finish-barrier/acquire", serde_json::json!({})),
            ("mutation/finish-barrier/reopen", serde_json::json!({})),
            ("mutation/finish-barrier/acquire", serde_json::json!({})),
            ("mutation/finish-barrier/reopen", serde_json::json!({})),
            ("mutation/finish-barrier/acquire", serde_json::json!({})),
            ("mutation/finish-barrier/complete", serde_json::json!({})),
            ("mutation/finish-barrier/acquire", serde_json::json!({})),
            ("mutation/finish-barrier/complete", serde_json::json!({})),
        ],
    );

    let mut invalid_outcome = fixture.task("owner-a", "finish");
    invalid_outcome
        .args(["--output", "json"])
        .env("KAST_TEST_GRADLE_OUTCOME", "NO_SOURCE");
    let invalid_outcome = invalid_outcome.output().expect("invalid Gradle outcome");
    assert!(!invalid_outcome.status.success());
    let invalid_outcome = decode_json_output(&invalid_outcome);
    assert_eq!(
        invalid_outcome["result"]["state"], "BLOCKED",
        "{invalid_outcome:#}"
    );
    assert_eq!(
        invalid_outcome["error"]["code"],
        "GRADLE_BUILD_TASK_INVALID",
    );

    let reports = fixture.workspace.join("build/test-results");
    if reports.exists() {
        std::fs::remove_dir_all(&reports).expect("remove stale report fixture");
    }
    let mut missing_report = fixture.task("owner-a", "finish");
    missing_report
        .args(["--output", "json"])
        .env("KAST_TEST_REPORT_MODE", "missing");
    let missing_report = missing_report.output().expect("missing report finish");
    assert!(!missing_report.status.success());
    let missing_report = decode_json_output(&missing_report);
    assert_eq!(
        missing_report["error"]["code"], "GRADLE_TEST_REPORT_REQUIRED",
        "{missing_report:#}",
    );

    let mut concurrent_change = fixture.task("owner-a", "finish");
    concurrent_change
        .args(["--output", "json"])
        .env("KAST_TEST_MUTATE_PATH", &source);
    let concurrent_change = concurrent_change
        .output()
        .expect("concurrent change finish");
    assert!(!concurrent_change.status.success());
    let concurrent_change = decode_json_output(&concurrent_change);
    assert_eq!(
        concurrent_change["error"]["code"], "WORKSPACE_CHANGED_DURING_VALIDATION",
        "{concurrent_change:#}",
    );

    let complete_output = fixture.task_json("owner-a", "finish");
    assert!(
        complete_output.status.success(),
        "retry stderr={}",
        String::from_utf8_lossy(&complete_output.stderr)
    );
    let complete = decode_json_output(&complete_output);
    assert_eq!(complete["result"]["state"], "COMPLETE", "{complete:#}");
    assert_eq!(complete["result"]["taskId"], task_id);
    assert_eq!(complete["result"]["baselineSha256"], baseline_sha256);
    let completed_current = complete["result"]["currentSha256"].clone();

    std::fs::write(&source, "class Example { int value = 2; }\n").expect("post-completion edit");
    let immutable = decode_json_output(&fixture.task_json("owner-a", "status"));
    assert_eq!(immutable["result"]["state"], "COMPLETE");
    assert_eq!(immutable["result"]["currentSha256"], completed_current);

    let next = decode_json_output(&fixture.task_json("owner-a", "begin"));
    assert_ne!(next["result"]["taskId"], task_id);
    let aborted = decode_json_output(&fixture.task_json("owner-a", "abort"));
    assert_eq!(aborted["result"]["state"], "ABORTED");
    let repeated_abort = decode_json_output(&fixture.task_json("owner-a", "abort"));
    assert_eq!(
        repeated_abort["result"]["taskId"],
        aborted["result"]["taskId"]
    );

    let no_op_task = decode_json_output(&fixture.task_json("owner-a", "begin"));
    let no_op = decode_json_output(&fixture.task_json("owner-a", "finish"));
    assert_eq!(no_op["result"]["taskId"], no_op_task["result"]["taskId"]);
    assert_eq!(no_op["result"]["state"], "COMPLETE");

    let drifted_task = decode_json_output(&fixture.task_json("owner-a", "begin"));
    assert_eq!(drifted_task["result"]["state"], "ACTIVE");
    fixture.write_install_manifest("task-fixture-generation-2");
    let drifted = decode_json_output(&fixture.task_json("owner-a", "status"));
    assert_eq!(drifted["result"]["state"], "BLOCKED", "{drifted:#}");
    assert_eq!(
        drifted["result"]["blockerCodes"][0],
        "AGENT_TASK_STALE_GENERATION",
    );
    let drifted_home = Command::new(&fixture.binary)
        .env("HOME", &fixture.home)
        .env("KAST_CONFIG_HOME", &fixture.config_home)
        .env("KAST_AGENT_SESSION_ID", "owner-a")
        .arg("agent")
        .current_dir(&fixture.workspace)
        .output()
        .expect("drifted task home");
    let (_, drifted_home) = decode_default_toon("drifted task home", drifted_home);
    assert_eq!(drifted_home["readiness"]["state"], "BLOCKED");
    assert_eq!(
        drifted_home["readiness"]["blocker"]["code"],
        "AGENT_TASK_STALE_GENERATION",
    );

    fixture.write_install_manifest("task-fixture-generation-1");
    let mut changed_launcher = std::fs::read(&fixture.launcher).expect("task launcher");
    changed_launcher.extend_from_slice(b"\n# resource-only generation change\n");
    std::fs::write(&fixture.launcher, changed_launcher).expect("changed task launcher");
    let resource_drift = Command::new(&fixture.binary)
        .env("HOME", &fixture.home)
        .env("KAST_CONFIG_HOME", &fixture.config_home)
        .env("KAST_AGENT_SESSION_ID", "owner-a")
        .args([
            "--output",
            "json",
            "agent",
            "task",
            "status",
            "--workspace-root",
        ])
        .arg(&fixture.workspace)
        .output()
        .expect("resource drift status");
    let resource_drift = decode_json_output(&resource_drift);
    assert_eq!(resource_drift["result"]["state"], "BLOCKED");
    assert_eq!(
        resource_drift["result"]["blockerCodes"][0],
        "AGENT_TASK_STALE_GENERATION",
    );

    let barrier_requests = barrier_backend.join().expect("finish barrier backend");
    assert_eq!(
        barrier_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| method.starts_with("mutation/finish-barrier/"))
            .count(),
        10,
    );
}

#[test]
fn task_repair_replaces_legacy_state_without_touching_workspace() {
    let temp = tempfile::tempdir().expect("tempdir");
    let fixture = InstalledTaskFixture::new(temp.path(), "java");
    let begun = decode_json_output(&fixture.task_json("legacy-owner", "begin"));
    let task_id = begun["result"]["taskId"]
        .as_str()
        .expect("task id")
        .to_string();
    let source = fixture.source("java");
    let changed = b"class Example { int preserved = 1; }\n";
    std::fs::write(&source, changed).expect("change source");

    let mut legacy: serde_json::Value =
        serde_json::from_slice(&std::fs::read(fixture.receipt_path()).expect("current task state"))
            .expect("current task JSON");
    legacy["schemaVersion"] = serde_json::json!(1);
    legacy["owner"] = serde_json::json!({
        "kind": "SESSION",
        "provider": "codex",
        "sessionSha256": "0".repeat(64),
    });
    legacy["lease"] = serde_json::json!({
        "leaseId": format!("ktl1.{}", uuid::Uuid::new_v4()),
        "owner": legacy["owner"].clone(),
        "acquiredAt": legacy["startedAt"].clone(),
    });
    std::fs::write(
        fixture.receipt_path(),
        serde_json::to_vec_pretty(&legacy).expect("legacy receipt JSON"),
    )
    .expect("legacy receipt");

    let repaired = decode_json_output(&fixture.task_json("another-session", "repair"));
    assert_eq!(repaired["result"]["taskId"], task_id);
    assert_eq!(repaired["result"]["state"], "ABORTED");
    assert_eq!(repaired["result"]["schemaVersion"], 2);
    assert!(repaired["result"].get("owner").is_none());
    assert!(repaired["result"].get("lease").is_none());
    assert_eq!(std::fs::read(&source).expect("preserved source"), changed);
    assert!(
        !fixture
            .receipt_path()
            .parent()
            .expect("receipt parent")
            .join(format!("{task_id}.aborted.json"))
            .exists()
    );

    let next = decode_json_output(&fixture.task_json("another-session", "begin"));
    assert_ne!(next["result"]["taskId"], task_id);
    assert_eq!(next["result"]["state"], "ACTIVE");
}

#[test]
fn task_repair_requests_cooperative_cancellation_from_a_live_finish_executor() {
    let temp = tempfile::tempdir().expect("tempdir");
    let fixture = InstalledTaskFixture::new(temp.path(), "java");
    let begun = decode_json_output(&fixture.task_json("live-finish", "begin"));
    let task_id = begun["result"]["taskId"].clone();
    let receipt_path = fixture.receipt_path();
    let mut receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("task state"))
            .expect("task state JSON");
    receipt["state"] = serde_json::json!("DRAINING");
    receipt["finishExecutor"] = serde_json::json!({
        "coordinationToken": "00000000-0000-0000-0000-000000000424",
        "pid": std::process::id(),
        "startedAt": "unix:1",
        "cancellationRequested": false
    });
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("draining task state"),
    )
    .expect("draining task state");

    let repaired = decode_json_output(&fixture.task_json("another-session", "repair"));
    assert_eq!(repaired["result"]["taskId"], task_id);
    assert_eq!(repaired["result"]["state"], "DRAINING");
    let persisted: serde_json::Value =
        serde_json::from_slice(&std::fs::read(receipt_path).expect("repaired task state"))
            .expect("repaired task state JSON");
    assert_eq!(persisted["finishExecutor"]["cancellationRequested"], true);
}

#[test]
fn task_repair_reopens_a_dead_finish_executor_without_touching_workspace() {
    let temp = tempfile::tempdir().expect("tempdir");
    let fixture = InstalledTaskFixture::new(temp.path(), "java");
    let begun = decode_json_output(&fixture.task_json("dead-finish", "begin"));
    let task_id = begun["result"]["taskId"].clone();
    let source = fixture.source("java");
    let changed = b"class Example { int preserved = 2; }\n";
    std::fs::write(&source, changed).expect("changed source");
    let receipt_path = fixture.receipt_path();
    let mut receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("task state"))
            .expect("task state JSON");
    receipt["state"] = serde_json::json!("VALIDATING");
    receipt["finishExecutor"] = serde_json::json!({
        "coordinationToken": "00000000-0000-0000-0000-000000000425",
        "pid": u32::MAX,
        "startedAt": "unix:1",
        "cancellationRequested": false
    });
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("validating task state"),
    )
    .expect("validating task state");
    let backend = spawn_scripted_idea_backend_for_invocations(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &temp.path().join("agent-task-repair.sock"),
        ScriptedCliAuthority::new(&fixture.binary, &cli_version(&fixture.binary)),
        1,
        vec![("mutation/finish-barrier/repair", serde_json::json!({}))],
    );

    let repaired = decode_json_output(&fixture.task_json("another-session", "repair"));
    assert_eq!(repaired["result"]["taskId"], task_id);
    assert_eq!(repaired["result"]["state"], "BLOCKED");
    assert_eq!(
        repaired["result"]["blockerCodes"][0],
        "AGENT_TASK_FINISH_INTERRUPTED",
    );
    assert_eq!(std::fs::read(source).expect("preserved source"), changed);
    let requests = backend.join().expect("repair backend");
    assert_eq!(
        requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec![
            "runtime/status",
            "capabilities",
            "mutation/finish-barrier/repair"
        ],
    );
}

#[test]
fn selected_installed_cli_resolves_once_and_reuses_handle_across_default_toon_operations() {
    let cli_binary = selected_installed_binary();
    assert!(
        cli_binary.is_file(),
        "Cargo-built CLI does not exist: {}",
        cli_binary.display(),
    );
    let cli_version = cli_version(&cli_binary);
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
        ScriptedCliAuthority::new(&cli_binary, &cli_version),
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
    let resolved = kast_at(&cli_binary, &home, &config_home)
        .args([
            "agent",
            "symbol",
            "--query",
            "lib.Bar",
            "--workspace-root",
            workspace_root,
        ])
        .output()
        .expect("run selector resolve");
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

    let references = kast_at(&cli_binary, &home, &config_home)
        .args([
            "agent",
            "references",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace_root,
        ])
        .output()
        .expect("run references");
    let (references_toon, references) = decode_default_toon("references", references);
    assert_eq!(references["result"]["outcome"], "AVAILABLE");
    assert_eq!(references["result"]["subject"], identity);
    assert_eq!(references["result"]["coverage"]["type"], "COMPLETE");
    assert_eq!(references["result"]["limitations"], serde_json::json!([]));

    let callers = kast_at(&cli_binary, &home, &config_home)
        .args([
            "agent",
            "callers",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace_root,
        ])
        .output()
        .expect("run callers");
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
        "selector handle workflow must resolve once and never rediscover by name",
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
