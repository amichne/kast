struct MutatingFilesystemLaneReader {
    target: std::path::PathBuf,
    filesystem_reads: usize,
    inner: super::collect::LiveCandidateWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for MutatingFilesystemLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        self.filesystem_reads += 1;
        if self.filesystem_reads == 2 {
            std::fs::remove_file(&self.target).expect("filesystem lane mutation");
        }
        let stamp = super::collect::WorkspaceInventoryLaneReader::read_filesystem(
            &mut self.inner,
            root,
            paths,
        );
        if let super::model::WorkspaceLaneStamp::Available(stamp) = &stamp {
            assert_eq!(stamp.states().len(), paths.len());
        }
        stamp
    }
}

#[test]
fn filesystem_existence_movement_discards_the_attempt_and_retries_once() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    let target = root.as_path().join("src/main/kotlin/sample/Stable.kt");
    let mut backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut lanes = MutatingFilesystemLaneReader {
        target,
        filesystem_reads: 0,
        inner: super::collect::LiveCandidateWorkspaceLaneReader,
    };

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(lanes.filesystem_reads, 4);
    assert_eq!(backend.requests.len(), 8);
    assert_eq!(
        snapshot.files()[0].drift(),
        WorkspaceFileDrift::MissingOnDisk
    );
    assert!(snapshot.continuation_allowed());
}

struct UnavailableFilesystemLaneReader {
    reason: super::model::WorkspaceLaneUnavailableReason,
    inner: super::collect::LiveCandidateWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for UnavailableFilesystemLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        _root: &WorkspaceRoot,
        _paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        super::model::WorkspaceLaneStamp::Unavailable(self.reason.clone())
    }
}

#[test]
fn stable_filesystem_unavailability_retains_proven_backend_candidates_and_reason_identity() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    let mut digests = Vec::new();
    for reason in ["permission-denied", "observer-closed"] {
        let mut backend = ScriptedWorkspaceBackend::new(responses.clone());
        let mut lanes = UnavailableFilesystemLaneReader {
            reason: super::model::WorkspaceLaneUnavailableReason::new(reason),
            inner: super::collect::LiveCandidateWorkspaceLaneReader,
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

        assert_eq!(snapshot.files().len(), 1);
        assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
        assert_eq!(
            snapshot.coverage().candidate_inventory(),
            WorkspaceCoverageDimension::Partial
        );
        assert!(snapshot.continuation_allowed());
        digests.push(snapshot.composition_digest().to_string());
    }
    assert_ne!(digests[0], digests[1]);
}
