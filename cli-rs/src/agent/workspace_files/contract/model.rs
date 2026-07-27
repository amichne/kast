#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdmittedWorkspaceFilesQueryIdentity {
    canonical_workspace_root: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend: Option<&'static str>,
    filters: AdmittedWorkspaceFileFilters,
    kind_domain: &'static str,
    view: &'static str,
    ordered_fields: Vec<&'static str>,
    limit: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdmittedWorkspaceFileFilters {
    #[serde(skip_serializing_if = "Option::is_none")]
    module: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_set: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    package: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    package_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    dirty: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    drift: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    path_prefix: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    glob: Option<String>,
}

#[derive(Debug, Serialize)]
struct WorkspaceFilesPageHandleIdentity {
    token: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesNextAction {
    kind: &'static str,
    command: &'static str,
    arguments: Vec<String>,
    mutates_global_install_authority: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    workspace_root: String,
    files: WorkspaceFilesResultFiles,
    cardinality: AgentResultCardinality,
    returned_count: usize,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
    coverage: WorkspaceFilesCoverage,
    limitations: Vec<WorkspaceFilesLimitation>,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend_page_coverage: Option<WorkspaceFilesBackendPageCoverage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    classification_evidence: Option<Vec<WorkspaceFilesClassificationEvidence>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    normalized_query: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    composition_digest: Option<String>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
enum WorkspaceFilesResultFiles {
    Compact(Vec<WorkspaceFileCompactGroup>),
    Detailed(Vec<WorkspaceFileDetailedRecord>),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesContinuationIdentity {
    workspace_root: String,
    backend_name: String,
    normalized_query: String,
    projection: String,
    limit: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesContinuationState {
    identity: WorkspaceFilesContinuationIdentity,
    composition_stamp_digest: String,
    last_relative_path: String,
    cumulative_returned_count: usize,
}

struct ValidatedWorkspaceFilesContinuation<'a> {
    state: &'a WorkspaceFilesContinuationState,
}

#[derive(Debug, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum WorkspaceFilesContinuationResult {
    Issued { page_token: String },
    Consumed { state: WorkspaceFilesContinuationState },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFileDetailedRecord {
    file_path: String,
    relative_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend_modules: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    indexed_gradle_projects: Option<Vec<WorkspaceFilesGradleProject>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_sets: Option<WorkspaceFilesSourceSetEvidence>,
    kind: WorkspaceFilesKind,
    package: WorkspaceFilesPackageEvidence,
    source_index: WorkspaceFilesIndexState,
    drift: WorkspaceFilesDrift,
    dirty: WorkspaceFilesDirty,
    #[serde(skip_serializing_if = "Option::is_none")]
    evidence: Option<Vec<WorkspaceFilesEvidenceSource>>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFileCompactGroup {
    #[serde(flatten)]
    evidence: WorkspaceFileCompactEvidence,
    paths: Vec<WorkspaceFileCompactPath>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFileCompactPath {
    file_path: String,
    relative_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFileCompactEvidence {
    backend_modules: Vec<String>,
    indexed_gradle_projects: Vec<WorkspaceFilesGradleProject>,
    source_sets: WorkspaceFilesSourceSetEvidence,
    kind: WorkspaceFilesKind,
    package: WorkspaceFilesPackageEvidence,
    source_index: WorkspaceFilesIndexState,
    drift: WorkspaceFilesDrift,
    dirty: WorkspaceFilesDirty,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesGradleProject {
    build_root: String,
    project_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", rename_all_fields = "camelCase")]
enum WorkspaceFilesSourceSetEvidence {
    Proven { source_sets: Vec<WorkspaceFilesGradleSourceSet> },
    Unproven { labels: Vec<String> },
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesGradleSourceSet {
    build_root: String,
    project_path: String,
    source_set_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", rename_all_fields = "camelCase")]
enum WorkspaceFilesPackageEvidence {
    ProvenRoot,
    ProvenNamed { name: String },
    Unproven,
    Unavailable,
    InvalidReference,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesKind {
    KotlinSource,
    KotlinScript,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesIndexState {
    Indexed,
    NotIndexed,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesDrift {
    None,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesDirty {
    Clean,
    Dirty,
    Unknown,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesEvidenceSource {
    Manifest,
    PackageMetadata,
    GradleProjectModel,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesCoverage {
    candidate_inventory: WorkspaceFilesCoverageDimension,
    filter_evidence: WorkspaceFilesCoverageDimension,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesCoverageDimension {
    Complete,
    Partial,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesLimitation {
    code: &'static str,
    count: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesBackendPageCoverage {
    workspace: WorkspaceFilesBackendCoverage,
    modules: Vec<WorkspaceFilesBackendModuleCoverage>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesBackendCoverage {
    Complete,
    Partial,
    Unavailable,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesBackendModuleCoverage {
    module_name: String,
    declared_file_count: usize,
    coverage: WorkspaceFilesModuleCoverage,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WorkspaceFilesModuleCoverage {
    Complete,
    Partial,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceFilesClassificationEvidence {
    relative_path: String,
    kind: WorkspaceFilesKind,
    sources: Vec<WorkspaceFilesEvidenceSource>,
    package: &'static str,
    source_sets: &'static str,
}
