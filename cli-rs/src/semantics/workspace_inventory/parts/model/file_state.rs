#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceFileKind {
    Source,
    Script,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum SourceIndexIncompatibility {
    PackageMetadataReference,
    MalformedGradleProjectIdentity,
    MalformedGradleSourceSetIdentity,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceFileIndexState {
    Indexed,
    MetadataUnavailable,
    Incompatible(BTreeSet<SourceIndexIncompatibility>),
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkspaceFileDrift {
    InSync,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkspaceFileDirtyState {
    Clean,
    Dirty,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum DirtyWorkspaceCoverage {
    Complete,
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DirtyWorkspaceStamp {
    repository_root: PathBuf,
    dirty_paths: BTreeSet<WorkspaceFilePath>,
}

impl DirtyWorkspaceStamp {
    pub(super) fn new(repository_root: PathBuf, dirty_paths: BTreeSet<WorkspaceFilePath>) -> Self {
        Self {
            repository_root,
            dirty_paths,
        }
    }
}

#[cfg(test)]
impl DirtyWorkspaceStamp {
    pub(crate) fn repository_root(&self) -> &Path {
        &self.repository_root
    }

    pub(crate) fn dirty_paths(&self) -> &BTreeSet<WorkspaceFilePath> {
        &self.dirty_paths
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DirtyWorkspaceSnapshot {
    stamp: DirtyWorkspaceStamp,
    coverage: DirtyWorkspaceCoverage,
}

impl DirtyWorkspaceSnapshot {
    pub(super) fn complete(stamp: DirtyWorkspaceStamp) -> Self {
        Self {
            stamp,
            coverage: DirtyWorkspaceCoverage::Complete,
        }
    }

    pub(crate) fn stamp(&self) -> &DirtyWorkspaceStamp {
        &self.stamp
    }

    pub(crate) fn coverage(&self) -> DirtyWorkspaceCoverage {
        self.coverage
    }

    pub(crate) fn state_for(&self, path: &WorkspaceFilePath) -> WorkspaceFileDirtyState {
        if self.stamp.dirty_paths.contains(path) {
            WorkspaceFileDirtyState::Dirty
        } else {
            WorkspaceFileDirtyState::Clean
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum DirtyWorkspaceRead {
    Snapshot(DirtyWorkspaceSnapshot),
    Unavailable(WorkspaceLaneUnavailableReason),
}

impl DirtyWorkspaceRead {
    pub(crate) fn coverage(&self) -> DirtyWorkspaceCoverage {
        match self {
            Self::Snapshot(snapshot) => snapshot.coverage(),
            Self::Unavailable(_) => DirtyWorkspaceCoverage::Unavailable,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceFilesystemPathState {
    Present(PathBuf),
    Missing {
        canonical_ancestor: PathBuf,
        missing_suffix: PathBuf,
    },
    Unprovable,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceFilesystemStamp(
    BTreeMap<WorkspaceFilePath, WorkspaceFilesystemPathState>,
);

impl WorkspaceFilesystemStamp {
    pub(super) fn new(states: BTreeMap<WorkspaceFilePath, WorkspaceFilesystemPathState>) -> Self {
        Self(states)
    }

    pub(crate) fn state_for(
        &self,
        path: &WorkspaceFilePath,
    ) -> Option<&WorkspaceFilesystemPathState> {
        self.0.get(path)
    }
}

#[cfg(test)]
impl WorkspaceFilesystemStamp {
    pub(crate) fn states(&self) -> &BTreeMap<WorkspaceFilePath, WorkspaceFilesystemPathState> {
        &self.0
    }
}
