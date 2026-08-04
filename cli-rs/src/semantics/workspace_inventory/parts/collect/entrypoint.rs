pub(crate) struct SystemWorkspaceLaneReader<'a> {
    published: &'a crate::published_workspace::PublishedWorkspaceDatabase,
}

impl<'a> SystemWorkspaceLaneReader<'a> {
    pub(crate) fn new(
        published: &'a crate::published_workspace::PublishedWorkspaceDatabase,
    ) -> Self {
        Self { published }
    }
}

impl WorkspaceInventoryLaneReader for SystemWorkspaceLaneReader<'_> {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::read_workspace_index_from_published(root, self.published)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        read_dirty_workspace(root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &BTreeSet<WorkspaceFilePath>,
    ) -> WorkspaceLaneStamp<WorkspaceFilesystemStamp> {
        WorkspaceLaneStamp::Available(observe_filesystem(root, paths))
    }
}

#[cfg(test)]
pub(crate) struct LiveCandidateWorkspaceLaneReader;

#[cfg(test)]
impl WorkspaceInventoryLaneReader for LiveCandidateWorkspaceLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::read_workspace_index_from_live_candidate_for_test(root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        read_dirty_workspace(root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &BTreeSet<WorkspaceFilePath>,
    ) -> WorkspaceLaneStamp<WorkspaceFilesystemStamp> {
        WorkspaceLaneStamp::Available(observe_filesystem(root, paths))
    }
}

pub(crate) struct WorkspaceInventoryInputs<'a> {
    pub(crate) root: WorkspaceRoot,
    pub(crate) kind_domain: WorkspaceRequestedKindDomain,
    pub(crate) dirty_evidence_relevant: bool,
    pub(crate) backend: &'a mut dyn BackendWorkspaceRpc,
    pub(crate) lanes: &'a mut dyn WorkspaceInventoryLaneReader,
}

pub(crate) type WorkspaceInventoryCollectionError = Infallible;

pub(crate) fn collect_workspace_inventory(
    mut inputs: WorkspaceInventoryInputs<'_>,
) -> Result<WorkspaceInventorySnapshot, WorkspaceInventoryCollectionError> {
    let (mut snapshot, stable) = collect_with_single_retry(|| {
        let collected = collect_attempt(&mut inputs);
        let stable = collected.before == collected.after;
        (collected.snapshot, stable)
    });
    if !stable {
        snapshot.mark_unstable();
    }
    Ok(snapshot)
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CompositionLaneStamps {
    backend: WorkspaceLaneEvidence<BackendWorkspaceStamp>,
    index: WorkspaceLaneEvidence<SourceIndexSnapshotStamp>,
    filesystem: WorkspaceLaneEvidence<WorkspaceFilesystemStamp>,
    dirty: WorkspaceLaneEvidence<DirtyWorkspaceStamp>,
}

struct CollectedAttempt {
    snapshot: WorkspaceInventorySnapshot,
    before: CompositionLaneStamps,
    after: CompositionLaneStamps,
}
