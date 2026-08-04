struct CountingSystemLaneReader {
    index_reads: usize,
    filesystem_reads: usize,
    dirty_reads: usize,
    inner: super::collect::LiveCandidateWorkspaceLaneReader,
}

impl CountingSystemLaneReader {
    fn new() -> Self {
        Self {
            index_reads: 0,
            filesystem_reads: 0,
            dirty_reads: 0,
            inner: super::collect::LiveCandidateWorkspaceLaneReader,
        }
    }
}

impl super::collect::WorkspaceInventoryLaneReader for CountingSystemLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        self.index_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        self.dirty_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        self.filesystem_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

#[test]
fn kind_relevance_skips_the_source_index_for_script_only_and_keeps_mixed_coverage_separate() {
    let temp = tempfile::tempdir().expect("workspace");
    std::fs::write(temp.path().join("Source.kt"), "class Source\n").expect("source");
    std::fs::write(temp.path().join("build.gradle.kts"), "plugins {}\n").expect("script");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    for (domain, files, expected_index_reads) in [
        (
            WorkspaceRequestedKindDomain::ScriptOnly,
            vec!["build.gradle.kts"],
            0,
        ),
        (
            WorkspaceRequestedKindDomain::SourceOnly,
            vec!["Source.kt"],
            2,
        ),
        (
            WorkspaceRequestedKindDomain::Mixed,
            vec!["Source.kt", "build.gradle.kts"],
            2,
        ),
    ] {
        let mut responses = complete_backend_responses("snapshot", "module", &[], &[], &files);
        responses.push(backend_result("snapshot", vec![]));
        let mut backend = ScriptedWorkspaceBackend::new(responses);
        let mut lanes = CountingSystemLaneReader::new();

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root: root.clone(),
                kind_domain: domain,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(lanes.index_reads, expected_index_reads, "domain={domain:?}");
        assert_eq!(lanes.dirty_reads, 0, "domain={domain:?}");
        if domain.includes_scripts() {
            assert_eq!(
                snapshot.kind_coverage().script(),
                Some(WorkspaceCoverageDimension::Complete),
                "domain={domain:?}"
            );
        }
        if domain == WorkspaceRequestedKindDomain::Mixed {
            assert_eq!(
                snapshot.kind_coverage().source(),
                Some(WorkspaceCoverageDimension::Partial)
            );
        }
    }
}
