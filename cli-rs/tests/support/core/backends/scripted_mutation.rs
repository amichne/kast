use super::*;

pub(crate) fn scripted_json_rpc_error(
    code: &str,
    message: &str,
    details: serde_json::Value,
    apply_default_mutation: bool,
) -> serde_json::Value {
    serde_json::json!({
        "__kastTestApplyDefaultMutation": apply_default_mutation,
        "__kastTestJsonRpcError": {
            "code": -32500,
            "message": message,
            "data": {
                "schemaVersion": api_schema_version(),
                "requestId": "scripted-test-request",
                "code": code,
                "message": message,
                "retryable": false,
                "details": details,
            },
        },
    })
}

pub(crate) fn scripted_json_rpc_error_with_retained_artifact(
    code: &str,
    message: &str,
    details: serde_json::Value,
    apply_default_mutation: bool,
    artifact_path: &Path,
    artifact_contents: &[u8],
) -> serde_json::Value {
    let mut reply = scripted_json_rpc_error(code, message, details, apply_default_mutation);
    reply["__kastTestRetainedArtifactPath"] =
        serde_json::json!(artifact_path.display().to_string());
    reply["__kastTestRetainedArtifactBase64"] =
        serde_json::json!(unified_base64(artifact_contents));
    reply
}

pub(crate) fn spawn_scripted_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        true,
        vec![],
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_scripted_mutating_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_verified_add_declaration_binding_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    plan_result: serde_json::Value,
    verified_receipt: serde_json::Value,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_strictly_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        2,
        true,
        unified_mutation_capabilities(),
        vec![
            ("change/plan-add-declaration", plan_result),
            ("change/apply-add-declaration", verified_receipt),
        ],
    )
}

pub(crate) fn spawn_lease_only_mutating_indexer_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    shutdown_marker: &Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        Some(shutdown_marker.to_path_buf()),
        None,
        vec![],
    )
}

pub(crate) fn spawn_scripted_mutating_indexer_backend_with_file_write(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    file_path: &Path,
    contents: &[u8],
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        Some((file_path.to_path_buf(), contents.to_vec())),
        None,
        None,
        None,
        scripted_results,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_mutating_indexer_backend_with_file_write(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    file_path: &Path,
    contents: &[u8],
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        Some((file_path.to_path_buf(), contents.to_vec())),
        Some((entered_marker.to_path_buf(), release_marker.to_path_buf())),
        None,
        None,
        scripted_results,
    )
}

#[derive(Clone, Copy)]
pub(super) enum ScriptedScratchCrash {
    PreparedPostimage,
    PreparedForeign,
    QuarantinePreimage,
}

pub(super) struct ScriptedScratchCrashGate {
    pub(super) mode: ScriptedScratchCrash,
    pub(super) entered_marker: PathBuf,
    pub(super) release_marker: PathBuf,
}

impl ScriptedScratchCrash {
    pub(super) fn method(self) -> &'static str {
        match self {
            Self::PreparedPostimage | Self::PreparedForeign => "raw/apply-edits",
            Self::QuarantinePreimage => "raw/exact-file-image-cas",
        }
    }
}

pub(super) fn retain_declared_scratch_until_release(
    request: &serde_json::Value,
    gate: ScriptedScratchCrashGate,
) -> serde_json::Value {
    let params = &request["params"];
    let retained = match gate.mode {
        ScriptedScratchCrash::PreparedPostimage | ScriptedScratchCrash::PreparedForeign => {
            let operation = &params["fileOperations"][0];
            assert_eq!(operation["type"], "CREATE_FILE");
            let target = Path::new(operation["filePath"].as_str().expect("create target"));
            assert!(!target.exists(), "prepared crash target must retain absent preimage");
            let scratch = &params["mutationScratchSets"][0];
            let prepared = PathBuf::from(
                scratch["preparedPath"]
                    .as_str()
                    .expect("declared prepared path"),
            );
            let content = match gate.mode {
                ScriptedScratchCrash::PreparedPostimage => {
                    operation["content"].as_str().expect("create content").as_bytes()
                }
                ScriptedScratchCrash::PreparedForeign => b"foreign scratch image",
                ScriptedScratchCrash::QuarantinePreimage => unreachable!("closed crash mode"),
            };
            std::fs::write(&prepared, content).expect("retain declared prepared postimage");
            prepared
        }
        ScriptedScratchCrash::QuarantinePreimage => {
            let target = Path::new(params["filePath"].as_str().expect("CAS target"));
            let quarantine = PathBuf::from(
                params["mutationScratch"]["quarantinePath"]
                    .as_str()
                    .expect("declared quarantine path"),
            );
            std::fs::rename(target, &quarantine).expect("retain declared quarantine preimage");
            quarantine
        }
    };
    std::fs::write(&gate.entered_marker, retained.display().to_string())
        .expect("scratch crash entered marker");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while !gate.release_marker.is_file() && std::time::Instant::now() < deadline {
        thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(gate.release_marker.is_file(), "scratch crash release marker");
    scripted_json_rpc_error(
        "UNSAFE_WORKSPACE_MUTATION",
        "The test backend retained exact journal-owned scratch before responding",
        serde_json::json!({
            "recoveryFilePathCount": "1",
            "recoveryFilePath.0": retained,
        }),
        false,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_prepared_scratch_crash_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        Some(ScriptedScratchCrashGate {
            mode: ScriptedScratchCrash::PreparedPostimage,
            entered_marker: entered_marker.to_path_buf(),
            release_marker: release_marker.to_path_buf(),
        }),
        scripted_results,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_quarantine_scratch_crash_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        Some(ScriptedScratchCrashGate {
            mode: ScriptedScratchCrash::QuarantinePreimage,
            entered_marker: entered_marker.to_path_buf(),
            release_marker: release_marker.to_path_buf(),
        }),
        scripted_results,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_gated_foreign_prepared_scratch_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    entered_marker: &Path,
    release_marker: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        unified_mutation_capabilities(),
        None,
        None,
        None,
        Some(ScriptedScratchCrashGate {
            mode: ScriptedScratchCrash::PreparedForeign,
            entered_marker: entered_marker.to_path_buf(),
            release_marker: release_marker.to_path_buf(),
        }),
        scripted_results,
    )
}
