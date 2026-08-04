struct MutatingDirtyLaneReader {
    target: std::path::PathBuf,
    dirty_reads: usize,
    inner: super::collect::LiveCandidateWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for MutatingDirtyLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        self.dirty_reads += 1;
        if self.dirty_reads == 2 {
            std::fs::write(&self.target, "package sample\n\nclass Changed\n")
                .expect("Git lane mutation");
        }
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
fn git_movement_is_barrier_relevant_only_when_dirty_evidence_is_requested() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    git(
        root.as_path(),
        &["config", "user.email", "fixture@example.com"],
    );
    git(root.as_path(), &["config", "user.name", "Fixture"]);
    git(root.as_path(), &["add", "."]);
    git(root.as_path(), &["commit", "-qm", "fixture"]);
    let target = root.as_path().join("src/main/kotlin/sample/Stable.kt");

    let mut relevant_backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut relevant_lanes = MutatingDirtyLaneReader {
        target: target.clone(),
        dirty_reads: 0,
        inner: super::collect::LiveCandidateWorkspaceLaneReader,
    };
    let relevant =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root: root.clone(),
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: true,
            backend: &mut relevant_backend,
            lanes: &mut relevant_lanes,
        })
        .expect("dirty-relevant composition");

    std::fs::write(&target, "package sample\n").expect("restore clean content");
    git(
        root.as_path(),
        &["checkout", "--", "src/main/kotlin/sample/Stable.kt"],
    );
    let mut irrelevant_backend = ScriptedWorkspaceBackend::new(responses);
    let mut irrelevant_lanes = MutatingDirtyLaneReader {
        target,
        dirty_reads: 0,
        inner: super::collect::LiveCandidateWorkspaceLaneReader,
    };
    let irrelevant =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut irrelevant_backend,
            lanes: &mut irrelevant_lanes,
        })
        .expect("dirty-irrelevant composition");

    assert_eq!(relevant_lanes.dirty_reads, 4);
    assert_eq!(relevant_backend.requests.len(), 8);
    assert_eq!(
        relevant.files()[0].dirty_state(),
        WorkspaceFileDirtyState::Dirty
    );
    assert_eq!(irrelevant_lanes.dirty_reads, 0);
    assert_eq!(irrelevant_backend.requests.len(), 4);
    assert_eq!(
        irrelevant.files()[0].dirty_state(),
        WorkspaceFileDirtyState::NotApplicable
    );
}
