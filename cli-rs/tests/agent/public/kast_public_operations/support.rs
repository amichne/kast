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
    let socket_name = format!("p{}.sock", &uuid::Uuid::new_v4().simple().to_string()[..6]);
    let backend = spawn_scripted_indexer_backend(
        home,
        config_home,
        workspace,
        &home.join(socket_name),
        vec![(
            "raw/plan-add-file",
            public_exact_add_file_preview(workspace, &target, content),
        )],
    );
    let mut change = installed_public_kast(binary, home, config_home, workspace);
    change.args(["change", "plan", "add-file", "--file", relative_path]);
    let change = run_with_stdin(change, content);
    backend.join().expect("add-file planner backend");
    change
}

pub(super) fn public_addition_collision_dimensions() -> Value {
    json!([
        "EXACT_DECLARATION_IDENTITIES",
        "COMPLETE_OWNING_SOURCE_SCOPE",
        "COMPLETE_DEPENDENT_SCOPE",
        "NO_COMPILER_COLLISION",
    ])
}

pub(super) fn public_addition_rebinding_dimensions() -> Value {
    json!([
        "EXACT_OCCURRENCE_CARDINALITY",
        "COMPLETE_DEPENDENT_SCOPE",
        "COMPLETE_IMPLICIT_LOOKUP_SCOPE",
        "COMPLETE_JAVA_LOOKUP_SCOPE",
        "EVERY_CURRENT_BINDING_CAPTURED",
        "VIRTUAL_PROPOSED_BINDINGS_EQUAL_BASELINE",
    ])
}

pub(super) fn public_addition_owner(workspace: &Path, target: &Path) -> Value {
    json!({
        "sourceRoot": target.parent().expect("target source root"),
        "ideaModuleName": "root.main",
        "gradleBuildRoot": workspace,
        "gradleProjectPath": ":",
        "sourceSetName": "main",
    })
}

pub(super) fn public_addition_declaration(content_length: usize) -> Value {
    json!({
        "packageIdentity": {"type": "ROOT"},
        "name": "Added",
        "kind": "CLASS",
        "relativeRange": {"startOffset": 0, "endOffset": content_length},
        "collisionSignature": "1".repeat(64),
    })
}

pub(super) fn public_addition_context(context_file_hashes: Vec<Value>) -> Value {
    json!({
        "requiredGeneration": 7,
        "projectModelFingerprint": "2".repeat(64),
        "classpathFingerprint": "3".repeat(64),
        "contextFileHashes": context_file_hashes,
    })
}

pub(super) fn public_exact_add_file_preview(
    workspace: &Path,
    target: &Path,
    content: &str,
) -> Value {
    let sha256 = source_sha256(content.as_bytes());
    json!({
        "proposedContent": content,
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(content.as_bytes()),
            "sha256": sha256,
        },
        "proof": {
            "targetPath": target,
            "targetState": "ABSENT",
            "owner": public_addition_owner(workspace, target),
            "packageIdentity": {"type": "ROOT"},
            "declarations": [public_addition_declaration(content.encode_utf16().count())],
            "context": public_addition_context(Vec::new()),
            "collisionEvidence": {
                "declarationCardinality": 1,
                "dimensions": public_addition_collision_dimensions(),
            },
            "outboundEvidence": {"cardinality": 0, "occurrences": []},
            "rebindingBaseline": {
                "cardinality": 0,
                "dimensions": public_addition_rebinding_dimensions(),
                "occurrences": [],
            },
            "postimageSha256": sha256,
        },
        "schemaVersion": 7,
    })
}

pub(super) fn successful_add_file_result(target: &Path) -> Value {
    json!({
        "type": "SUCCEEDED",
        "result": {
            "type": "SCOPE_MUTATION_RESULT",
            "response": {
                "editCount": 1,
                "affectedFiles": [],
                "createdFiles": [target],
                "diagnostics": {"errorCount": 0, "warningCount": 0}
            }
        },
        "deduplicated": false
    })
}

pub(super) fn source_sha256(content: &[u8]) -> String {
    hex::encode(Sha256::digest(content))
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
