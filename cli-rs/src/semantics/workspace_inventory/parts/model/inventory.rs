#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceLaneUnavailableReason(String);

impl WorkspaceLaneUnavailableReason {
    pub(crate) fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceLaneStamp<Stamp> {
    Available(Stamp),
    Unavailable(WorkspaceLaneUnavailableReason),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceLanePurpose {
    CandidateInventory,
    FilterEvidence,
    CandidateAndFilter,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceLaneEvidence<Stamp> {
    Irrelevant,
    Relevant {
        purpose: WorkspaceLanePurpose,
        stamp: WorkspaceLaneStamp<Stamp>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WorkspaceKindMatchCoverage {
    source: Option<WorkspaceCoverageDimension>,
    script: Option<WorkspaceCoverageDimension>,
}

impl WorkspaceKindMatchCoverage {
    pub(super) fn new(
        source: Option<WorkspaceCoverageDimension>,
        script: Option<WorkspaceCoverageDimension>,
    ) -> Self {
        Self { source, script }
    }

    pub(crate) fn source(self) -> Option<WorkspaceCoverageDimension> {
        self.source
    }

    pub(crate) fn script(self) -> Option<WorkspaceCoverageDimension> {
        self.script
    }

    fn force_partial(&mut self) {
        self.source = self.source.map(|_| WorkspaceCoverageDimension::Partial);
        self.script = self.script.map(|_| WorkspaceCoverageDimension::Partial);
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceInventoryFile {
    path: WorkspaceFilePath,
    backend_modules: BTreeSet<BackendModuleName>,
    indexed_gradle_projects: BTreeSet<BuildQualifiedGradleProjectIdentity>,
    source_sets: WorkspaceSourceSetEvidence,
    kind: WorkspaceFileKind,
    package: WorkspacePackageEvidence,
    index_state: WorkspaceFileIndexState,
    drift: WorkspaceFileDrift,
    dirty_state: WorkspaceFileDirtyState,
    evidence: BTreeSet<WorkspaceEvidenceSource>,
}

impl WorkspaceInventoryFile {
    pub(super) fn indexed_source(
        path: WorkspaceFilePath,
        indexed_gradle_projects: BTreeSet<BuildQualifiedGradleProjectIdentity>,
        source_sets: WorkspaceSourceSetEvidence,
        package: WorkspacePackageEvidence,
        index_state: WorkspaceFileIndexState,
        drift: WorkspaceFileDrift,
        evidence: BTreeSet<WorkspaceEvidenceSource>,
    ) -> Self {
        Self {
            path,
            backend_modules: BTreeSet::new(),
            indexed_gradle_projects,
            source_sets,
            kind: WorkspaceFileKind::Source,
            package,
            index_state,
            drift,
            dirty_state: WorkspaceFileDirtyState::NotApplicable,
            evidence,
        }
    }

    pub(crate) fn path(&self) -> &WorkspaceFilePath {
        &self.path
    }

    pub(crate) fn backend_modules(&self) -> &BTreeSet<BackendModuleName> {
        &self.backend_modules
    }

    pub(crate) fn indexed_gradle_projects(&self) -> &BTreeSet<BuildQualifiedGradleProjectIdentity> {
        &self.indexed_gradle_projects
    }

    pub(crate) fn source_sets(&self) -> &WorkspaceSourceSetEvidence {
        &self.source_sets
    }

    pub(crate) fn kind(&self) -> WorkspaceFileKind {
        self.kind
    }

    pub(crate) fn package(&self) -> &WorkspacePackageEvidence {
        &self.package
    }

    pub(crate) fn index_state(&self) -> &WorkspaceFileIndexState {
        &self.index_state
    }

    pub(crate) fn drift(&self) -> WorkspaceFileDrift {
        self.drift
    }

    pub(crate) fn dirty_state(&self) -> WorkspaceFileDirtyState {
        self.dirty_state
    }

    pub(crate) fn evidence(&self) -> &BTreeSet<WorkspaceEvidenceSource> {
        &self.evidence
    }

    pub(super) fn composed(
        path: WorkspaceFilePath,
        backend_modules: BTreeSet<BackendModuleName>,
        indexed: Option<&WorkspaceInventoryFile>,
        kind: WorkspaceFileKind,
        drift: WorkspaceFileDrift,
        dirty_state: WorkspaceFileDirtyState,
        evidence: BTreeSet<WorkspaceEvidenceSource>,
    ) -> Self {
        let index_state = if kind == WorkspaceFileKind::Script {
            WorkspaceFileIndexState::NotApplicable
        } else {
            indexed
                .map(|file| file.index_state.clone())
                .unwrap_or(WorkspaceFileIndexState::MetadataUnavailable)
        };
        Self {
            path,
            backend_modules,
            indexed_gradle_projects: indexed
                .map(|file| file.indexed_gradle_projects.clone())
                .unwrap_or_default(),
            source_sets: indexed
                .map(|file| file.source_sets.clone())
                .unwrap_or(WorkspaceSourceSetEvidence::Unavailable),
            kind,
            package: indexed
                .map(|file| file.package.clone())
                .unwrap_or(WorkspacePackageEvidence::Unavailable),
            index_state,
            drift,
            dirty_state,
            evidence,
        }
    }

    pub(super) fn force_cross_source_unknown(&mut self) {
        if self.kind == WorkspaceFileKind::Source {
            self.drift = WorkspaceFileDrift::Unknown;
        }
        if self.dirty_state != WorkspaceFileDirtyState::NotApplicable {
            self.dirty_state = WorkspaceFileDirtyState::Unknown;
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceInventoryLimitationCode {
    BackendMetadataUnavailable,
    BackendPageIncomplete,
    BackendWorkspaceInventoryStale,
    RuntimeIndexing,
    ProjectModelUnavailable,
    LinkedRootUnassociated,
    SourceIndexUnavailable,
    SourceIndexIncompatible,
    SourceIndexProgressIncomplete,
    SourceIndexUpdatesPending,
    GitUnavailable,
    CrossSourceCompositionUnstable,
    PathContainmentUnprovable,
    PackageMetadataInvalid,
    UnknownProjectModelOwnership,
    ProjectModelOwnershipUnknown,
    OutOfRootExcluded,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceInventorySnapshot {
    files: Vec<WorkspaceInventoryFile>,
    backend_coverage: BackendWorkspaceCoverage,
    backend_modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
    coverage: WorkspaceMatchCoverage,
    kind_coverage: WorkspaceKindMatchCoverage,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    continuation_allowed: bool,
    composition_digest: String,
}

pub(super) struct WorkspaceInventorySnapshotInputs {
    pub(super) files: Vec<WorkspaceInventoryFile>,
    pub(super) backend_coverage: BackendWorkspaceCoverage,
    pub(super) backend_modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
    pub(super) coverage: WorkspaceMatchCoverage,
    pub(super) kind_coverage: WorkspaceKindMatchCoverage,
    pub(super) limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    pub(super) continuation_allowed: bool,
    pub(super) composition_digest: String,
}

impl WorkspaceInventorySnapshot {
    pub(super) fn new(mut inputs: WorkspaceInventorySnapshotInputs) -> Self {
        inputs
            .files
            .sort_by(|left, right| left.path.cmp(&right.path));
        Self {
            files: inputs.files,
            backend_coverage: inputs.backend_coverage,
            backend_modules: inputs.backend_modules,
            coverage: inputs.coverage,
            kind_coverage: inputs.kind_coverage,
            limitations: inputs.limitations,
            continuation_allowed: inputs.continuation_allowed,
            composition_digest: inputs.composition_digest,
        }
    }

    pub(crate) fn files(&self) -> &[WorkspaceInventoryFile] {
        &self.files
    }

    pub(crate) fn backend_coverage(&self) -> BackendWorkspaceCoverage {
        self.backend_coverage
    }

    pub(crate) fn backend_modules(&self) -> &BTreeMap<BackendModuleName, BackendModuleInventory> {
        &self.backend_modules
    }

    pub(crate) fn coverage(&self) -> WorkspaceMatchCoverage {
        self.coverage
    }

    pub(crate) fn kind_coverage(&self) -> WorkspaceKindMatchCoverage {
        self.kind_coverage
    }

    pub(crate) fn limitations(&self) -> &BTreeMap<WorkspaceInventoryLimitationCode, usize> {
        &self.limitations
    }

    pub(crate) fn limitation_count(&self, code: WorkspaceInventoryLimitationCode) -> usize {
        self.limitations.get(&code).copied().unwrap_or_default()
    }

    pub(crate) fn continuation_allowed(&self) -> bool {
        self.continuation_allowed
    }

    pub(crate) fn composition_digest(&self) -> &str {
        &self.composition_digest
    }

    pub(super) fn mark_unstable(&mut self) {
        self.limitations
            .entry(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable)
            .and_modify(|count| *count += 1)
            .or_insert(1);
        self.coverage = WorkspaceMatchCoverage::from_dimensions(
            WorkspaceCoverageDimension::Partial,
            WorkspaceCoverageDimension::Partial,
        );
        self.kind_coverage.force_partial();
        self.continuation_allowed = false;
        for file in &mut self.files {
            file.force_cross_source_unknown();
        }
    }
}
