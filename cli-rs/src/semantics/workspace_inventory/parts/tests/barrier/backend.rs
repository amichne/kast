#[test]
fn backend_barrier_retries_availability_transitions_and_marks_second_movement_unstable() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");

    let mut becomes_unavailable = empty_available_backend("lease-a", "module");
    becomes_unavailable.extend([
        unavailable_backend("offline"),
        unavailable_backend("offline"),
        unavailable_backend("offline"),
    ]);
    let mut backend = ScriptedWorkspaceBackend::new(becomes_unavailable);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;
    let unavailable =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root: root.clone(),
            kind_domain: WorkspaceRequestedKindDomain::ScriptOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("unavailable composition");
    assert_eq!(backend.requests.len(), 5);
    assert_eq!(
        unavailable.backend_coverage(),
        BackendWorkspaceCoverage::Unavailable
    );
    assert!(unavailable.continuation_allowed());

    let mut becomes_available = vec![unavailable_backend("offline")];
    becomes_available.extend(empty_available_backend("lease-b", "module"));
    becomes_available.extend(empty_available_backend("lease-c", "module"));
    becomes_available.push(backend_result("lease-c", vec![]));
    let mut backend = ScriptedWorkspaceBackend::new(becomes_available);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;
    let available =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root: root.clone(),
            kind_domain: WorkspaceRequestedKindDomain::ScriptOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("available composition");
    assert_eq!(backend.requests.len(), 6);
    assert_eq!(
        available.backend_coverage(),
        BackendWorkspaceCoverage::Complete
    );
    assert!(available.continuation_allowed());

    let mut moves_twice = empty_available_backend("lease-e", "module-a");
    moves_twice.push(backend_api_failure("STALE_WORKSPACE_INVENTORY", None));
    moves_twice.extend(empty_available_backend("lease-f", "module-b"));
    moves_twice.push(backend_api_failure("STALE_WORKSPACE_INVENTORY", None));
    let mut backend = ScriptedWorkspaceBackend::new(moves_twice);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;
    let unstable =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::ScriptOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("unstable composition");
    assert_eq!(backend.requests.len(), 6);
    assert!(!unstable.continuation_allowed());
    assert_eq!(
        unstable.limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
        1
    );
}
