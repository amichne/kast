use super::*;

fn current_process_descriptor(socket_path: &Path) -> ServerInstanceDescriptor {
    ServerInstanceDescriptor {
        workspace_root: "/workspace".to_string(),
        backend_name: BackendName::Headless.canonical().to_string(),
        backend_version: "test".to_string(),
        runtime_instance_id: Some("runtime-instance".to_string()),
        process_start_epoch_millis: Some(1),
        owner_uid: Some(u64::from(unsafe { libc::geteuid() })),
        socket_file_identity: current_socket_file_identity(
            socket_path.to_str().expect("UTF-8 socket path"),
        )
        .expect("socket identity"),
        transport: "uds".to_string(),
        socket_path: socket_path.display().to_string(),
        pid: u64::from(std::process::id()),
        schema_version: SCHEMA_VERSION,
    }
}

#[test]
fn missing_descriptor_ownership_identity_is_rejected() {
    let descriptor = ServerInstanceDescriptor {
        workspace_root: "/workspace".to_string(),
        backend_name: BackendName::Headless.canonical().to_string(),
        backend_version: "test".to_string(),
        runtime_instance_id: None,
        process_start_epoch_millis: None,
        owner_uid: None,
        socket_file_identity: None,
        transport: "uds".to_string(),
        socket_path: "/missing.sock".to_string(),
        pid: u64::from(std::process::id()),
        schema_version: SCHEMA_VERSION,
    };

    let error = validate_descriptor_owner(&descriptor).expect_err("legacy identity rejected");

    assert_eq!(error.code, "RUNTIME_IDENTITY_MISMATCH");
}

#[cfg(unix)]
#[test]
fn mismatched_descriptor_process_start_is_rejected_as_pid_reuse() {
    use std::os::unix::net::UnixListener;

    let temp = tempfile::tempdir().expect("socket directory");
    let socket_path = temp.path().join("runtime.sock");
    let _listener = UnixListener::bind(&socket_path).expect("runtime socket");
    let descriptor = current_process_descriptor(&socket_path);

    let error = validate_descriptor_owner(&descriptor).expect_err("PID reuse rejected");

    assert_eq!(error.code, "RUNTIME_IDENTITY_MISMATCH");
}

#[test]
fn migration_planner_returns_typed_patch_for_retired_default() {
    let plan = plan_legacy_backend_migration(
        "[runtime]\ndefaultBackend = \"idea\"\nstrictPluginMatching = true\n",
    )
    .expect("migration plan");

    let LegacyBackendMigrationPlan::Replace(patch) = plan else {
        panic!("expected migration patch");
    };
    assert_eq!(
        patch.migrated_contents(),
        "[runtime]\ndefaultBackend = \"headless\"\nstrictPluginMatching = true\n"
    );
}

#[test]
fn migration_planner_preserves_automatic_default() {
    assert_eq!(
        plan_legacy_backend_migration("[runtime]\ndefaultBackend = \"auto\"\n")
            .expect("migration plan"),
        LegacyBackendMigrationPlan::NoChange
    );
}

#[test]
fn headless_authority_accepts_every_server_mutation_capability() {
    let capabilities: Vec<SemanticMutationCapability> =
        serde_json::from_value(serde_json::json!([
            "RENAME",
            "APPLY_EDITS",
            "FILE_OPERATIONS",
            "OPTIMIZE_IMPORTS",
            "REFRESH_WORKSPACE"
        ]))
        .expect("complete server mutation capability domain");

    assert_eq!(
        capabilities,
        vec![
            SemanticMutationCapability::Rename,
            SemanticMutationCapability::ApplyEdits,
            SemanticMutationCapability::FileOperations,
            SemanticMutationCapability::OptimizeImports,
            SemanticMutationCapability::RefreshWorkspace,
        ]
    );
}

#[test]
fn runtime_rejection_retains_cli_error_details() {
    let mut error = CliError::new("NO_BACKEND_AVAILABLE", "backend unavailable");
    error.details.insert(
        "supportedDistribution".to_string(),
        "linux-headless-tarball".to_string(),
    );

    let rejection = runtime_cli_rejection(
        Path::new("/workspace"),
        SemanticWorkspaceKind::StandaloneGradleWorkspace,
        error,
    );

    assert_eq!(
        rejection.details.get("supportedDistribution"),
        Some(&"linux-headless-tarball".to_string())
    );
}
