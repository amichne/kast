#[path = "../../support/mod.rs"]
mod support;

use base64::{Engine as _, engine::general_purpose::STANDARD as STANDARD_BASE64};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};
use support::{
    api_schema_version, kast_at, scripted_json_rpc_error,
    scripted_json_rpc_error_with_retained_artifact, spawn_gated_foreign_prepared_scratch_backend,
    spawn_gated_mutating_indexer_backend_with_file_write,
    spawn_gated_prepared_scratch_crash_backend, spawn_gated_quarantine_scratch_crash_backend,
    spawn_lease_only_mutating_indexer_backend, spawn_scripted_indexer_backend,
    spawn_scripted_indexer_backend_for_invocations, spawn_scripted_mutating_indexer_backend,
    spawn_scripted_mutating_indexer_backend_with_file_write, workspace_database_path_for_test,
    workspace_files::WorkspaceIndexFixture, write_active_kast_for_test,
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

fn installed_public_kast(
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

fn plan_add_file(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    relative_path: &str,
    content: &str,
) -> String {
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

#[allow(clippy::too_many_arguments)]
fn plan_add_declaration(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket: &Path,
    target: &Path,
    declaration: &str,
    preview: Value,
) -> String {
    let backend = spawn_scripted_indexer_backend(
        home,
        config_home,
        workspace,
        socket,
        vec![("raw/plan-add-declaration", preview)],
    );
    let mut change = installed_public_kast(binary, home, config_home, workspace);
    change.args([
        "change",
        "add-declaration",
        target.to_str().expect("target"),
    ]);
    let change = run_with_stdin(change, declaration);
    assert!(change.status.success(), "{change:?}");
    backend.join().expect("add-declaration planner backend");
    decode(&change)["planId"]
        .as_str()
        .expect("plan id")
        .to_string()
}

fn change_add_file(
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
    change.args(["change", "add-file", relative_path]);
    let change = run_with_stdin(change, content);
    backend.join().expect("add-file planner backend");
    change
}

fn public_addition_collision_dimensions() -> Value {
    json!([
        "EXACT_DECLARATION_IDENTITIES",
        "COMPLETE_OWNING_SOURCE_SCOPE",
        "COMPLETE_DEPENDENT_SCOPE",
        "NO_COMPILER_COLLISION",
    ])
}

fn public_addition_rebinding_dimensions() -> Value {
    json!([
        "EXACT_OCCURRENCE_CARDINALITY",
        "COMPLETE_DEPENDENT_SCOPE",
        "COMPLETE_IMPLICIT_LOOKUP_SCOPE",
        "COMPLETE_JAVA_LOOKUP_SCOPE",
        "EVERY_CURRENT_BINDING_CAPTURED",
        "VIRTUAL_PROPOSED_BINDINGS_EQUAL_BASELINE",
    ])
}

fn public_addition_owner(workspace: &Path, target: &Path) -> Value {
    json!({
        "sourceRoot": target.parent().expect("target source root"),
        "ideaModuleName": "root.main",
        "gradleBuildRoot": workspace,
        "gradleProjectPath": ":",
        "sourceSetName": "main",
    })
}

fn public_addition_declaration(content_length: usize) -> Value {
    json!({
        "packageIdentity": {"type": "ROOT"},
        "name": "Added",
        "kind": "CLASS",
        "relativeRange": {"startOffset": 0, "endOffset": content_length},
        "collisionSignature": "1".repeat(64),
    })
}

fn public_addition_context(context_file_hashes: Vec<Value>) -> Value {
    json!({
        "requiredGeneration": 7,
        "projectModelFingerprint": "2".repeat(64),
        "classpathFingerprint": "3".repeat(64),
        "contextFileHashes": context_file_hashes,
    })
}

fn public_exact_add_file_preview(workspace: &Path, target: &Path, content: &str) -> Value {
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
        "schemaVersion": 6,
    })
}

fn public_exact_add_declaration_preview(
    workspace: &Path,
    target: &Path,
    preimage: &[u8],
    declaration: &str,
) -> Value {
    let normalized_preimage = std::str::from_utf8(preimage)
        .expect("UTF-8 fixture")
        .strip_prefix('\u{feff}')
        .unwrap_or(std::str::from_utf8(preimage).expect("UTF-8 fixture"))
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let separator = if normalized_preimage.is_empty() || normalized_preimage.ends_with("\n\n") {
        ""
    } else if normalized_preimage.ends_with('\n') {
        "\n"
    } else {
        "\n\n"
    };
    let mut postimage = preimage.to_vec();
    postimage.extend_from_slice(format!("{separator}{declaration}\n").as_bytes());
    let preimage_sha256 = source_sha256(preimage);
    let postimage_sha256 = source_sha256(&postimage);
    json!({
        "proposedDeclaration": declaration,
        "proposedContent": String::from_utf8(postimage.clone()).expect("UTF-8 fixture"),
        "image": {
            "filePath": target,
            "preimage": {
                "contentBase64": STANDARD_BASE64.encode(preimage),
                "sha256": preimage_sha256,
            },
            "postimage": {
                "contentBase64": STANDARD_BASE64.encode(&postimage),
                "sha256": postimage_sha256,
            },
        },
        "proof": {
            "targetPath": target,
            "targetPreimageSha256": preimage_sha256,
            "owner": public_addition_owner(workspace, target),
            "packageIdentity": {"type": "ROOT"},
            "declaration": public_addition_declaration(declaration.encode_utf16().count()),
            "insertion": {"offset": normalized_preimage.encode_utf16().count()},
            "newlinePolicy": "PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF",
            "context": public_addition_context(vec![json!({
                "filePath": target,
                "sha256": preimage_sha256,
            })]),
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
            "postimageSha256": postimage_sha256,
        },
        "schemaVersion": 6,
    })
}

fn successful_add_file_result(target: &Path) -> Value {
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

fn source_sha256(content: &[u8]) -> String {
    hex::encode(Sha256::digest(content))
}

fn independent_refresh(file: &Path) -> Value {
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
        "schemaVersion": 6
    })
}

fn diagnostic(
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
fn independent_diagnostics(
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
        "cardinality": {"type": "EXACT", "totalCount": total}
    });
    if let Some(page) = page {
        result["page"] = page;
    }
    result
}

fn successful_verified_add_file_script(
    target: &Path,
    content: &[u8],
) -> Vec<(&'static str, Value)> {
    vec![
        ("mutation/submit", successful_add_file_result(target)),
        ("raw/workspace-refresh", independent_refresh(target)),
        (
            "raw/diagnostics",
            independent_diagnostics(target, &source_sha256(content), vec![], 0, 0, 0, 0, None),
        ),
    ]
}

fn assert_independent_verification_failure_rolls_back(
    case: &str,
    verification_script: impl FnOnce(&Path, &str) -> Vec<(&'static str, Value)>,
) {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Unverified.kt");
    let content = b"package sample\nclass Unverified\n";
    let content_hash = source_sha256(content);
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Unverified.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let verification_script = verification_script(&target, &content_hash);
    let mut script = vec![("mutation/submit", successful_add_file_result(&target))];
    script.extend(verification_script.clone());
    let apply_socket = fixture.path().join(format!("{case}-apply.sock"));
    let apply_backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &apply_socket,
        &target,
        content,
        script,
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("apply with unavailable compiler verification");
    let apply_requests = apply_backend.join().expect("apply backend");
    assert_eq!(
        apply.status.code(),
        Some(1),
        "{apply:?}; methods={:?}",
        apply_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>()
    );
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "RECOVERY_REQUIRED", "{receipt:#}");
    assert_eq!(receipt["recoveryId"], plan_id);
    assert_eq!(
        std::fs::read(&target).expect("unverified postimage"),
        content
    );
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let recover_socket = fixture.path().join(format!("{case}-recover.sock"));
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        verification_script,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover unverified postimage in a new process");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let recovered = decode(&recovered);
    assert_eq!(recovered["outcome"], "ROLLED_BACK", "{recovered:#}");
    assert!(!target.exists(), "recovery restored the absent pre-state");

    let recover_requests = recover_backend.join().expect("recovery backend");
    assert_eq!(
        recover_requests
            .iter()
            .filter(|request| request["method"] == "mutation/submit")
            .count(),
        0,
        "recovery must not resubmit an unverified mutation",
    );
}

#[test]
fn public_change_exposes_only_the_four_verified_mutations() {
    let fixture = tempfile::tempdir().expect("fixture");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let help = Command::new(env!("CARGO_BIN_EXE_kast"))
        .arg0("kast")
        .current_dir(&workspace)
        .args(["change", "--help"])
        .output()
        .expect("public change help");
    assert!(
        help.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&help.stdout),
        String::from_utf8_lossy(&help.stderr),
    );
    let help = String::from_utf8(help.stdout).expect("UTF-8 help");
    for operation in ["rename", "replace", "add-file", "add-declaration"] {
        assert!(help.contains(operation), "missing {operation}:\n{help}");
    }
    for operation in ["add-implementation", "add-statement"] {
        assert!(!help.contains(operation), "unexpected {operation}:\n{help}");
    }
}

#[test]
fn public_apply_owns_lease_and_returns_verified_receipt() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"verified-apply\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);

    let change = change_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    assert!(
        change.status.success(),
        "change should succeed: stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    let change = decode(&change);
    let plan_id = change["planId"].as_str().expect("plan id");

    let socket = fixture.path().join("verified-apply.sock");
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        successful_verified_add_file_script(&target, content),
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", plan_id])
        .output()
        .expect("public apply");
    assert!(
        apply.status.success(),
        "public apply should acquire and release its own lease: stdout={} stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(receipt["schemaVersion"], 6, "{receipt:#}");
    assert_eq!(receipt["lease"]["state"], "RELEASED", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("created source"), content);

    let requests = backend.join().expect("mutation backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", plan_id])
        .output()
        .expect("terminal receipt replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal replay must be stable");
}

#[test]
fn terminal_verified_receipt_persistence_failure_replays_from_durable_journal() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Terminal.kt");
    let content = b"package sample\nclass Terminal\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Terminal.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let apply_backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-apply.sock"),
        &target,
        content,
        successful_verified_add_file_script(&target, content),
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "TERMINAL_RECEIPT_PERSISTENCE",
        )
        .args(["apply", &plan_id])
        .output()
        .expect("terminal persistence failure");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    assert_eq!(decode(&interrupted)["outcome"], "RECOVERY_REQUIRED");
    assert_eq!(std::fs::read(&target).expect("retained postimage"), content);
    apply_backend.join().expect("apply backend");

    let recover_shutdown = fixture.path().join("terminal-persistence-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-recover.sock"),
        &recover_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("new-process recovery");
    assert!(recovered.status.success(), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    std::fs::write(&recover_shutdown, "stop\n").expect("stop recovery backend");
    let recovery_requests = recover_backend.join().expect("recovery backend");
    assert_eq!(
        recovery_requests
            .iter()
            .filter(|request| {
                matches!(
                    request["method"].as_str(),
                    Some("raw/apply-edits" | "raw/exact-file-image-cas")
                )
            })
            .count(),
        0,
        "verified journal replay must not write source again"
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("terminal retry");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
}

#[test]
fn public_apply_requires_complete_independent_diagnostics() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Warnings.kt");
    let content = b"package sample\nclass Warnings\n";
    let content_hash = source_sha256(content);
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Warnings.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let next_page = "00000000-0000-4000-8000-000000000337";
    let socket = fixture.path().join("independent-diagnostics.sock");
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        vec![
            ("mutation/submit", successful_add_file_result(&target)),
            ("raw/workspace-refresh", independent_refresh(&target)),
            (
                "raw/diagnostics",
                independent_diagnostics(
                    &target,
                    &content_hash,
                    vec![diagnostic(
                        &target,
                        "WARNING",
                        "STYLE",
                        "Spacing   warning\nfrom compiler",
                        1,
                    )],
                    0,
                    2,
                    1,
                    3,
                    Some(json!({"truncated": true, "nextPageToken": next_page})),
                ),
            ),
            (
                "raw/diagnostics",
                independent_diagnostics(
                    &target,
                    &content_hash,
                    vec![
                        diagnostic(
                            &target,
                            "WARNING",
                            "STYLE",
                            "Spacing   warning\nfrom compiler",
                            7,
                        ),
                        diagnostic(&target, "INFO", "NOTE", "Compiler note", 10),
                    ],
                    0,
                    2,
                    1,
                    3,
                    Some(json!({"truncated": false})),
                ),
            ),
        ],
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("independently verified apply");
    assert!(
        apply.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(
        receipt["compilerVerification"]["preDiagnostics"]["outcome"], "COMPLETE",
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["preDiagnostics"]["fileHashes"],
        json!([]),
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["outcome"], "COMPLETE",
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["fileHashes"][0]["sha256"],
        content_hash,
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["severityCounts"],
        json!({"error": 0, "warning": 2, "info": 1, "total": 3}),
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["diagnostics"][0]["identity"]
            ["message"],
        "Spacing warning from compiler",
    );
    let identity_counts =
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["identityCounts"]
            .as_array()
            .expect("diagnostic identity multiset");
    assert!(
        identity_counts
            .iter()
            .any(|entry| { entry["identity"]["code"] == "STYLE" && entry["count"] == 2 })
    );

    let requests = backend.join().expect("independent diagnostics backend");
    let semantic_methods = requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| {
            matches!(
                *method,
                "raw/apply-edits" | "raw/workspace-refresh" | "raw/diagnostics"
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_methods,
        [
            "raw/apply-edits",
            "raw/workspace-refresh",
            "raw/diagnostics",
            "raw/diagnostics"
        ],
    );
    let diagnostic_requests = requests
        .iter()
        .filter(|request| request["method"] == "raw/diagnostics")
        .collect::<Vec<_>>();
    assert!(diagnostic_requests[0]["params"].get("pageToken").is_none());
    assert_eq!(diagnostic_requests[1]["params"]["pageToken"], next_page);
}

#[test]
fn public_apply_requires_a_successful_exact_file_refresh() {
    assert_independent_verification_failure_rolls_back("refresh-failure", |_target, _hash| {
        vec![(
            "raw/workspace-refresh",
            json!({"error": "refresh unavailable"}),
        )]
    });
}

#[test]
fn public_apply_rejects_incomplete_truncated_diagnostics() {
    assert_independent_verification_failure_rolls_back("incomplete-diagnostics", |target, hash| {
        let mut diagnostics = independent_diagnostics(
            target,
            hash,
            vec![],
            0,
            0,
            0,
            0,
            Some(json!({
                "truncated": true,
                "nextPageToken": "00000000-0000-4000-8000-000000000338"
            })),
        );
        diagnostics["semanticOutcome"] = json!("INCOMPLETE");
        vec![
            ("raw/workspace-refresh", independent_refresh(target)),
            ("raw/diagnostics", diagnostics),
        ]
    });
}

#[test]
fn public_apply_rejects_a_new_compiler_error() {
    assert_independent_verification_failure_rolls_back("new-error", |target, hash| {
        vec![
            ("raw/workspace-refresh", independent_refresh(target)),
            (
                "raw/diagnostics",
                independent_diagnostics(
                    target,
                    hash,
                    vec![diagnostic(
                        target,
                        "ERROR",
                        "UNRESOLVED_REFERENCE",
                        "Unresolved reference: Missing",
                        10,
                    )],
                    1,
                    0,
                    0,
                    1,
                    None,
                ),
            ),
        ]
    });
}

#[test]
fn public_apply_rejects_malformed_diagnostic_locations() {
    assert_independent_verification_failure_rolls_back(
        "malformed-diagnostic-location",
        |target, hash| {
            let mut malformed = diagnostic(target, "WARNING", "STYLE", "Style warning", 1);
            malformed["location"]["startLine"] = json!(0);
            vec![
                ("raw/workspace-refresh", independent_refresh(target)),
                (
                    "raw/diagnostics",
                    independent_diagnostics(target, hash, vec![malformed], 0, 1, 0, 1, None),
                ),
            ]
        },
    );
}

#[test]
fn public_recover_restores_absent_prestate_after_prepared_journal_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = "package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        content,
    );
    let socket = fixture.path().join("prepared-recovery.sock");
    let shutdown = fixture.path().join("prepared-recovery.shutdown");
    let backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        &shutdown,
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_RECOVERY_JOURNAL")
        .args(["apply", &plan_id])
        .output()
        .expect("interrupted apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let interrupted = decode(&interrupted);
    assert_eq!(
        interrupted["outcome"], "RECOVERY_REQUIRED",
        "{interrupted:#}"
    );
    assert_eq!(interrupted["recoveryId"], plan_id);
    assert!(!target.exists(), "interruption happened before mutation");

    std::fs::write(&shutdown, "stop\n").expect("stop prepared backend");
    let requests = backend.join().expect("prepared backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        0,
        "journal persistence must precede mutation submission",
    );

    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored_plan_bytes = std::fs::read(&plan_path).expect("stored plan");
    let stored_plan: Value = serde_json::from_slice(&stored_plan_bytes).expect("stored plan JSON");
    assert_eq!(stored_plan["state"]["state"], "PLANNED");
    assert!(
        !String::from_utf8_lossy(&stored_plan_bytes).contains("RECOVERY_REQUIRED"),
        "RECOVERY_REQUIRED must not become a terminal replay state",
    );
    let journal: Value =
        serde_json::from_slice(&std::fs::read(&journal_path).expect("durable recovery journal"))
            .expect("recovery JSON");
    assert_eq!(journal["recoveryId"], plan_id);
    assert_eq!(journal["workspaceRoot"], workspace.display().to_string());
    assert_eq!(journal["transitions"].as_array().map(Vec::len), Some(1));
    assert_eq!(
        journal["transitions"][0]["relativePath"],
        "src/main/kotlin/Added.kt"
    );
    assert_eq!(
        journal["transitions"][0]["absolutePath"],
        target.display().to_string()
    );
    assert_eq!(journal["transitions"][0]["preimage"]["state"], "ABSENT");
    assert_eq!(
        journal["transitions"][0]["postimage"]["sha256"],
        source_sha256(content.as_bytes()),
    );
    assert_eq!(
        std::fs::metadata(&journal_path)
            .expect("journal metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600,
    );

    let recover_socket = fixture.path().join("prepared-recovery-second-process.sock");
    let recover_shutdown = fixture
        .path()
        .join("prepared-recovery-second-process.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        &recover_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover in a new process");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert!(!target.exists(), "exact absent pre-state is retained");
    std::fs::write(&recover_shutdown, "stop\n").expect("stop recovery backend");
    let recovery_requests = recover_backend.join().expect("recovery backend");
    assert!(
        recovery_requests
            .iter()
            .any(|request| request["method"] == "raw/workspace-refresh"),
        "rollback must refresh the exact absent pre-state"
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("terminal recovery replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal recovery is stable");
}

#[test]
fn public_recover_rejects_pre_diagnostic_evidence_not_bound_to_exact_preimages() {
    let fixture = tempfile::tempdir().expect("fixture");
    let mut recovered_outcomes = Vec::new();
    for tamper in ["hash", "foreign-diagnostic"] {
        let root = fixture.path().join(tamper);
        let home = root.join("home");
        let config_home = root.join("config");
        let workspace = root.join("workspace");
        let source_root = workspace.join("src/main/kotlin");
        std::fs::create_dir_all(&source_root).expect("source root");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
        let target = source_root.join("Existing.kt");
        let preimage = b"class Existing\n";
        std::fs::write(&target, preimage).expect("existing source");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let target = target.canonicalize().expect("canonical source");
        let declaration = "class Added";
        let preview =
            public_exact_add_declaration_preview(&workspace, &target, preimage, declaration);
        let binary = write_active_kast_for_test(&home, &config_home);
        let plan_backend = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &root.join("plan.sock"),
            vec![("raw/plan-add-declaration", preview.clone())],
        );
        let mut change = installed_public_kast(&binary, &home, &config_home, &workspace);
        change.args([
            "change",
            "add-declaration",
            target.to_str().expect("target"),
        ]);
        let change = run_with_stdin(change, declaration);
        assert!(change.status.success(), "{change:?}");
        plan_backend.join().expect("planner backend");
        let plan_id = decode(&change)["planId"]
            .as_str()
            .expect("plan id")
            .to_string();

        let apply_backend = spawn_scripted_mutating_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &root.join("apply.sock"),
            vec![("raw/plan-add-declaration", preview)],
        );
        let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
            .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_RECOVERY_JOURNAL")
            .args(["apply", &plan_id])
            .output()
            .expect("interrupted apply");
        assert_eq!(
            decode(&interrupted)["outcome"],
            "RECOVERY_REQUIRED",
            "{interrupted:?}"
        );
        apply_backend.join().expect("interrupted apply backend");

        let journal_path = home
            .join(".local/share/kast/state/agent-plans")
            .join(format!("{plan_id}.recovery.json"));
        let mut journal: Value = serde_json::from_slice(
            &std::fs::read(&journal_path).expect("prepared recovery journal"),
        )
        .expect("recovery JSON");
        match tamper {
            "hash" => {
                journal["preDiagnostics"]["fileHashes"][0]["sha256"] = json!("0".repeat(64));
            }
            "foreign-diagnostic" => {
                let foreign = workspace.join("src/main/kotlin/Foreign.kt");
                let identity = json!({
                    "severity": "WARNING",
                    "code": "STYLE",
                    "canonicalPath": foreign,
                    "message": "Foreign warning",
                });
                journal["preDiagnostics"]["cardinality"] =
                    json!({"type": "EXACT", "totalCount": 1});
                journal["preDiagnostics"]["severityCounts"] =
                    json!({"error": 0, "warning": 1, "info": 0, "total": 1});
                journal["preDiagnostics"]["diagnostics"] = json!([{
                    "identity": identity,
                    "fullMessage": "Foreign warning",
                    "location": {
                        "filePath": foreign,
                        "startOffset": 0,
                        "endOffset": 1,
                        "startLine": 1,
                        "startColumn": 1,
                        "preview": "foreign",
                    },
                }]);
                journal["preDiagnostics"]["identityCounts"] =
                    json!([{"identity": identity, "count": 1}]);
            }
            _ => unreachable!(),
        }
        let mut encoded = serde_json::to_vec(&journal).expect("tampered recovery JSON");
        encoded.push(b'\n');
        std::fs::write(&journal_path, encoded).expect("write tampered recovery journal");

        let shutdown = root.join("recover.shutdown");
        let recover_backend = spawn_lease_only_mutating_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &root.join("recover.sock"),
            &shutdown,
        );
        let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
            .args(["recover", &plan_id])
            .output()
            .expect("recover tampered journal");
        std::fs::write(&shutdown, "stop\n").expect("stop recovery backend");
        let requests = recover_backend.join().expect("recovery backend");
        assert!(
            requests.iter().all(|request| {
                !matches!(
                    request["method"].as_str(),
                    Some("raw/apply-edits" | "raw/exact-file-image-cas")
                )
            }),
            "invalid persisted evidence must not authorize a write"
        );
        assert_eq!(std::fs::read(&target).expect("unchanged source"), preimage);
        recovered_outcomes.push((tamper, decode(&recovered)));
    }
    for (tamper, recovered) in recovered_outcomes {
        assert_eq!(
            recovered["error"], "KAST_RECOVERY_INVALID",
            "tamper={tamper}; output={recovered:#}"
        );
    }
}

#[test]
fn public_recover_finishes_verified_receipt_after_postwrite_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let socket = fixture.path().join("verified-recovery.sock");
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        successful_verified_add_file_script(&target, content),
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "AFTER_VERIFIED_EVIDENCE",
        )
        .args(["apply", &plan_id])
        .output()
        .expect("interrupted apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let interrupted = decode(&interrupted);
    assert_eq!(
        interrupted["outcome"], "RECOVERY_REQUIRED",
        "{interrupted:#}"
    );
    assert_eq!(interrupted["recoveryId"], plan_id);
    assert_eq!(std::fs::read(&target).expect("postimage"), content);

    let requests = backend.join().expect("mutation backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let recover_socket = fixture.path().join("verified-recovery-second-process.sock");
    let recover_shutdown = fixture
        .path()
        .join("verified-recovery-second-process.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        &recover_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover in a new process");
    assert!(
        recovered.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("verified postimage"), content);
    std::fs::write(&recover_shutdown, "stop\n").expect("stop recovery backend");
    recover_backend.join().expect("recovery backend");

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("terminal apply replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal retry is stable");
}

#[test]
fn public_recover_verifies_all_postimages_after_postwrite_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let apply_socket = fixture.path().join("ambiguous-apply.sock");
    let apply_backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &apply_socket,
        &target,
        content,
        vec![("mutation/submit", successful_add_file_result(&target))],
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "AFTER_MUTATION_BEFORE_VERIFIED_EVIDENCE",
        )
        .args(["apply", &plan_id])
        .output()
        .expect("ambiguous postwrite apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let interrupted = decode(&interrupted);
    assert_eq!(
        interrupted["outcome"], "RECOVERY_REQUIRED",
        "{interrupted:#}"
    );
    assert_eq!(
        std::fs::read(&target).expect("ambiguous postimage"),
        content
    );
    let apply_requests = apply_backend.join().expect("apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let recover_socket = fixture.path().join("ambiguous-recover.sock");
    let shutdown = fixture.path().join("ambiguous-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover ambiguous postwrite");
    assert!(recovered.status.success(), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("verified postimage"), content,);

    std::fs::write(&shutdown, "stop\n").expect("stop recovery backend");
    let recover_requests = recover_backend.join().expect("recover backend");
    assert_eq!(
        recover_requests
            .iter()
            .filter(|request| request["method"] == "mutation/submit")
            .count(),
        0,
        "recovery must not resubmit an already-applied mutation",
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("rolled-back recovery replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "verified replay is stable");
}

#[cfg(unix)]
#[test]
fn public_recover_consumes_declared_prepared_scratch_after_apply_is_sigkilled() {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Killed.kt");
    let content = b"package sample\nclass Killed\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Killed.kt",
        std::str::from_utf8(content).expect("Kotlin source"),
    );
    let entered = fixture.path().join("sigkill.entered");
    let release = fixture.path().join("sigkill.release");
    let apply_backend = spawn_gated_prepared_scratch_crash_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-apply.sock"),
        &entered,
        &release,
        successful_verified_add_file_script(&target, content),
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn killable apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        entered.is_file(),
        "apply entered its durable post-journal write"
    );
    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    assert!(
        journal_path.is_file(),
        "recovery journal is durable before SIGKILL"
    );
    let kill_result = unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL apply child");
    let killed = apply.wait_with_output().expect("wait for killed apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    let retained_scratch = std::fs::read_to_string(&entered).expect("retained scratch path");
    let retained_scratch = Path::new(retained_scratch.trim()).to_path_buf();

    std::fs::write(&release, "release\n").expect("release in-flight backend request");
    apply_backend.join().expect("killed apply backend");
    assert!(!target.exists(), "target retains its exact absent preimage");
    assert_eq!(
        std::fs::read(&retained_scratch).expect("declared prepared postimage"),
        content
    );

    let shutdown = fixture.path().join("sigkill-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-recover.sock"),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover after SIGKILL");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert_eq!(receipt["schemaVersion"], 6, "{receipt:#}");
    assert!(
        !target.exists(),
        "typed recovery retains the absent preimage"
    );
    assert!(
        !retained_scratch.exists(),
        "typed recovery consumes the exact declared prepared path"
    );
    std::fs::write(&shutdown, "stop\n").expect("stop recovery backend");
    let requests = recover_backend.join().expect("SIGKILL recovery backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| {
                matches!(
                    request["method"].as_str(),
                    Some("raw/apply-edits" | "raw/exact-file-image-cas")
                )
            })
            .count(),
        0,
        "scratch recovery must not resubmit a normal mutation"
    );
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/recover-mutation-scratch")
            .count(),
        1,
        "restart must consume only the journal-declared prepared path"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("SIGKILL terminal replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
}

#[cfg(unix)]
#[test]
fn public_recover_restores_declared_quarantine_scratch_after_cas_is_sigkilled() {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    let preimage = b"class Existing\n";
    std::fs::write(&target, preimage).expect("existing source");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical source");
    let declaration = "class Added";
    let preview = public_exact_add_declaration_preview(&workspace, &target, preimage, declaration);
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_declaration(
        &binary,
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-cas-plan.sock"),
        &target,
        declaration,
        preview.clone(),
    );
    let entered = fixture.path().join("sigkill-cas.entered");
    let release = fixture.path().join("sigkill-cas.release");
    let backend = spawn_gated_quarantine_scratch_crash_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-cas.sock"),
        &entered,
        &release,
        vec![("raw/plan-add-declaration", preview)],
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn killable CAS apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        entered.is_file(),
        "CAS detached into its declared quarantine"
    );
    let retained_scratch = std::fs::read_to_string(&entered).expect("retained quarantine path");
    let retained_scratch = Path::new(retained_scratch.trim()).to_path_buf();
    let kill_result = unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL CAS apply child");
    let killed = apply.wait_with_output().expect("wait for killed CAS apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release detached CAS backend");
    backend.join().expect("killed CAS backend");
    assert!(
        !target.exists(),
        "detached target remains absent after client death"
    );
    assert_eq!(
        std::fs::read(&retained_scratch).expect("declared quarantine preimage"),
        preimage
    );

    let shutdown = fixture.path().join("sigkill-cas-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-cas-recover.sock"),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover detached CAS scratch");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("restored preimage"), preimage);
    assert!(
        !retained_scratch.exists(),
        "declared quarantine was consumed"
    );
    std::fs::write(&shutdown, "stop\n").expect("stop CAS recovery backend");
    let requests = recover_backend
        .join()
        .expect("CAS scratch recovery backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/recover-mutation-scratch")
            .count(),
        1
    );
    assert!(requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("raw/apply-edits" | "raw/exact-file-image-cas")
        )
    }));
}

#[cfg(unix)]
#[test]
fn public_recover_rejects_wrong_bytes_at_a_journal_owned_scratch_path() {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/ForeignScratch.kt");
    let content = b"class ForeignScratch\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/ForeignScratch.kt",
        std::str::from_utf8(content).expect("Kotlin source"),
    );
    let entered = fixture.path().join("foreign-scratch.entered");
    let release = fixture.path().join("foreign-scratch.release");
    let backend = spawn_gated_foreign_prepared_scratch_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("foreign-scratch.sock"),
        &entered,
        &release,
        successful_verified_add_file_script(&target, content),
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn foreign-scratch apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        entered.is_file(),
        "backend retained a declared scratch role"
    );
    let scratch = std::fs::read_to_string(&entered).expect("foreign scratch path");
    let scratch = Path::new(scratch.trim()).to_path_buf();
    let kill_result = unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL foreign-scratch apply");
    let killed = apply
        .wait_with_output()
        .expect("wait for foreign-scratch apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release foreign-scratch backend");
    backend.join().expect("foreign-scratch backend");
    assert!(
        !target.exists(),
        "foreign scratch never authorizes a source write"
    );
    assert_eq!(
        std::fs::read(&scratch).expect("foreign scratch retained"),
        b"foreign scratch image"
    );

    let shutdown = fixture.path().join("foreign-scratch-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("foreign-scratch-recover.sock"),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover foreign scratch");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered)["outcome"], "RECOVERY_REQUIRED");
    assert!(
        !target.exists(),
        "wrong-hash owned scratch remains write-free"
    );
    assert!(scratch.is_file(), "wrong-hash scratch is not consumed");
    std::fs::write(&shutdown, "stop\n").expect("stop foreign-scratch recovery backend");
    let requests = recover_backend
        .join()
        .expect("foreign-scratch recovery backend");
    assert!(requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("raw/apply-edits" | "raw/exact-file-image-cas" | "raw/recover-mutation-scratch")
        )
    }));
}

#[cfg(unix)]
fn assert_reverse_quarantine_only_recovery(case: &str, preimage: &[u8]) {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Reverse.kt");
    std::fs::write(&target, preimage).expect("present source preimage");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical source");
    let declaration = "class Added";
    let preview = public_exact_add_declaration_preview(&workspace, &target, preimage, declaration);
    let postimage = STANDARD_BASE64
        .decode(
            preview["image"]["postimage"]["contentBase64"]
                .as_str()
                .expect("postimage Base64"),
        )
        .expect("postimage bytes");
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_declaration(
        &binary,
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-plan.sock")),
        &target,
        declaration,
        preview.clone(),
    );
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-apply.sock")),
        vec![("raw/plan-add-declaration", preview)],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_ALL_WRITES")
        .args(["apply", &plan_id])
        .output()
        .expect("interrupt after forward write");
    assert_eq!(decode(&interrupted)["outcome"], "RECOVERY_REQUIRED");
    apply_backend.join().expect("forward apply backend");
    assert_eq!(
        std::fs::read(&target).expect("forward postimage"),
        postimage
    );

    let entered = fixture.path().join(format!("{case}-reverse.entered"));
    let release = fixture.path().join(format!("{case}-reverse.release"));
    let reverse_backend = spawn_gated_quarantine_scratch_crash_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-reverse.sock")),
        &entered,
        &release,
        vec![(
            "raw/verify-mutation-postcondition",
            scripted_json_rpc_error(
                "MUTATION_POSTCONDITION_FAILED",
                "Force reverse exact-image recovery",
                json!({}),
                false,
            ),
        )],
    );
    let recovery = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn killable reverse recovery");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(entered.is_file(), "reverse CAS detached its postimage");
    let quarantine = std::fs::read_to_string(&entered).expect("reverse quarantine path");
    let quarantine = Path::new(quarantine.trim()).to_path_buf();
    let kill_result = unsafe { libc::kill(recovery.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL reverse recovery");
    let killed = recovery
        .wait_with_output()
        .expect("wait for reverse recovery");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release reverse backend");
    let reverse_requests = reverse_backend.join().expect("reverse backend");
    assert!(!target.exists(), "reverse target is absent after detach");
    assert_eq!(
        std::fs::read(&quarantine).expect("reverse quarantine postimage"),
        postimage
    );
    let reverse_request = reverse_requests
        .iter()
        .find(|request| request["method"] == "raw/exact-file-image-cas")
        .expect("reverse exact-image request");
    let prepared = Path::new(
        reverse_request["params"]["mutationScratch"]["preparedPath"]
            .as_str()
            .expect("reverse prepared path"),
    );
    assert!(
        !prepared.exists(),
        "crash occurred before reverse preimage preparation"
    );

    let shutdown = fixture.path().join(format!("{case}-final.shutdown"));
    let final_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-final.sock")),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover reverse quarantine-only state");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert!(
        target.is_file(),
        "PRESENT preimage is not collapsed to ABSENT"
    );
    assert_eq!(
        std::fs::read(&target).expect("restored exact preimage"),
        preimage
    );
    assert!(!quarantine.exists(), "reverse quarantine is consumed");
    std::fs::write(&shutdown, "stop\n").expect("stop final recovery backend");
    let final_requests = final_backend.join().expect("final recovery backend");
    let scratch_recovery = final_requests
        .iter()
        .find(|request| request["method"] == "raw/recover-mutation-scratch")
        .expect("typed reverse scratch recovery");
    assert_eq!(
        scratch_recovery["params"]["scratchDirection"],
        "RESTORE_PREIMAGE"
    );
    assert_eq!(scratch_recovery["params"]["preimage"]["state"], "PRESENT");
    assert!(final_requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("raw/apply-edits" | "raw/exact-file-image-cas")
        )
    }));
}

#[cfg(unix)]
#[test]
fn public_recover_materializes_a_nonempty_present_preimage_from_reverse_quarantine_only() {
    assert_reverse_quarantine_only_recovery("reverse-present", b"class Existing\n");
}

#[cfg(unix)]
#[test]
fn public_recover_materializes_an_empty_present_preimage_from_reverse_quarantine_only() {
    assert_reverse_quarantine_only_recovery("reverse-empty-present", b"");
}

#[test]
fn public_recover_blocks_on_a_retained_exact_cas_backend_artifact() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    let preimage = b"class Existing\n";
    std::fs::write(&target, preimage).expect("existing source");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical source");
    let declaration = "class Added";
    let preview = public_exact_add_declaration_preview(&workspace, &target, preimage, declaration);
    let postimage = STANDARD_BASE64
        .decode(
            preview["image"]["postimage"]["contentBase64"]
                .as_str()
                .expect("postimage Base64"),
        )
        .expect("postimage");
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_declaration(
        &binary,
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-plan.sock"),
        &target,
        declaration,
        preview.clone(),
    );
    let artifact = target
        .parent()
        .expect("target parent")
        .join(".kast-cleanup-retained-cas");
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-apply.sock"),
        vec![
            ("raw/plan-add-declaration", preview),
            (
                "raw/exact-file-image-cas",
                scripted_json_rpc_error_with_retained_artifact(
                    "UNSAFE_WORKSPACE_MUTATION",
                    "Exact file-image commit retained secure recovery evidence",
                    json!({
                        "recoveryFilePathCount": "1",
                        "recoveryFilePath.0": artifact,
                    }),
                    true,
                    &artifact,
                    preimage,
                ),
            ),
        ],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("apply with retained CAS artifact");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied)["outcome"], "RECOVERY_REQUIRED");
    apply_backend.join().expect("retained CAS apply backend");
    assert_eq!(
        std::fs::read(&target).expect("committed postimage"),
        postimage
    );

    let present_shutdown = fixture.path().join("retained-cas-present.shutdown");
    let present_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-present.sock"),
        &present_shutdown,
    );
    let present = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover with retained CAS artifact present");
    std::fs::write(&present_shutdown, "stop\n").expect("stop present backend");
    let present_requests = present_backend.join().expect("present recovery backend");
    let present_receipt = decode(&present);
    assert_eq!(
        present_receipt["outcome"], "RECOVERY_REQUIRED",
        "{present_receipt:#}"
    );
    assert_eq!(present_receipt["schemaVersion"], 6, "{present_receipt:#}");
    assert!(
        present_requests.iter().all(|request| {
            !matches!(
                request["method"].as_str(),
                Some("raw/apply-edits" | "raw/exact-file-image-cas")
            )
        }),
        "a present backend artifact must block all source writes"
    );
    assert_eq!(
        std::fs::read(&target).expect("retained postimage"),
        postimage
    );

    std::fs::remove_file(&artifact).expect("external cleanup of retained artifact");
    let absent_shutdown = fixture.path().join("retained-cas-absent.shutdown");
    let absent_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-absent.sock"),
        &absent_shutdown,
    );
    let absent = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover after retained CAS artifact removal");
    std::fs::write(&absent_shutdown, "stop\n").expect("stop absent backend");
    let absent_requests = absent_backend.join().expect("absent recovery backend");
    assert!(absent.status.success(), "{absent:?}");
    let receipt = decode(&absent);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert!(
        absent_requests.iter().all(|request| {
            !matches!(
                request["method"].as_str(),
                Some("raw/apply-edits" | "raw/exact-file-image-cas")
            )
        }),
        "all-post recovery after cleanup must remain write-free"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("terminal CAS recovery replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
}

#[test]
fn public_recover_blocks_on_a_retained_add_file_rollback_artifact() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/RolledBack.kt");
    let content = b"class RolledBack\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/RolledBack.kt",
        std::str::from_utf8(content).expect("Kotlin source"),
    );
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-rollback-apply.sock"),
        vec![(
            "raw/plan-add-file",
            public_exact_add_file_preview(
                &workspace,
                &target,
                std::str::from_utf8(content).expect("Kotlin source"),
            ),
        )],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_ALL_WRITES")
        .args(["apply", &plan_id])
        .output()
        .expect("interrupt after add-file write");
    assert_eq!(decode(&applied)["outcome"], "RECOVERY_REQUIRED");
    apply_backend.join().expect("postwrite apply backend");
    assert_eq!(std::fs::read(&target).expect("add-file postimage"), content);

    let artifact = target
        .parent()
        .expect("target parent")
        .join(".kast-quarantine-retained-rollback");
    let rollback_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-rollback.sock"),
        vec![
            (
                "raw/verify-mutation-postcondition",
                scripted_json_rpc_error(
                    "MUTATION_POSTCONDITION_FAILED",
                    "Deterministic postcondition failure",
                    json!({}),
                    false,
                ),
            ),
            (
                "raw/apply-edits",
                scripted_json_rpc_error_with_retained_artifact(
                    "APPLY_PARTIAL_FAILURE",
                    "Rollback delete retained secure recovery evidence",
                    json!({
                        "recoveryFilePathCount": "1",
                        "recoveryFilePath.0": artifact,
                    }),
                    true,
                    &artifact,
                    content,
                ),
            ),
        ],
    );
    let rollback = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("rollback with retained delete artifact");
    assert_eq!(
        decode(&rollback)["outcome"],
        "RECOVERY_REQUIRED",
        "{rollback:?}"
    );
    rollback_backend.join().expect("retained rollback backend");
    assert!(
        !target.exists(),
        "rollback delete committed before its unsafe response"
    );

    let present_shutdown = fixture.path().join("retained-rollback-present.shutdown");
    let present_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-rollback-present.sock"),
        &present_shutdown,
    );
    let present = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover with rollback artifact present");
    std::fs::write(&present_shutdown, "stop\n").expect("stop present backend");
    let present_requests = present_backend.join().expect("present recovery backend");
    let present_receipt = decode(&present);
    assert_eq!(
        present_receipt["outcome"], "RECOVERY_REQUIRED",
        "{present_receipt:#}"
    );
    assert_eq!(present_receipt["schemaVersion"], 6, "{present_receipt:#}");
    assert!(
        present_requests.iter().all(|request| {
            !matches!(
                request["method"].as_str(),
                Some("raw/apply-edits" | "raw/exact-file-image-cas")
            )
        }),
        "a present rollback artifact must block source writes"
    );

    std::fs::remove_file(&artifact).expect("external cleanup of rollback artifact");
    let absent_shutdown = fixture.path().join("retained-rollback-absent.shutdown");
    let absent_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-rollback-absent.sock"),
        &absent_shutdown,
    );
    let absent = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover after rollback artifact removal");
    std::fs::write(&absent_shutdown, "stop\n").expect("stop absent backend");
    let absent_requests = absent_backend.join().expect("absent recovery backend");
    let receipt = decode(&absent);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert_eq!(receipt["schemaVersion"], 6, "{receipt:#}");
    assert!(
        absent_requests.iter().all(|request| {
            !matches!(
                request["method"].as_str(),
                Some("raw/apply-edits" | "raw/exact-file-image-cas")
            )
        }),
        "all-pre recovery after cleanup must remain write-free"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("terminal rollback recovery replay");
    assert_eq!(decode(&replay), receipt);
}

#[test]
fn public_apply_and_recover_share_one_exclusive_plan_lock() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let socket = fixture.path().join("exclusive-plan-lock.sock");
    let entered = fixture.path().join("exclusive-plan-lock.entered");
    let release = fixture.path().join("exclusive-plan-lock.release");
    let backend = spawn_gated_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        &entered,
        &release,
        successful_verified_add_file_script(&target, content),
    );

    let first = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("first apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        entered.is_file(),
        "first apply reached the mutation boundary"
    );

    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    let lock_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.lock"));
    assert_eq!(
        std::fs::metadata(&lock_path)
            .expect("durable plan lock")
            .permissions()
            .mode()
            & 0o777,
        0o600,
    );
    let journal_before = std::fs::read(&journal_path).expect("prepared journal");
    let concurrent = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("concurrent recover");
    assert_eq!(concurrent.status.code(), Some(1), "{concurrent:?}");
    assert_eq!(decode(&concurrent)["error"], "KAST_PLAN_BUSY");
    assert_eq!(
        std::fs::read(&journal_path).expect("unchanged journal"),
        journal_before,
    );
    assert!(!target.exists(), "concurrent recovery performed no write");

    std::fs::write(&release, "release\n").expect("release mutation gate");
    let first = first.wait_with_output().expect("first apply output");
    assert!(
        first.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
    );
    let receipt = decode(&first);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    let requests = backend.join().expect("gated mutation backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
        "the concurrent process must not submit another mutation",
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("terminal recover replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal receipt is unchanged");
}

#[test]
fn public_apply_classifies_exact_source_drift_before_semantic_revalidation() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);
    let relative_path = "src/main/kotlin/Ordering.kt";
    let target = workspace.join(relative_path);
    let planned = "package sample\nclass Planned\n";
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        relative_path,
        planned,
    );
    let foreign = b"package sample\nclass Foreign\n";
    std::fs::write(&target, foreign).expect("drifted target");

    let mut changed_authority = public_exact_add_file_preview(&workspace, &target, planned);
    changed_authority["proof"]["context"]["requiredGeneration"] = json!(8);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("ordering-apply.sock"),
        vec![("raw/plan-add-file", changed_authority)],
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("drifted apply");
    assert_eq!(apply.status.code(), Some(1), "{apply:?}");
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "CONFLICTED", "{receipt:#}");
    assert_eq!(receipt["schemaVersion"], 6, "{receipt:#}");
    assert_eq!(
        std::fs::read(&target).expect("foreign source retained"),
        foreign
    );
    let requests = backend.join().expect("ordering backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/plan-add-file")
            .count(),
        0,
        "semantic revalidation must not run after exact source drift"
    );
}

#[test]
fn public_apply_persists_stable_rejected_and_conflicted_outcomes() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);

    let rejected_target = workspace.join("src/main/kotlin/Rejected.kt");
    let rejected_content = "package sample\nclass Rejected\n";
    let rejected_plan = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Rejected.kt",
        rejected_content,
    );
    let mut changed_authority =
        public_exact_add_file_preview(&workspace, &rejected_target, rejected_content);
    changed_authority["proof"]["context"]["requiredGeneration"] = json!(8);
    let socket = fixture.path().join("rejected-apply.sock");
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![("raw/plan-add-file", changed_authority)],
    );
    let rejected = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &rejected_plan])
        .output()
        .expect("rejected apply");
    assert_eq!(rejected.status.code(), Some(1), "{rejected:?}");
    let rejected_receipt = decode(&rejected);
    assert_eq!(
        rejected_receipt["outcome"], "REJECTED",
        "{rejected_receipt:#}"
    );
    assert_eq!(rejected_receipt["schemaVersion"], 6, "{rejected_receipt:#}");
    assert!(
        !rejected_target.exists(),
        "rejection retained absent pre-state"
    );
    assert_eq!(
        backend
            .join()
            .expect("rejected backend")
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        0,
    );
    let rejected_replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &rejected_plan])
        .output()
        .expect("rejected replay");
    assert_eq!(
        rejected_replay.status.code(),
        Some(1),
        "{rejected_replay:?}"
    );
    assert_eq!(decode(&rejected_replay), rejected_receipt);

    let conflicted_target = workspace.join("src/main/kotlin/Conflicted.kt");
    let conflicted_plan = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Conflicted.kt",
        "package sample\nclass Planned\n",
    );
    let foreign = b"package sample\nclass Foreign\n";
    std::fs::write(&conflicted_target, foreign).expect("foreign source");
    let conflicted_socket = fixture.path().join("conflicted-apply.sock");
    let conflicted_shutdown = fixture.path().join("conflicted-apply.shutdown");
    let conflicted_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &conflicted_socket,
        &conflicted_shutdown,
    );
    let conflicted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &conflicted_plan])
        .output()
        .expect("conflicted apply");
    assert_eq!(conflicted.status.code(), Some(1), "{conflicted:?}");
    let conflicted_receipt = decode(&conflicted);
    assert_eq!(
        conflicted_receipt["outcome"], "CONFLICTED",
        "{conflicted_receipt:#}"
    );
    assert_eq!(
        conflicted_receipt["schemaVersion"], 6,
        "{conflicted_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&conflicted_target).expect("foreign source retained"),
        foreign,
    );
    std::fs::write(&conflicted_shutdown, "stop\n").expect("stop conflict backend");
    assert_eq!(
        conflicted_backend
            .join()
            .expect("conflicted backend")
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        0,
    );
    let conflicted_replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &conflicted_plan])
        .output()
        .expect("conflicted replay");
    assert_eq!(
        conflicted_replay.status.code(),
        Some(1),
        "{conflicted_replay:?}"
    );
    assert_eq!(decode(&conflicted_replay), conflicted_receipt);
}

#[test]
fn change_rename_persists_restart_safe_exact_file_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");

    let declaration_file = workspace.join("Keywords.kt");
    let reference_file = workspace.join("Usage.kt");
    let declaration_preimage = b"\xef\xbb\xbfpackage io.example\r\nclass OrderService {\r\n    fun process() = \"rocket \xf0\x9f\x9a\x80\"\r\n}\r\n".to_vec();
    let reference_preimage =
        b"package io.example\r\nfun use(service: OrderService) = service.process()\r\n".to_vec();
    std::fs::write(&declaration_file, &declaration_preimage).expect("declaration source");
    std::fs::write(&reference_file, &reference_preimage).expect("reference source");

    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("Keywords.kt");
    let reference_file = workspace.join("Usage.kt");
    let original_symbol = "io.example.OrderService.process";
    let new_name = "processSafely";
    let declaration_text =
        std::str::from_utf8(&declaration_preimage).expect("UTF-8 declaration source");
    let reference_text = std::str::from_utf8(&reference_preimage).expect("UTF-8 reference source");
    let declaration_byte_start = declaration_text.find("process").expect("declaration name");
    let reference_byte_start = reference_text.find("process").expect("reference name");
    let declaration_document = declaration_text
        .strip_prefix('\u{feff}')
        .unwrap_or(declaration_text)
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let reference_document = reference_text
        .strip_prefix('\u{feff}')
        .unwrap_or(reference_text)
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let declaration_document_start = declaration_document
        .find("process")
        .expect("declaration document name");
    let reference_document_start = reference_document
        .find("process")
        .expect("reference document name");
    let declaration_start = declaration_document[..declaration_document_start]
        .encode_utf16()
        .count() as u32;
    let reference_start = reference_document[..reference_document_start]
        .encode_utf16()
        .count() as u32;
    let original_name_length = "process".encode_utf16().count() as u32;

    let mut declaration_postimage = declaration_preimage.clone();
    declaration_postimage.splice(
        declaration_byte_start..declaration_byte_start + "process".len(),
        new_name.bytes(),
    );
    let mut reference_postimage = reference_preimage.clone();
    reference_postimage.splice(
        reference_byte_start..reference_byte_start + "process".len(),
        new_name.bytes(),
    );

    let target = json!({
        "fqName": original_symbol,
        "kind": "FUNCTION",
        "declarationFile": declaration_file,
        "declarationStartOffset": declaration_start,
        "containingType": "io.example.OrderService"
    });
    let occurrence = json!({
        "reference": {
            "location": {
                "filePath": reference_file,
                "startOffset": reference_start,
                "endOffset": reference_start + original_name_length,
                "startLine": 2,
                "startColumn": 42,
                "preview": "service.process()"
            },
            "containingSymbol": {"type": "TOP_LEVEL"}
        },
        "resolvedTarget": target,
        "provenance": "COMPILER"
    });
    let proof = json!({
        "target": target,
        "requiredGeneration": 7,
        "evidence": {
            "type": "COMPLETE",
            "cardinality": {"type": "EXACT", "totalCount": 1},
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
        "occurrences": [occurrence]
    });
    let edits = json!([
        {
            "filePath": declaration_file,
            "startOffset": declaration_start,
            "endOffset": declaration_start + original_name_length,
            "newText": new_name
        },
        {
            "filePath": reference_file,
            "startOffset": reference_start,
            "endOffset": reference_start + original_name_length,
            "newText": new_name
        }
    ]);
    let file_images = json!([
        {
            "filePath": declaration_file,
            "preimage": {
                "contentBase64": STANDARD_BASE64.encode(&declaration_preimage),
                "sha256": source_sha256(&declaration_preimage)
            },
            "postimage": {
                "contentBase64": STANDARD_BASE64.encode(&declaration_postimage),
                "sha256": source_sha256(&declaration_postimage)
            }
        },
        {
            "filePath": reference_file,
            "preimage": {
                "contentBase64": STANDARD_BASE64.encode(&reference_preimage),
                "sha256": source_sha256(&reference_preimage)
            },
            "postimage": {
                "contentBase64": STANDARD_BASE64.encode(&reference_postimage),
                "sha256": source_sha256(&reference_postimage)
            }
        }
    ]);

    let resolve_response = json!({
        "type": "RESOLVE_SUCCESS",
        "ok": true,
        "source": "compiler",
        "symbol": {
            "fqName": original_symbol,
            "kind": "FUNCTION",
            "containingType": "io.example.OrderService",
            "location": {
                "filePath": declaration_file,
                "startOffset": declaration_start,
                "endOffset": declaration_start + original_name_length,
                "startLine": 3,
                "startColumn": 9,
                "preview": "fun process()"
            }
        }
    });
    let rename_preview = json!({
        "edits": edits,
        "fileHashes": [
            {
                "filePath": declaration_file,
                "hash": source_sha256(&declaration_preimage)
            },
            {
                "filePath": reference_file,
                "hash": source_sha256(&reference_preimage)
            }
        ],
        "affectedFiles": [declaration_file, reference_file],
        "proof": proof,
        "fileImages": file_images.clone(),
        "schemaVersion": 6
    });
    let socket = fixture.path().join("rename-authority.sock");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            ("symbol/resolve", resolve_response.clone()),
            ("raw/rename", rename_preview.clone()),
        ],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let change = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "rename", original_symbol, new_name])
        .output()
        .expect("persist rename plan");
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public rename plan must redact exact image bytes"
    );
    backend.join().expect("rename planning backend");

    let public = decode(&change);
    assert_eq!(
        public["plan"]["preview"]["proof"]["occurrences"][0]["reference"]["containingSymbol"]["type"],
        "TOP_LEVEL",
        "public rename proof must retain its semantic discriminator"
    );
    let plan_id = public["planId"].as_str().expect("plan id").to_string();
    let stored: Value = serde_json::from_slice(
        &std::fs::read(
            home.join(".local/share/kast/state/agent-plans")
                .join(format!("{plan_id}.json")),
        )
        .expect("restart-safe stored rename plan"),
    )
    .expect("stored rename JSON");
    let expected_operation = json!({
        "operation": "rename",
        "authority": {
            "target": target,
            "proof": proof,
            "edits": edits,
            "fileImages": file_images
        }
    });
    assert_eq!(
        stored["operation"], expected_operation,
        "rename storage must retain exact write authority, not the symbol lookup request"
    );
    assert!(stored["operation"].get("symbol").is_none());
    assert!(stored["operation"].get("newName").is_none());
    for image in stored["operation"]["authority"]["fileImages"]
        .as_array()
        .expect("exact file images")
    {
        for state in ["preimage", "postimage"] {
            let hash = image[state]["sha256"].as_str().expect("image SHA-256");
            assert_eq!(hash.len(), 64, "{state} SHA-256: {hash}");
            assert!(
                hash.bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
                "{state} SHA-256 must be lowercase hexadecimal: {hash}"
            );
        }
    }

    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-apply.sock"),
        vec![("raw/rename", rename_preview.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("verified rename apply");
    assert!(applied.status.success(), "{applied:?}");
    let verified_receipt = decode(&applied);
    assert_eq!(
        verified_receipt["outcome"], "VERIFIED",
        "{verified_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&declaration_file).expect("renamed declaration"),
        declaration_postimage
    );
    assert_eq!(
        std::fs::read(&reference_file).expect("renamed reference"),
        reference_postimage
    );
    let apply_requests = apply_backend.join().expect("rename apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        2,
        "rename must write both exact transitions once"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("verified rename replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);

    std::fs::write(&declaration_file, &declaration_preimage).expect("reset declaration preimage");
    std::fs::write(&reference_file, &reference_preimage).expect("reset reference preimage");
    let restart_plan_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-restart-plan.sock"),
        vec![
            ("symbol/resolve", resolve_response),
            ("raw/rename", rename_preview.clone()),
        ],
    );
    let restart_change = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "rename", original_symbol, new_name])
        .output()
        .expect("persist restart rename plan");
    assert!(restart_change.status.success(), "{restart_change:?}");
    restart_plan_backend.join().expect("restart rename planner");
    let restart_plan_id = decode(&restart_change)["planId"]
        .as_str()
        .expect("restart rename plan id")
        .to_string();

    let interrupted_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-interrupted-apply.sock"),
        vec![("raw/rename", rename_preview)],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_WRITE_1")
        .args(["apply", &restart_plan_id])
        .output()
        .expect("interrupt multi-file rename");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    assert_eq!(decode(&interrupted)["outcome"], "RECOVERY_REQUIRED");
    assert_eq!(
        std::fs::read(&declaration_file).expect("first postimage"),
        declaration_postimage
    );
    assert_eq!(
        std::fs::read(&reference_file).expect("second preimage"),
        reference_preimage
    );
    interrupted_backend
        .join()
        .expect("interrupted rename backend");

    let recovery_shutdown = fixture.path().join("rename-mixed-recovery.shutdown");
    let recovery_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rename-mixed-recovery.sock"),
        &recovery_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &restart_plan_id])
        .output()
        .expect("restart mixed rename recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let rolled_back_receipt = decode(&recovered);
    assert_eq!(
        rolled_back_receipt["outcome"], "ROLLED_BACK",
        "{rolled_back_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&declaration_file).expect("restored declaration"),
        declaration_preimage
    );
    assert_eq!(
        std::fs::read(&reference_file).expect("retained reference"),
        reference_preimage
    );
    std::fs::write(&recovery_shutdown, "stop\n").expect("stop mixed recovery backend");
    let recovery_requests = recovery_backend
        .join()
        .expect("mixed rename recovery backend");
    assert_eq!(
        recovery_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        1,
        "mixed recovery must reverse only the written transition"
    );
    let rollback_replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &restart_plan_id])
        .output()
        .expect("rolled-back rename replay");
    assert_eq!(
        rollback_replay.status.code(),
        Some(1),
        "{rollback_replay:?}"
    );
    assert_eq!(decode(&rollback_replay), rolled_back_receipt);
}

#[test]
fn change_replace_persists_restart_safe_exact_file_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");

    let source = "fun process(): String = \"old\"\r\n";
    let source_document = source.replace("\r\n", "\n");
    let proposed = "fun process(): String = \"new 🚀\"\n";
    let mut preimage = b"\xef\xbb\xbf".to_vec();
    preimage.extend_from_slice(source.as_bytes());
    let mut postimage = b"\xef\xbb\xbf".to_vec();
    postimage.extend_from_slice(proposed.replace('\n', "\r\n").as_bytes());
    std::fs::write(workspace.join("Keywords.kt"), &preimage).expect("source");

    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("Keywords.kt");
    let symbol = "io.example.OrderService.process";
    let target = json!({
        "fqName": symbol,
        "kind": "FUNCTION",
        "declarationFile": declaration_file,
        "declarationStartOffset": 4,
        "containingType": "io.example.OrderService"
    });
    let signature = json!({
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
        "actual": false
    });
    let proof = json!({
        "target": target,
        "requiredGeneration": 7,
        "sourceRange": {
            "filePath": declaration_file,
            "startOffset": 0,
            "endOffset": source_document.encode_utf16().count(),
            "startLine": 1,
            "startColumn": 1,
            "preview": source_document
        },
        "fileHashes": [{
            "filePath": declaration_file,
            "hash": source_sha256(&preimage)
        }],
        "oldSignature": signature,
        "proposedSignature": signature,
        "proposedDeclarationHash": source_sha256(proposed.as_bytes()),
        "proposedDeclarationLength": proposed.encode_utf16().count(),
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
                "SEMANTIC_GENERATION_UNCHANGED"
            ]
        },
        "outboundReferences": []
    });
    let edit = json!({
        "filePath": declaration_file,
        "startOffset": 0,
        "endOffset": source_document.encode_utf16().count(),
        "newText": proposed
    });
    let file_images = json!([{
        "filePath": declaration_file,
        "preimage": {
            "contentBase64": STANDARD_BASE64.encode(&preimage),
            "sha256": source_sha256(&preimage)
        },
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(&postimage),
            "sha256": source_sha256(&postimage)
        }
    }]);

    let resolve_response = json!({
        "type": "RESOLVE_SUCCESS",
        "ok": true,
        "source": "compiler",
        "symbol": {
            "fqName": symbol,
            "kind": "FUNCTION",
            "containingType": "io.example.OrderService",
            "location": {
                "filePath": declaration_file,
                "startOffset": 4,
                "endOffset": 11,
                "startLine": 1,
                "startColumn": 5,
                "preview": "fun process()"
            }
        }
    });
    let replacement_preview = json!({
        "edit": edit,
        "proof": proof,
        "fileImages": file_images.clone(),
        "schemaVersion": 6
    });
    let socket = fixture.path().join("replacement-authority.sock");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            ("symbol/resolve", resolve_response.clone()),
            ("raw/plan-replacement", replacement_preview.clone()),
        ],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut change = installed_public_kast(&binary, &home, &config_home, &workspace);
    change.args(["change", "replace", symbol]);
    let change = run_with_stdin(change, proposed);
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public replacement plan must redact exact image bytes"
    );
    backend.join().expect("replacement planning backend");

    let public = decode(&change);
    assert_eq!(
        public["plan"]["preview"]["proof"]["oldSignature"]["type"], "function",
        "public replacement proof must retain its semantic discriminator"
    );
    let plan_id = public["planId"].as_str().expect("plan id").to_string();
    let plan_directory = home.join(".local/share/kast/state/agent-plans");
    let stored: Value = serde_json::from_slice(
        &std::fs::read(plan_directory.join(format!("{plan_id}.json")))
            .expect("restart-safe stored replacement plan"),
    )
    .expect("stored replacement JSON");
    assert_eq!(
        stored["operation"],
        json!({
            "operation": "replace",
            "authority": {
                "target": target,
                "proof": proof,
                "edits": [edit],
                "fileImages": file_images
            }
        }),
        "replacement storage must retain exact write authority, not a symbol lookup request"
    );
    assert!(stored["operation"].get("symbol").is_none());
    assert!(stored["operation"].get("selectorHandle").is_none());
    assert_eq!(stored["contentSha256"], source_sha256(proposed.as_bytes()));
    assert_eq!(
        std::fs::read(plan_directory.join(format!("{plan_id}.content")))
            .expect("stored proposed replacement content"),
        proposed.as_bytes()
    );

    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("replacement-apply.sock"),
        vec![("raw/plan-replacement", replacement_preview.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("verified replacement apply");
    assert!(applied.status.success(), "{applied:?}");
    let verified_receipt = decode(&applied);
    assert_eq!(
        verified_receipt["outcome"], "VERIFIED",
        "{verified_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&declaration_file).expect("replacement postimage"),
        postimage
    );
    let apply_requests = apply_backend.join().expect("replacement apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        1
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("verified replacement replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);

    std::fs::write(&declaration_file, &preimage).expect("reset replacement preimage");
    let tamper_plan_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("replacement-tamper-plan.sock"),
        vec![
            ("symbol/resolve", resolve_response),
            ("raw/plan-replacement", replacement_preview),
        ],
    );
    let mut tamper_change = installed_public_kast(&binary, &home, &config_home, &workspace);
    tamper_change.args(["change", "replace", symbol]);
    let tamper_change = run_with_stdin(tamper_change, proposed);
    assert!(tamper_change.status.success(), "{tamper_change:?}");
    tamper_plan_backend
        .join()
        .expect("replacement tamper planner");
    let plan_id = decode(&tamper_change)["planId"]
        .as_str()
        .expect("tamper plan id")
        .to_string();
    let stored: Value = serde_json::from_slice(
        &std::fs::read(plan_directory.join(format!("{plan_id}.json")))
            .expect("stored tamper replacement plan"),
    )
    .expect("stored tamper replacement JSON");

    let tampered_proposed = "fun process(): String = \"tampered\"\n";
    let mut tampered_postimage = b"\xef\xbb\xbf".to_vec();
    tampered_postimage.extend_from_slice(tampered_proposed.replace('\n', "\r\n").as_bytes());
    let mut tampered = stored;
    tampered["operation"]["authority"]["edits"][0]["newText"] = json!(tampered_proposed);
    tampered["operation"]["authority"]["proof"]["proposedDeclarationHash"] =
        json!(source_sha256(tampered_proposed.as_bytes()));
    tampered["operation"]["authority"]["proof"]["proposedDeclarationLength"] =
        json!(tampered_proposed.encode_utf16().count());
    tampered["operation"]["authority"]["fileImages"][0]["postimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(&tampered_postimage),
        "sha256": source_sha256(&tampered_postimage)
    });
    let plan_path = plan_directory.join(format!("{plan_id}.json"));
    let mut encoded = serde_json::to_vec(&tampered).expect("tampered plan JSON");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("rewrite private plan for restart tamper proof");

    let tampered_apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("restart with tampered replacement authority");
    assert_eq!(tampered_apply.status.code(), Some(1), "{tampered_apply:?}");
    assert_eq!(
        decode(&tampered_apply)["error"],
        "KAST_PLAN_INVALID",
        "tampered authority must fail before private content or runtime use"
    );
}

#[test]
fn change_add_declaration_persists_restart_safe_file_bottom_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    let preimage = b"\xef\xbb\xbfclass Existing\r\n";
    std::fs::write(&target, preimage).expect("existing source");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical target");
    let declaration = "class Added";
    let preview = public_exact_add_declaration_preview(&workspace, &target, preimage, declaration);
    let expected_image = preview["image"].clone();
    let expected_proof = preview["proof"].clone();
    let expected_postimage = STANDARD_BASE64
        .decode(
            preview["image"]["postimage"]["contentBase64"]
                .as_str()
                .expect("add-declaration postimage bytes"),
        )
        .expect("add-declaration postimage Base64");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("plan-add-declaration.sock"),
        vec![("raw/plan-add-declaration", preview.clone())],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut change = installed_public_kast(&binary, &home, &config_home, &workspace);
    change.args([
        "change",
        "add-declaration",
        target.to_str().expect("target"),
    ]);
    let change = run_with_stdin(change, declaration);
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public add-declaration plan must redact exact image bytes"
    );
    backend.join().expect("add-declaration planner backend");
    let public = decode(&change);
    assert_eq!(
        public["plan"]["preview"]["proposedDeclaration"],
        declaration
    );
    assert_eq!(
        public["plan"]["preview"]["proof"]["packageIdentity"]["type"], "ROOT",
        "public addition proof must retain its semantic discriminator"
    );
    let plan_id = public["planId"].as_str().expect("plan id");
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored: Value = serde_json::from_slice(
        &std::fs::read(&plan_path).expect("stored add-declaration authority"),
    )
    .expect("stored add-declaration JSON");
    assert_eq!(
        stored["operation"],
        json!({
            "operation": "add-declaration",
            "authority": {
                "image": expected_image,
                "proof": expected_proof,
                "proposedDeclarationSha256": source_sha256(declaration.as_bytes()),
            }
        })
    );
    assert!(stored["operation"].get("path").is_none());
    assert_eq!(
        stored["contentSha256"],
        source_sha256(declaration.as_bytes())
    );

    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("add-declaration-apply.sock"),
        vec![("raw/plan-add-declaration", preview.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", plan_id])
        .output()
        .expect("verified add-declaration apply");
    assert!(applied.status.success(), "{applied:?}");
    let verified_receipt = decode(&applied);
    assert_eq!(
        verified_receipt["outcome"], "VERIFIED",
        "{verified_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&target).expect("add-declaration postimage"),
        expected_postimage
    );
    let apply_requests = apply_backend.join().expect("add-declaration apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/exact-file-image-cas")
            .count(),
        1
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", plan_id])
        .output()
        .expect("verified add-declaration replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);

    std::fs::write(&target, preimage).expect("reset add-declaration preimage");
    let tamper_plan_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("add-declaration-tamper-plan.sock"),
        vec![("raw/plan-add-declaration", preview)],
    );
    let mut tamper_change = installed_public_kast(&binary, &home, &config_home, &workspace);
    tamper_change.args([
        "change",
        "add-declaration",
        target.to_str().expect("target"),
    ]);
    let tamper_change = run_with_stdin(tamper_change, declaration);
    assert!(tamper_change.status.success(), "{tamper_change:?}");
    tamper_plan_backend
        .join()
        .expect("add-declaration tamper planner");
    let plan_id = decode(&tamper_change)["planId"]
        .as_str()
        .expect("tamper plan id")
        .to_string();
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored: Value = serde_json::from_slice(
        &std::fs::read(&plan_path).expect("stored tamper add-declaration authority"),
    )
    .expect("stored tamper add-declaration JSON");

    let mut tampered = stored;
    tampered["operation"]["authority"]["proposedDeclarationSha256"] =
        json!(source_sha256(b"class Other"));
    let mut encoded = serde_json::to_vec(&tampered).expect("tampered declaration plan");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("write tampered declaration plan");
    let restarted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("restart with tampered declaration authority");
    assert_eq!(restarted.status.code(), Some(1), "{restarted:?}");
    assert_eq!(decode(&restarted)["error"], "KAST_PLAN_INVALID");
}

#[test]
fn change_persists_a_private_root_bound_plan() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"plan-test\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);

    let change = change_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        "package sample\nclass Added\n",
    );
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr)
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public plan must not expose private raw byte images"
    );
    let change = decode(&change);
    let plan_id = change["planId"].as_str().expect("plan id");
    uuid::Uuid::parse_str(plan_id).expect("UUID plan id");
    assert_eq!(change["operation"], "add-file");
    assert_eq!(
        change["plan"]["preview"]["proposedContent"],
        "package sample\nclass Added\n"
    );
    assert_eq!(
        change["plan"]["preview"]["proof"]["packageIdentity"]["type"], "ROOT",
        "public add-file proof must retain its semantic discriminator"
    );
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
    let stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("stored add-file authority"))
            .expect("stored add-file JSON");
    assert_eq!(stored["operation"]["operation"], "add-file");
    assert!(stored["operation"].get("path").is_none());
    assert_eq!(
        stored["operation"]["authority"]["proof"]["targetState"],
        "ABSENT"
    );
    assert_eq!(
        stored["operation"]["authority"]["postimage"]["contentBase64"],
        STANDARD_BASE64.encode("package sample\nclass Added\n")
    );

    let other = fixture.path().join("other");
    std::fs::create_dir_all(&other).expect("other root");
    std::fs::write(other.join("settings.gradle.kts"), "").expect("other settings");
    let wrong_root = installed_public_kast(&binary, &home, &config_home, &other)
        .args(["apply", plan_id])
        .output()
        .expect("wrong-root apply");
    assert_eq!(wrong_root.status.code(), Some(1), "{wrong_root:?}");
    assert_eq!(decode(&wrong_root)["error"], "KAST_PLAN_WORKSPACE_MISMATCH");
    assert!(plan_path.is_file(), "failed apply keeps plan");
    assert!(content_path.is_file(), "failed apply keeps content");

    let mut tampered = stored;
    tampered["operation"]["authority"]["proof"]["postimageSha256"] = json!("0".repeat(64));
    let mut encoded = serde_json::to_vec(&tampered).expect("tampered add-file plan");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("write tampered add-file plan");
    let restarted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", plan_id])
        .output()
        .expect("restart with tampered add-file authority");
    assert_eq!(restarted.status.code(), Some(1), "{restarted:?}");
    assert_eq!(decode(&restarted)["error"], "KAST_PLAN_INVALID");
}

#[test]
fn refresh_keeps_relationship_failure_actionable_without_graph_extraction() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source = workspace.join("src/App.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source directory");
    std::fs::write(&source, "fun app() = missing\n").expect("source");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_empty_graph_scope(&workspace);
    let source = source.canonicalize().expect("canonical source");
    let failure_id = uuid::Uuid::new_v4().hyphenated().to_string();
    let socket = fixture.path().join("refresh.sock");
    let backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        2,
        vec![
            (
                "raw/workspace-refresh",
                complete_refresh(&source, &failure_id),
            ),
            ("raw/diagnostics", diagnostics_with_error(&source)),
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
    assert_eq!(refresh["diagnostics"]["cardinality"]["totalCount"], 1);
    assert_eq!(refresh["diagnostics"]["cardinality"]["returnedCount"], 1);
    assert_eq!(refresh["diagnostics"]["cardinality"]["truncated"], false);
    assert_eq!(
        refresh["diagnostics"]["diagnostics"][0]["severity"],
        "ERROR"
    );
    assert_eq!(refresh["graph"]["updated"], false);
    assert_eq!(
        refresh["externalizableFailures"],
        json!([{
            "path": source,
            "failureId": failure_id,
            "code": "PSI_UNAVAILABLE"
        }])
    );
    assert_eq!(
        refresh["next"],
        json!([format!("kast refresh external {failure_id}")])
    );
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
        ["raw/workspace-refresh", "raw/diagnostics"]
    );
    for request in semantic_requests {
        assert_eq!(request["params"]["filePaths"], json!([source]));
    }
}

include!("operations/refresh.rs");
include!("operations/focused_refresh.rs");
