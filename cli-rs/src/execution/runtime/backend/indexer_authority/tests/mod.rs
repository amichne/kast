use super::*;
use std::cell::Cell;

fn current_process_descriptor(socket_path: &Path) -> ServerInstanceDescriptor {
    ServerInstanceDescriptor {
        workspace_root: "/workspace".to_string(),
        backend_name: BackendName::Indexer.canonical().to_string(),
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
        backend_name: BackendName::Indexer.canonical().to_string(),
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
fn migration_planner_removes_retired_backend_selection() {
    let plan = plan_legacy_backend_migration(
        "[runtime]\ndefaultBackend = \"idea\"\nstrictPluginMatching = true\n\n[runtime.ideaLaunch]\ncommand = \"/Applications/IntelliJ IDEA.app\"\n",
    )
    .expect("migration plan");

    let LegacyBackendMigrationPlan::Replace(patch) = plan else {
        panic!("expected migration patch");
    };
    assert_eq!(
        patch.migrated_contents(),
        "[indexer]\nhostCommand = \"/Applications/IntelliJ IDEA.app\"\n"
    );
}

#[test]
fn migration_planner_removes_automatic_default() {
    let plan = plan_legacy_backend_migration("[runtime]\ndefaultBackend = \"auto\"\n")
        .expect("migration plan");

    let LegacyBackendMigrationPlan::Replace(patch) = plan else {
        panic!("expected migration patch");
    };
    assert_eq!(patch.migrated_contents(), "");
}

#[test]
fn indexer_authority_accepts_every_server_mutation_capability() {
    let capabilities: Vec<SemanticMutationCapability> =
        serde_json::from_value(serde_json::json!([
            "RENAME",
            "APPLY_EDITS",
            "FILE_OPERATIONS",
            "OPTIMIZE_IMPORTS",
            "REFRESH_WORKSPACE",
            "PLAN_REPLACEMENT",
            "PLAN_ADD_FILE",
            "PLAN_ADD_DECLARATION",
            "EXACT_FILE_IMAGE_CAS",
            "EXACT_FILE_OBSERVATION",
            "MUTATION_SCRATCH_RECOVERY",
            "VERIFY_MUTATION_POSTCONDITION"
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
            SemanticMutationCapability::PlanReplacement,
            SemanticMutationCapability::PlanAddFile,
            SemanticMutationCapability::PlanAddDeclaration,
            SemanticMutationCapability::ExactFileImageCas,
            SemanticMutationCapability::ExactFileObservation,
            SemanticMutationCapability::MutationScratchRecovery,
            SemanticMutationCapability::VerifyMutationPostcondition,
        ]
    );
}

#[test]
fn indexer_distribution_rejection_projects_closed_distribution() {
    let rejection = indexer_distribution_unavailable_rejection(
        Path::new("/workspace"),
        SemanticWorkspaceKind::StandaloneGradleWorkspace,
    );
    assert_eq!(
        rejection.supported_distribution,
        Some(SupportedIndexerDistribution::LinuxIndexerTarball)
    );

    let error = super::super::semantic_workspace_rejection(rejection).into_cli_error();

    assert_eq!(
        error.details.get("supportedDistribution"),
        Some(&"linux-indexer-tarball".to_string())
    );
    assert!(error.details.contains_key("semanticWorkspace"));
}

#[test]
fn default_wait_admits_an_indexer_after_a_full_initial_gradle_refresh() {
    fn admit_after(delay_ms: u64, timeout_ms: u64) -> Option<()> {
        let elapsed_ms = Cell::new(0_u64);
        poll_for_runtime_candidate(
            timeout_ms,
            250,
            || elapsed_ms.get(),
            || (elapsed_ms.get() >= delay_ms).then_some(()),
            |duration_ms| elapsed_ms.set(elapsed_ms.get() + duration_ms),
        )
    }

    let delayed_initial_refresh_ms = 300_001;
    assert_eq!(None, admit_after(delayed_initial_refresh_ms, 60_000));
    assert_eq!(
        Some(()),
        admit_after(
            delayed_initial_refresh_ms,
            crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
        ),
    );
}

#[cfg(unix)]
#[test]
fn timed_out_spawned_indexer_is_stopped_before_control_returns() {
    let mut child = Command::new("/bin/sh")
        .args(["-c", "exec sleep 30"])
        .spawn()
        .expect("spawn timeout fixture");
    let elapsed_ms = Cell::new(0_u64);

    let candidate = poll_for_spawned_runtime_candidate(
        &mut child,
        1,
        1,
        || elapsed_ms.get(),
        || None::<()>,
        |duration_ms| elapsed_ms.set(elapsed_ms.get() + duration_ms),
    )
    .expect("bounded spawned runtime wait");

    assert_eq!(candidate, None);
    assert!(
        child.try_wait().expect("inspect stopped fixture").is_some(),
        "the exact child started by this admission must not outlive its timeout",
    );
}
