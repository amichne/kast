#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SemanticGraphReadinessState {
    Ready,
    Incomplete,
    Unavailable,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SemanticGraphReadinessError {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SemanticGraphReadiness {
    pub state: SemanticGraphReadinessState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub generation: Option<u64>,
    pub total: usize,
    pub indexed: usize,
    pub excluded: usize,
    pub pending: usize,
    pub limited: usize,
    pub failed: usize,
    pub stale: usize,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub limitations: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<SemanticGraphReadinessError>,
}

impl SemanticGraphReadiness {
    pub fn is_ready(&self) -> bool {
        self.state == SemanticGraphReadinessState::Ready
    }
}

#[derive(Debug, Clone)]
struct SemanticFileRow {
    manifest_content_hash: Option<PersistedFileContentHash>,
    desired_stage_version: Option<PersistedFileStageVersion>,
    outcome: Option<SemanticFileOutcome>,
}

#[derive(Debug, Clone)]
struct SemanticFileOutcome {
    content_hash: PersistedFileContentHash,
    stage_version: PersistedFileStageVersion,
    input_fingerprint: Option<SemanticGraphStageInputFingerprint>,
    status: SemanticFileOutcomeStatus,
    limitations: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SemanticFileOutcomeStatus {
    Complete,
    Limited,
    Failed,
}

#[derive(Debug, Clone)]
enum PersistedPendingUpdateTarget {
    CanonicalPath(String),
    Unproven,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum GraphFileState {
    Indexed,
    Excluded,
    Pending,
    Limited,
    Failed,
    Stale,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphFileCoverage {
    path: String,
    state: GraphFileState,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason_code: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    indexed_content_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_content_hash: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    diagnostics: Vec<Value>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    limitations: Vec<String>,
    gradle_projects: Vec<String>,
    source_sets: Vec<String>,
    #[serde(skip)]
    ownership: RepositoryFileOwnership,
}

#[derive(Debug, Clone, Copy, Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct StateCounts {
    total: usize,
    indexed: usize,
    excluded: usize,
    pending: usize,
    limited: usize,
    failed: usize,
    stale: usize,
}

impl StateCounts {
    fn add(&mut self, state: GraphFileState) {
        self.total += 1;
        match state {
            GraphFileState::Indexed => self.indexed += 1,
            GraphFileState::Excluded => self.excluded += 1,
            GraphFileState::Pending => self.pending += 1,
            GraphFileState::Limited => self.limited += 1,
            GraphFileState::Failed => self.failed += 1,
            GraphFileState::Stale => self.stale += 1,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CoverageGroup {
    name: String,
    #[serde(flatten)]
    counts: StateCounts,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct IndexModuleCoverage {
    name: String,
    status: &'static str,
    indexed_file_count: u64,
    total_file_count: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CoverageSummary {
    complete: bool,
    eligible_for_complete_negative: bool,
    #[serde(flatten)]
    counts: StateCounts,
    accounted: usize,
    eligibility_proven: bool,
    pending_update_count: u64,
    modules: Vec<CoverageGroup>,
    compilations: Vec<CoverageGroup>,
    index_modules: Vec<IndexModuleCoverage>,
    limitations: Vec<String>,
}

#[derive(Debug, Clone)]
struct CoverageSnapshot {
    generation: u64,
    scope: RepositoryScope,
    resolved_scope: ResolvedRepositoryScopeProof,
    coverage: CoverageSummary,
    files: Vec<GraphFileCoverage>,
    semantic_scope: BTreeSet<String>,
    orphaned_semantic_paths: Vec<String>,
}

#[derive(Debug, Clone)]
pub(crate) struct SemanticGraphRefreshPlan {
    pub file_paths: Vec<String>,
    pub removed_file_paths: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SemanticGraphEvidenceCoverage {
    total: usize,
    indexed: usize,
    excluded: usize,
    pending: usize,
    limited: usize,
    failed: usize,
    stale: usize,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    limitations: Vec<String>,
}

#[derive(Debug, Clone)]
pub(crate) enum SemanticGraphReadAdmission {
    Current {
        generation: u64,
        coverage: SemanticGraphEvidenceCoverage,
    },
    Qualified {
        generation: u64,
        coverage: SemanticGraphEvidenceCoverage,
    },
    Rejected {
        generation: u64,
        coverage: SemanticGraphEvidenceCoverage,
    },
}

impl SemanticGraphReadAdmission {
    pub fn generation(&self) -> u64 {
        match self {
            Self::Current { generation, .. }
            | Self::Qualified { generation, .. }
            | Self::Rejected { generation, .. } => *generation,
        }
    }

    pub fn qualification(&self) -> Option<&'static str> {
        match self {
            Self::Current { .. } => Some("CURRENT"),
            Self::Qualified { .. } => Some("QUALIFIED"),
            Self::Rejected { .. } => None,
        }
    }

    pub fn coverage(&self) -> &SemanticGraphEvidenceCoverage {
        match self {
            Self::Current { coverage, .. }
            | Self::Qualified { coverage, .. }
            | Self::Rejected { coverage, .. } => coverage,
        }
    }

    pub fn is_rejected(&self) -> bool {
        matches!(self, Self::Rejected { .. })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ResolvedRepositoryScopeProof {
    project: Option<String>,
    source_set: Option<String>,
}

#[derive(Debug, Clone)]
struct ResolvedRepositoryScope {
    request: RepositoryScope,
    project: Option<BuildQualifiedGradleProjectIdentity>,
    source_set: Option<BuildQualifiedGradleSourceSetIdentity>,
}

#[derive(Debug, Clone)]
struct RepositoryExecutionScope {
    indexed_files: BTreeMap<String, RepositoryFileOwnership>,
}

#[derive(Debug, Clone)]
struct RepositoryFileOwnership {
    gradle_projects: BTreeSet<BuildQualifiedGradleProjectIdentity>,
    source_sets: BTreeSet<BuildQualifiedGradleSourceSetIdentity>,
}

struct RepositoryNodeCache<'a> {
    execution_scope: &'a RepositoryExecutionScope,
    nodes: BTreeMap<i64, RepositoryNode>,
}

impl RepositoryExecutionScope {
    fn from_coverage(snapshot: &CoverageSnapshot) -> Self {
        Self {
            indexed_files: snapshot
                .files
                .iter()
                .filter(|file| {
                    matches!(
                        file.state,
                        GraphFileState::Indexed | GraphFileState::Limited
                    )
                })
                .map(|file| (file.path.clone(), file.ownership.clone()))
                .collect(),
        }
    }

    fn admits_path(&self, path: &str) -> bool {
        self.indexed_files.contains_key(path)
    }

    fn admit_node(&self, mut node: RepositoryNode) -> Option<RepositoryNode> {
        let ownership = self.indexed_files.get(&node.path)?;
        node.gradle_projects = ownership
            .gradle_projects
            .iter()
            .map(canonical_gradle_project)
            .collect();
        node.source_sets = ownership
            .source_sets
            .iter()
            .map(canonical_gradle_source_set)
            .collect();
        Some(node)
    }

    fn admit_nodes(&self, nodes: Vec<RepositoryNode>) -> Vec<RepositoryNode> {
        nodes
            .into_iter()
            .filter_map(|node| self.admit_node(node))
            .collect()
    }

    fn ownership(&self, node: &RepositoryNode) -> Option<&RepositoryFileOwnership> {
        self.indexed_files.get(&node.path)
    }
}

struct RepositoryGraphExecution<'a> {
    request_scope: &'a RepositoryScope,
    admitted: &'a RepositoryExecutionScope,
    limits: &'a RepositoryLimits,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphCoverageResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    generation: u64,
    inventory_generation: u64,
    graph_generation: u64,
    scope: RepositoryScope,
    applied_filters: RepositoryScope,
    coverage: CoverageSummary,
    files: Vec<GraphFileCoverage>,
    ordering: &'static str,
    truncated: bool,
    continuation: Option<GraphCoverageContinuation>,
    schema_version: u32,
}
