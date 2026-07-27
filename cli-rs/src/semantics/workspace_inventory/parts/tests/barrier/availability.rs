struct ScriptedIndexLaneReader {
    index: VecDeque<WorkspaceIndexRead>,
    index_reads: usize,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for ScriptedIndexLaneReader {
    fn read_source_index(&mut self, _root: &WorkspaceRoot) -> WorkspaceIndexRead {
        self.index_reads += 1;
        self.index.pop_front().expect("scripted index observation")
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

#[test]
fn lane_availability_and_unavailable_reason_transitions_participate_in_the_barrier() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    let available = read_workspace_index(&root);
    let unavailable_a = WorkspaceIndexRead::Unavailable(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        "reason-a".to_string(),
    ));
    let unavailable_b = WorkspaceIndexRead::Unavailable(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        "reason-b".to_string(),
    ));
    for (observations, expected_coverage) in [
        (
            vec![
                available.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
            ],
            WorkspaceCoverageDimension::Partial,
        ),
        (
            vec![
                unavailable_b.clone(),
                available.clone(),
                available.clone(),
                available.clone(),
            ],
            WorkspaceCoverageDimension::Complete,
        ),
        (
            vec![
                unavailable_a.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
            ],
            WorkspaceCoverageDimension::Partial,
        ),
        (
            vec![
                unavailable_b.clone(),
                unavailable_a.clone(),
                unavailable_a.clone(),
                unavailable_a.clone(),
            ],
            WorkspaceCoverageDimension::Partial,
        ),
    ] {
        let mut backend = ScriptedWorkspaceBackend::new(
            responses
                .iter()
                .cloned()
                .chain(responses.iter().cloned())
                .collect(),
        );
        let mut lanes = ScriptedIndexLaneReader {
            index: observations.into(),
            index_reads: 0,
            inner: super::collect::SystemWorkspaceLaneReader,
        };

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root: root.clone(),
                kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(lanes.index_reads, 4);
        assert!(snapshot.continuation_allowed());
        assert_eq!(snapshot.coverage().candidate_inventory(), expected_coverage);
    }
}
