use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::path::{Component, Path, PathBuf};

use thiserror::Error;

#[derive(Debug, Error)]
pub(crate) enum WorkspaceRootError {
    #[error("workspace root `{path}` cannot be canonicalized: {source}")]
    Canonicalize {
        path: PathBuf,
        source: std::io::Error,
    },
    #[error("workspace root `{0}` is not a directory")]
    NotDirectory(PathBuf),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceRoot(PathBuf);

impl WorkspaceRoot {
    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

impl TryFrom<&Path> for WorkspaceRoot {
    type Error = WorkspaceRootError;

    fn try_from(path: &Path) -> Result<Self, Self::Error> {
        let canonical =
            std::fs::canonicalize(path).map_err(|source| WorkspaceRootError::Canonicalize {
                path: path.to_path_buf(),
                source,
            })?;
        if !canonical.is_dir() {
            return Err(WorkspaceRootError::NotDirectory(canonical));
        }
        Ok(Self(canonical))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceFilePath(PathBuf);

impl WorkspaceFilePath {
    pub(super) fn from_relative_path(path: PathBuf) -> Option<Self> {
        let mut saw_component = false;
        for component in path.components() {
            match component {
                Component::Normal(_) => saw_component = true,
                Component::CurDir if !saw_component => {}
                Component::CurDir
                | Component::ParentDir
                | Component::RootDir
                | Component::Prefix(_) => return None,
            }
        }
        saw_component.then_some(Self(path))
    }

    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

impl fmt::Display for WorkspaceFilePath {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.display().fmt(formatter)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexGeneration(u64);

impl SourceIndexGeneration {
    pub(super) fn try_from_database(value: i64) -> Option<Self> {
        u64::try_from(value).ok().map(Self)
    }

    pub(crate) fn value(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexPendingCount(u64);

impl SourceIndexPendingCount {
    pub(super) fn try_from_database(value: i64) -> Option<Self> {
        u64::try_from(value).ok().map(Self)
    }

    pub(crate) fn value(self) -> u64 {
        self.0
    }

    pub(crate) fn is_empty(self) -> bool {
        self.0 == 0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexModuleName(String);

impl SourceIndexModuleName {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum SourceIndexProgressStatus {
    Pending,
    Indexing,
    Complete,
    Failed,
}

impl SourceIndexProgressStatus {
    pub(super) fn parse(value: &str) -> Option<Self> {
        match value {
            "PENDING" => Some(Self::Pending),
            "INDEXING" => Some(Self::Indexing),
            "COMPLETE" => Some(Self::Complete),
            "FAILED" => Some(Self::Failed),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SourceIndexModuleProgress {
    module_name: SourceIndexModuleName,
    status: SourceIndexProgressStatus,
    indexed_file_count: u64,
    total_file_count: u64,
}

impl SourceIndexModuleProgress {
    pub(super) fn from_database(
        module_name: String,
        status: String,
        indexed_file_count: i64,
        total_file_count: i64,
    ) -> Option<Self> {
        Some(Self {
            module_name: SourceIndexModuleName::parse(module_name)?,
            status: SourceIndexProgressStatus::parse(&status)?,
            indexed_file_count: u64::try_from(indexed_file_count).ok()?,
            total_file_count: u64::try_from(total_file_count).ok()?,
        })
    }

    pub(crate) fn module_name(&self) -> &SourceIndexModuleName {
        &self.module_name
    }

    pub(crate) fn status(&self) -> SourceIndexProgressStatus {
        self.status
    }

    pub(crate) fn indexed_file_count(&self) -> u64 {
        self.indexed_file_count
    }

    pub(crate) fn total_file_count(&self) -> u64 {
        self.total_file_count
    }

    fn is_exact(&self) -> bool {
        self.status == SourceIndexProgressStatus::Complete
            && self.indexed_file_count == self.total_file_count
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SourceIndexSnapshotStamp {
    generation: SourceIndexGeneration,
    module_progress: BTreeSet<SourceIndexModuleProgress>,
    pending_count: SourceIndexPendingCount,
    progress_compatible: bool,
}

impl SourceIndexSnapshotStamp {
    pub(super) fn new(
        generation: SourceIndexGeneration,
        module_progress: BTreeSet<SourceIndexModuleProgress>,
        pending_count: SourceIndexPendingCount,
        progress_compatible: bool,
    ) -> Self {
        Self {
            generation,
            module_progress,
            pending_count,
            progress_compatible,
        }
    }

    pub(crate) fn generation(&self) -> SourceIndexGeneration {
        self.generation
    }

    pub(crate) fn module_progress(&self) -> &BTreeSet<SourceIndexModuleProgress> {
        &self.module_progress
    }

    pub(crate) fn pending_count(&self) -> SourceIndexPendingCount {
        self.pending_count
    }

    pub(crate) fn is_exact(&self) -> bool {
        self.progress_compatible
            && !self.module_progress.is_empty()
            && self
                .module_progress
                .iter()
                .all(SourceIndexModuleProgress::is_exact)
            && self.pending_count.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct KotlinPackageFqName(String);

impl KotlinPackageFqName {
    pub(super) fn parse_persisted(value: String) -> Option<Self> {
        if value.is_empty()
            || value.trim() != value
            || value.chars().any(char::is_control)
            || value.split('.').any(str::is_empty)
        {
            return None;
        }
        Some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspacePackageUnprovenReason {
    NotScanned,
    SemanticAnalysisUnavailable,
    SemanticAnalysisFailed,
    LegacyTextOnly,
}

impl WorkspacePackageUnprovenReason {
    pub(super) fn parse(value: &str) -> Option<Self> {
        match value {
            "NOT_SCANNED" => Some(Self::NotScanned),
            "SEMANTIC_ANALYSIS_UNAVAILABLE" => Some(Self::SemanticAnalysisUnavailable),
            "SEMANTIC_ANALYSIS_FAILED" => Some(Self::SemanticAnalysisFailed),
            "LEGACY_TEXT_ONLY" => Some(Self::LegacyTextOnly),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspacePackageInvalidReference {
    InvalidState,
    IllegalStateTuple,
    DanglingFqName,
    InvalidFqName,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspacePackageEvidence {
    ProvenRoot,
    ProvenNamed(KotlinPackageFqName),
    Unproven(WorkspacePackageUnprovenReason),
    Unavailable,
    InvalidReference(WorkspacePackageInvalidReference),
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceRelativeGradleBuildRoot(PathBuf);

impl WorkspaceRelativeGradleBuildRoot {
    pub(super) fn parse(value: String) -> Option<Self> {
        if value == "." {
            return Some(Self(PathBuf::new()));
        }
        if value.is_empty()
            || value.starts_with('/')
            || value.contains('\\')
            || value.chars().any(char::is_control)
            || has_windows_drive_prefix(&value)
        {
            return None;
        }
        let segments: Vec<_> = value.split('/').collect();
        if segments
            .iter()
            .any(|segment| segment.is_empty() || matches!(*segment, "." | ".."))
        {
            return None;
        }
        Some(Self(segments.iter().collect()))
    }

    pub(crate) fn as_path(&self) -> &Path {
        &self.0
    }
}

fn has_windows_drive_prefix(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':'
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct GradleProjectPath(String);

impl GradleProjectPath {
    pub(super) fn parse(value: String) -> Option<Self> {
        if !value.starts_with(':')
            || value.contains(['/', '\\', '#'])
            || value.chars().any(char::is_control)
            || (value != ":"
                && value
                    .split(':')
                    .skip(1)
                    .any(|segment| segment.is_empty() || segment.trim() != segment))
        {
            return None;
        }
        Some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BuildQualifiedGradleProjectIdentity {
    build_root: WorkspaceRelativeGradleBuildRoot,
    project_path: GradleProjectPath,
}

impl BuildQualifiedGradleProjectIdentity {
    pub(super) fn parse(build_root: String, project_path: String) -> Option<Self> {
        Some(Self {
            build_root: WorkspaceRelativeGradleBuildRoot::parse(build_root)?,
            project_path: GradleProjectPath::parse(project_path)?,
        })
    }

    pub(crate) fn build_root(&self) -> &WorkspaceRelativeGradleBuildRoot {
        &self.build_root
    }

    pub(crate) fn project_path(&self) -> &GradleProjectPath {
        &self.project_path
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct GradleSourceSetName(String);

impl GradleSourceSetName {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty()
            && value.trim() == value
            && !value.contains(['/', '\\', ':', '#'])
            && !value.chars().any(char::is_control))
        .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BuildQualifiedGradleSourceSetIdentity {
    project: BuildQualifiedGradleProjectIdentity,
    source_set_name: GradleSourceSetName,
}

impl BuildQualifiedGradleSourceSetIdentity {
    pub(super) fn parse(
        build_root: String,
        project_path: String,
        source_set_name: String,
    ) -> Option<Self> {
        Some(Self {
            project: BuildQualifiedGradleProjectIdentity::parse(build_root, project_path)?,
            source_set_name: GradleSourceSetName::parse(source_set_name)?,
        })
    }

    pub(crate) fn project(&self) -> &BuildQualifiedGradleProjectIdentity {
        &self.project
    }

    pub(crate) fn source_set_name(&self) -> &GradleSourceSetName {
        &self.source_set_name
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct LegacySourceSetLabel(String);

impl LegacySourceSetLabel {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceSourceSetEvidence {
    Proven(BTreeSet<BuildQualifiedGradleSourceSetIdentity>),
    Unproven(BTreeSet<LegacySourceSetLabel>),
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceFileKind {
    Source,
    Script,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum SourceIndexIncompatibility {
    MissingPathPrefix,
    InvalidPackageMetadata,
    InvalidGradleProjectIdentity,
    InvalidGradleSourceSetIdentity,
    DanglingGradleSourceSetOwner,
    OrphanGradleAssociation,
    InvalidProgress,
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

    pub(crate) fn states(&self) -> &BTreeMap<WorkspaceFilePath, WorkspaceFilesystemPathState> {
        &self.0
    }

    pub(crate) fn state_for(
        &self,
        path: &WorkspaceFilePath,
    ) -> Option<&WorkspaceFilesystemPathState> {
        self.0.get(path)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceEvidenceSource {
    Manifest,
    PackageMetadata,
    GradleProjectModel,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BackendModuleName(String);

impl BackendModuleName {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BackendWorkspaceSnapshotToken(String);

impl BackendWorkspaceSnapshotToken {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct BackendWorkspacePageToken(String);

impl BackendWorkspacePageToken {
    pub(super) fn parse(value: String) -> Option<Self> {
        (!value.is_empty() && value.trim() == value && !value.chars().any(char::is_control))
            .then_some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceRequestedKindDomain {
    SourceOnly,
    ScriptOnly,
    Mixed,
}

impl WorkspaceRequestedKindDomain {
    pub(crate) fn includes_sources(self) -> bool {
        matches!(self, Self::SourceOnly | Self::Mixed)
    }

    pub(crate) fn includes_scripts(self) -> bool {
        matches!(self, Self::ScriptOnly | Self::Mixed)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum BackendWorkspaceCoverage {
    Complete,
    Partial,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum BackendModuleCoverage {
    Complete,
    Partial,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendModuleInventory {
    name: BackendModuleName,
    source_roots: BTreeSet<PathBuf>,
    content_roots: BTreeSet<PathBuf>,
    dependency_module_names: BTreeSet<BackendModuleName>,
    declared_file_count: usize,
    coverage: BackendModuleCoverage,
}

impl BackendModuleInventory {
    pub(super) fn new(
        name: BackendModuleName,
        source_roots: BTreeSet<PathBuf>,
        content_roots: BTreeSet<PathBuf>,
        dependency_module_names: BTreeSet<BackendModuleName>,
        declared_file_count: usize,
        coverage: BackendModuleCoverage,
    ) -> Self {
        Self {
            name,
            source_roots,
            content_roots,
            dependency_module_names,
            declared_file_count,
            coverage,
        }
    }

    pub(crate) fn name(&self) -> &BackendModuleName {
        &self.name
    }

    pub(crate) fn source_roots(&self) -> &BTreeSet<PathBuf> {
        &self.source_roots
    }

    pub(crate) fn content_roots(&self) -> &BTreeSet<PathBuf> {
        &self.content_roots
    }

    pub(crate) fn dependency_module_names(&self) -> &BTreeSet<BackendModuleName> {
        &self.dependency_module_names
    }

    pub(crate) fn declared_file_count(&self) -> usize {
        self.declared_file_count
    }

    pub(crate) fn coverage(&self) -> BackendModuleCoverage {
        self.coverage
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendModuleLeaseFingerprint {
    source_roots: BTreeSet<PathBuf>,
    content_roots: BTreeSet<PathBuf>,
    dependency_module_names: BTreeSet<BackendModuleName>,
    declared_file_count: usize,
}

impl BackendModuleLeaseFingerprint {
    fn from_inventory(module: &BackendModuleInventory) -> Self {
        Self {
            source_roots: module.source_roots.clone(),
            content_roots: module.content_roots.clone(),
            dependency_module_names: module.dependency_module_names.clone(),
            declared_file_count: module.declared_file_count,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendWorkspaceStamp {
    snapshot_token: BackendWorkspaceSnapshotToken,
    modules: BTreeMap<BackendModuleName, BackendModuleLeaseFingerprint>,
}

impl BackendWorkspaceStamp {
    fn from_inventory(
        snapshot_token: BackendWorkspaceSnapshotToken,
        modules: &BTreeMap<BackendModuleName, BackendModuleInventory>,
    ) -> Self {
        Self {
            snapshot_token,
            modules: modules
                .iter()
                .map(|(name, module)| {
                    (
                        name.clone(),
                        BackendModuleLeaseFingerprint::from_inventory(module),
                    )
                })
                .collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BackendWorkspaceInventory {
    files: BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>>,
    modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
    coverage: BackendWorkspaceCoverage,
    snapshot_token: Option<BackendWorkspaceSnapshotToken>,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
}

impl BackendWorkspaceInventory {
    pub(super) fn new(
        files: BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>>,
        modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
        coverage: BackendWorkspaceCoverage,
        snapshot_token: Option<BackendWorkspaceSnapshotToken>,
        limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    ) -> Self {
        Self {
            files,
            modules,
            coverage,
            snapshot_token,
            limitations,
        }
    }

    pub(crate) fn files(&self) -> &BTreeMap<WorkspaceFilePath, BTreeSet<BackendModuleName>> {
        &self.files
    }

    pub(crate) fn modules(&self) -> &BTreeMap<BackendModuleName, BackendModuleInventory> {
        &self.modules
    }

    pub(crate) fn coverage(&self) -> BackendWorkspaceCoverage {
        self.coverage
    }

    pub(crate) fn snapshot_token(&self) -> Option<&BackendWorkspaceSnapshotToken> {
        self.snapshot_token.as_ref()
    }

    pub(crate) fn stamp(&self) -> Option<BackendWorkspaceStamp> {
        self.snapshot_token
            .clone()
            .map(|token| BackendWorkspaceStamp::from_inventory(token, &self.modules))
    }

    pub(crate) fn limitations(&self) -> &BTreeMap<WorkspaceInventoryLimitationCode, usize> {
        &self.limitations
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct WorkspaceLaneUnavailableReason(String);

impl WorkspaceLaneUnavailableReason {
    pub(crate) fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
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
    BackendCapabilityUnavailable,
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
    coverage: WorkspaceMatchCoverage,
    kind_coverage: WorkspaceKindMatchCoverage,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    continuation_allowed: bool,
    composition_digest: String,
}

impl WorkspaceInventorySnapshot {
    pub(super) fn new(
        mut files: Vec<WorkspaceInventoryFile>,
        backend_coverage: BackendWorkspaceCoverage,
        coverage: WorkspaceMatchCoverage,
        kind_coverage: WorkspaceKindMatchCoverage,
        limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
        continuation_allowed: bool,
        composition_digest: String,
    ) -> Self {
        files.sort_by(|left, right| left.path.cmp(&right.path));
        Self {
            files,
            backend_coverage,
            coverage,
            kind_coverage,
            limitations,
            continuation_allowed,
            composition_digest,
        }
    }

    pub(crate) fn files(&self) -> &[WorkspaceInventoryFile] {
        &self.files
    }

    pub(crate) fn backend_coverage(&self) -> BackendWorkspaceCoverage {
        self.backend_coverage
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkspaceCoverageDimension {
    Complete,
    Partial,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WorkspaceMatchCoverage {
    candidate_inventory: WorkspaceCoverageDimension,
    filter_evidence: WorkspaceCoverageDimension,
}

impl WorkspaceMatchCoverage {
    pub(crate) fn complete() -> Self {
        Self {
            candidate_inventory: WorkspaceCoverageDimension::Complete,
            filter_evidence: WorkspaceCoverageDimension::Complete,
        }
    }

    pub(super) fn from_dimensions(
        candidate_inventory: WorkspaceCoverageDimension,
        filter_evidence: WorkspaceCoverageDimension,
    ) -> Self {
        Self {
            candidate_inventory,
            filter_evidence,
        }
    }

    pub(crate) fn candidate_inventory(self) -> WorkspaceCoverageDimension {
        self.candidate_inventory
    }

    pub(crate) fn filter_evidence(self) -> WorkspaceCoverageDimension {
        self.filter_evidence
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceIndexSnapshot {
    files: Vec<WorkspaceInventoryFile>,
    stamp: SourceIndexSnapshotStamp,
    limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    coverage: WorkspaceMatchCoverage,
}

impl WorkspaceIndexSnapshot {
    pub(super) fn new(
        mut files: Vec<WorkspaceInventoryFile>,
        stamp: SourceIndexSnapshotStamp,
        limitations: BTreeMap<WorkspaceInventoryLimitationCode, usize>,
        coverage: WorkspaceMatchCoverage,
    ) -> Self {
        files.sort_by(|left, right| left.path.cmp(&right.path));
        Self {
            files,
            stamp,
            limitations,
            coverage,
        }
    }

    pub(crate) fn files(&self) -> &[WorkspaceInventoryFile] {
        &self.files
    }

    pub(crate) fn stamp(&self) -> &SourceIndexSnapshotStamp {
        &self.stamp
    }

    pub(crate) fn limitations(&self) -> &BTreeMap<WorkspaceInventoryLimitationCode, usize> {
        &self.limitations
    }

    pub(crate) fn limitation_count(&self, code: WorkspaceInventoryLimitationCode) -> usize {
        self.limitations.get(&code).copied().unwrap_or_default()
    }

    pub(crate) fn coverage(&self) -> WorkspaceMatchCoverage {
        self.coverage
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct WorkspaceIndexReadFailure {
    limitation: WorkspaceInventoryLimitationCode,
    detail: String,
}

impl WorkspaceIndexReadFailure {
    pub(super) fn new(limitation: WorkspaceInventoryLimitationCode, detail: String) -> Self {
        Self { limitation, detail }
    }

    pub(crate) fn limitation(&self) -> WorkspaceInventoryLimitationCode {
        self.limitation
    }

    pub(crate) fn detail(&self) -> &str {
        &self.detail
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum WorkspaceIndexRead {
    Snapshot(WorkspaceIndexSnapshot),
    Unavailable(WorkspaceIndexReadFailure),
    Incompatible(WorkspaceIndexReadFailure),
}
