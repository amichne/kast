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
        if value.is_empty() || value.trim() != value || value.chars().any(char::is_control) {
            return None;
        }
        let mut segment = String::new();
        let mut in_backticks = false;
        let mut quoted = false;
        for character in value.chars() {
            match character {
                '`' if segment.is_empty() && !in_backticks => {
                    in_backticks = true;
                    quoted = true;
                }
                '`' if in_backticks => in_backticks = false,
                '.' if !in_backticks => {
                    valid_package_segment(&segment, quoted)?;
                    segment.clear();
                    quoted = false;
                }
                '.' if in_backticks => return None,
                _ if quoted && !in_backticks => return None,
                '/' | '\\' | ':' | '[' | ']' => return None,
                _ => segment.push(character),
            }
        }
        if in_backticks {
            return None;
        }
        valid_package_segment(&segment, quoted)?;
        Some(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

fn valid_package_segment(value: &str, quoted: bool) -> Option<String> {
    if value.is_empty() || value.chars().any(char::is_control) {
        return None;
    }
    if !quoted && !is_plain_kotlin_identifier(value) {
        return None;
    }
    Some(value.to_string())
}

fn is_plain_kotlin_identifier(value: &str) -> bool {
    plain_kotlin_identifier_validator().is_valid(&serde_json::Value::String(value.to_string()))
        && !is_kotlin_hard_keyword(value)
}

fn plain_kotlin_identifier_validator() -> &'static jsonschema::Validator {
    static VALIDATOR: std::sync::OnceLock<jsonschema::Validator> = std::sync::OnceLock::new();
    VALIDATOR.get_or_init(|| {
        let schema = serde_json::json!({
            "type": "string",
            "pattern": r"^(?:_|\p{L})(?:_|\p{L}|\p{Nd})*$"
        });
        jsonschema::options()
            .with_pattern_options(jsonschema::PatternOptions::regex())
            .build(&schema)
            .expect("the static indexed Kotlin package identifier schema is valid")
    })
}

fn is_kotlin_hard_keyword(value: &str) -> bool {
    matches!(
        value,
        "as" | "break"
            | "class"
            | "continue"
            | "do"
            | "else"
            | "false"
            | "for"
            | "fun"
            | "if"
            | "in"
            | "interface"
            | "is"
            | "null"
            | "object"
            | "package"
            | "return"
            | "super"
            | "this"
            | "throw"
            | "true"
            | "try"
            | "typealias"
            | "typeof"
            | "val"
            | "var"
            | "when"
            | "while"
    )
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
        if value.is_empty() || value.starts_with('/') || value.contains('\\') {
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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum WorkspaceInventoryLimitationCode {
    BackendCapabilityUnavailable,
    BackendMetadataUnavailable,
    BackendPageIncomplete,
    StaleBackendGeneration,
    RuntimeIndexing,
    ProjectModelUnavailable,
    LinkedRootUnassociated,
    SourceIndexUnavailable,
    SourceIndexIncompatible,
    SourceIndexProgressIncomplete,
    SourceIndexUpdatesPending,
    GitUnavailable,
    UnstableCrossSourceComposition,
    PathContainmentUnprovable,
    PackageMetadataInvalid,
    UnknownProjectModelOwnership,
    OutOfRootExcluded,
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
