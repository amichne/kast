use super::*;

static ADD_FILE_PLAN_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

pub(super) fn kast(home: &Path, config_home: &Path, workspace: &Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_HOME", home.join(".local/share/kast"))
        .env("KAST_CONFIG_HOME", config_home);
    command
}

pub(super) fn installed_public_kast(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
) -> Command {
    let mut command = kast_at(binary, home, config_home);
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("KAST_HOME", home.join(".local/share/kast"));
    command
}

pub(super) fn run_with_stdin(mut command: Command, stdin: &str) -> Output {
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

pub(super) fn plan_add_file(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    relative_path: &str,
    content: &str,
) -> String {
    let _plan_guard = ADD_FILE_PLAN_LOCK.lock().expect("add-file plan lock");
    let change = change_add_file(binary, home, config_home, workspace, relative_path, content);
    assert!(
        change.status.success(),
        "change should succeed: stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    decode(&change)["planId"]
        .as_str()
        .expect("plan id")
        .to_string()
}

pub(super) fn change_add_file(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    relative_path: &str,
    content: &str,
) -> Output {
    let target = workspace.join(relative_path);
    let plan_id = verified_add_file_plan_id(&target, content.as_bytes());
    let socket_name = format!("p{}.sock", &uuid::Uuid::new_v4().simple().to_string()[..6]);
    let backend = spawn_scripted_indexer_backend(
        home,
        config_home,
        workspace,
        &home.join(socket_name),
        vec![(
            "change/plan-add-file",
            json!({
                "planId": plan_id,
                "planVersion": 0,
                "stage": "AWAITING_APPROVAL",
                "operation": "add-file",
                "preview": {
                    "targetPath": target,
                    "proposedContent": content,
                    "generation": 7,
                },
                "schemaVersion": 7,
            }),
        )],
    );
    let mut change = installed_public_kast(binary, home, config_home, workspace);
    change.args(["change", "plan", "add-file", "--file", relative_path]);
    let change = run_with_stdin(change, content);
    backend.join().expect("add-file planner backend");
    change
}

pub(super) fn source_sha256(content: &[u8]) -> String {
    hex::encode(Sha256::digest(content))
}

pub(super) fn verified_add_file_plan_id(target: &Path, content: &[u8]) -> String {
    let workspace = target
        .ancestors()
        .find(|candidate| candidate.join("settings.gradle.kts").is_file())
        .expect("target belongs to a Gradle workspace");
    format!(
        "af-{}",
        source_sha256(
            format!(
                "{}\0{}\0{}\07",
                workspace.display(),
                target.display(),
                std::str::from_utf8(content).expect("Kotlin content"),
            )
            .as_bytes(),
        ),
    )
}

pub(super) fn verified_add_file_receipt(target: &Path, content: &[u8]) -> Value {
    json!({
        "outcome": "VERIFIED",
        "planId": verified_add_file_plan_id(target, content),
        "planVersion": 5,
        "operation": "add-file",
        "publication": {"generation": 8},
        "identity": {
            "targetPath": target,
            "packageName": "sample",
            "declarations": [{"name": "Added", "kind": "CLASS"}],
        },
        "postimageSha256": source_sha256(content),
        "schemaVersion": 7,
    })
}

pub(super) fn verified_add_file_recovery_required(
    target: &Path,
    content: &[u8],
    progress: &str,
) -> Value {
    let failure = if progress == "WORKSPACE_PUBLICATION" {
        "PUBLICATION_FAILED"
    } else {
        "PSI_NOT_ADMITTED"
    };
    verified_add_file_recovery_required_with_failure(target, content, progress, failure)
}

pub(super) fn verified_add_file_recovery_required_with_failure(
    target: &Path,
    content: &[u8],
    progress: &str,
    failure: &str,
) -> Value {
    let stage = verified_add_file_recovery_stage(progress);
    json!({
        "outcome": "RECOVERY_REQUIRED",
        "planId": verified_add_file_plan_id(target, content),
        "recoveryId": verified_add_file_plan_id(target, content),
        "planVersion": 0,
        "stage": stage,
        "progress": progress,
        "failure": failure,
        "recoveryAction": "DELETE_CREATED_TARGET",
        "operation": "add-file",
        "schemaVersion": 7,
    })
}

pub(super) fn verified_add_file_rolled_back(
    target: &Path,
    content: &[u8],
    progress: &str,
    failure: &str,
) -> Value {
    let stage = verified_add_file_recovery_stage(progress);
    json!({
        "outcome": "ROLLED_BACK",
        "planId": verified_add_file_plan_id(target, content),
        "planVersion": 5,
        "stage": stage,
        "progress": progress,
        "failure": failure,
        "recoveryAction": "DELETE_CREATED_TARGET",
        "operation": "add-file",
        "schemaVersion": 7,
    })
}

pub(super) fn verified_add_file_rejected(
    target: &Path,
    content: &[u8],
    progress: &str,
    failure: &str,
) -> Value {
    let stage = match progress {
        "INTENT_ADMISSION" | "PLANNING" => "AWAITING_APPROVAL",
        "REVALIDATION" => "APPROVED",
        "RECOVERY_PREPARATION" => "RECOVERY_PREPARED",
        "SOURCE_APPLICATION" => "APPLY_ADMITTED",
        other => panic!("rejected add-file progress has no closed stage: {other}"),
    };
    json!({
        "outcome": "REJECTED",
        "planId": verified_add_file_plan_id(target, content),
        "planVersion": 0,
        "stage": stage,
        "progress": progress,
        "failure": failure,
        "operation": "add-file",
        "schemaVersion": 7,
    })
}

pub(super) fn verified_add_file_reconciliation_required(
    target: &Path,
    content: &[u8],
    progress: &str,
    failure: &str,
) -> Value {
    let stage = verified_add_file_recovery_stage(progress);
    json!({
        "outcome": "RECONCILIATION_REQUIRED",
        "planId": verified_add_file_plan_id(target, content),
        "recoveryId": verified_add_file_plan_id(target, content),
        "planVersion": 0,
        "stage": stage,
        "progress": progress,
        "failure": failure,
        "reconciliationAction": "INSPECT_TARGET",
        "operation": "add-file",
        "schemaVersion": 7,
    })
}

fn verified_add_file_recovery_stage(progress: &str) -> &'static str {
    match progress {
        "SOURCE_APPLICATION" => "APPLY_ADMITTED",
        "WORKSPACE_PUBLICATION" | "PSI_ADMISSION" => "APPLIED_UNVERIFIED",
        other => panic!("add-file recovery progress has no closed stage: {other}"),
    }
}

pub(super) fn independent_refresh(file: &Path) -> Value {
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
        "elapsedMillis": 1,
        "schemaVersion": 7
    })
}

pub(super) fn diagnostic(
    file: &Path,
    severity: &str,
    code: &str,
    message: &str,
    start_offset: usize,
) -> Value {
    json!({
        "location": {
            "filePath": file,
            "startOffset": start_offset,
            "endOffset": start_offset + 1,
            "startLine": 1,
            "startColumn": start_offset + 1,
            "preview": "evidence"
        },
        "severity": severity,
        "message": message,
        "code": code
    })
}

#[allow(clippy::too_many_arguments)]
pub(super) fn independent_diagnostics(
    file: &Path,
    hash: &str,
    diagnostics: Vec<Value>,
    errors: usize,
    warnings: usize,
    infos: usize,
    total: usize,
    page: Option<Value>,
) -> Value {
    let mut result = json!({
        "diagnostics": diagnostics,
        "fileStatuses": [{"filePath": file, "state": "ANALYZED"}],
        "fileHashes": [{"filePath": file, "hash": hash}],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {
            "error": errors,
            "warning": warnings,
            "info": infos,
            "total": total
        },
        "cardinality": {"type": "EXACT", "totalCount": total},
        "schemaVersion": 7
    });
    if let Some(page) = page {
        result["page"] = page;
    }
    result
}

#[path = "support/output.rs"]
mod output;
#[path = "support/replacement.rs"]
mod replacement;
#[path = "support/verification_failure.rs"]
mod verification_failure;
#[path = "support/verified_add_declaration.rs"]
mod verified_add_declaration;
pub(super) use output::{assert_selector_forwarding, decode, decode_envelope};
pub(super) use replacement::{plan_replacement, replacement_fixture};
pub(super) use verification_failure::{
    assert_independent_verification_failure_rolls_back, successful_verified_add_file_script,
};
pub(super) use verified_add_declaration::{
    verified_add_declaration_plan_result, verified_add_declaration_receipt,
};
