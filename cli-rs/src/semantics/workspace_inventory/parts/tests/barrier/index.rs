#[test]
fn source_index_generation_movement_retries_the_whole_composition_once() {
    let (_temp, root, fixture, responses) = barrier_fixture();
    let mut backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut lanes = MutatingIndexLaneReader::new(
        fixture.database_path().to_path_buf(),
        "UPDATE schema_version SET generation = generation + 1;",
        1,
    );

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(lanes.index_reads, 4);
    assert_eq!(backend.requests.len(), 8);
    assert!(snapshot.continuation_allowed());
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::InSync);
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
        0
    );
}

#[test]
fn second_source_index_movement_returns_typed_unstable_partial_evidence() {
    let (_temp, root, fixture, responses) = barrier_fixture();
    let mut backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut lanes = MutatingIndexLaneReader::new(
        fixture.database_path().to_path_buf(),
        "UPDATE schema_version SET generation = generation + 1;",
        2,
    );

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(lanes.index_reads, 4);
    assert_eq!(backend.requests.len(), 8);
    assert!(!snapshot.continuation_allowed());
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Partial
    );
    assert_eq!(
        snapshot.kind_coverage().source(),
        Some(WorkspaceCoverageDimension::Partial)
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
        1
    );
}

#[test]
fn stable_incomplete_progress_and_pending_updates_do_not_spin() {
    for (mutation_sql, limitation) in [
        (
            "UPDATE module_index_progress SET total_file_count = 2;",
            WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete,
        ),
        (
            "INSERT INTO pending_updates(op, prefix_id, filename, epoch_ms, applied) VALUES ('upsert_file', 1, 'Stable.kt', 2, 0);",
            WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending,
        ),
    ] {
        let (_temp, root, fixture, responses) = barrier_fixture();
        let mut backend = ScriptedWorkspaceBackend::new(
            responses
                .iter()
                .cloned()
                .chain(responses.iter().cloned())
                .collect(),
        );
        let mut lanes =
            MutatingIndexLaneReader::new(fixture.database_path().to_path_buf(), mutation_sql, 1);

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root,
                kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(lanes.index_reads, 4);
        assert_eq!(backend.requests.len(), 8);
        assert_eq!(snapshot.limitation_count(limitation), 1);
        assert_eq!(
            snapshot.coverage().candidate_inventory(),
            WorkspaceCoverageDimension::Partial
        );
        assert_eq!(
            snapshot
                .limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
            0
        );
    }
}
