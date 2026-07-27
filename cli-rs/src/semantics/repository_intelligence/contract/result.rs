#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryNode {
    #[serde(skip)]
    database_id: i64,
    canonical_key: String,
    kind: String,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    fq_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    signature: Option<String>,
    visibility: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    modality: Option<String>,
    origin: String,
    path: String,
    gradle_projects: Vec<String>,
    source_sets: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    owner_name: Option<String>,
    parameter_types: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    receiver_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    return_type: Option<String>,
    declaration_range: RepositorySourceRange,
    flags: RepositorySymbolFlags,
    annotations: Vec<String>,
    evidence_class: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryCandidate {
    rank: usize,
    match_score: usize,
    match_reasons: Vec<RepositoryMatchReason>,
    #[serde(flatten)]
    node: RepositoryNode,
}

enum RepositoryResolutionOutcome {
    Empty,
    Ambiguous,
    Answered(Box<RepositoryNode>),
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryMatchReason {
    field: &'static str,
    terms: Vec<String>,
    score: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositorySymbolFlags {
    is_expect: bool,
    is_actual: bool,
    is_override: bool,
    is_sealed: bool,
    is_delegated: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositorySourceRange {
    start_offset: i64,
    end_offset: i64,
    line: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryOccurrence {
    id: i64,
    path: String,
    start_offset: i64,
    end_offset: i64,
    line: i64,
}

#[derive(Debug, Clone)]
struct RepositoryEdgeOccurrence {
    source_id: i64,
    target_id: i64,
    kind: RepositoryRelationKind,
    context: String,
    occurrence: RepositoryOccurrence,
    lifted_source: Option<i64>,
    source_local_key: Option<String>,
}

struct RepositoryArchitectureGraph {
    nodes: Vec<RepositoryNode>,
    positions: BTreeMap<i64, usize>,
    occurrences: Vec<RepositoryEdgeOccurrence>,
    native: NativeGraph,
    execution_scope: RepositoryExecutionScope,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextRelation {
    source_path: String,
    source_kind: RepositoryContextSource,
    target_key: String,
    target_name: String,
    kind: RepositoryContextRelationKind,
    direction: RepositoryDirection,
    source_location: RepositoryContextLocation,
    evidence_class: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    derivation: Option<RepositoryContextDerivation>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextLocation {
    line: usize,
    start_offset: usize,
    end_offset: usize,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextDerivation {
    rule: &'static str,
    facts: Value,
}

struct RepositoryContextCandidate {
    score: usize,
    relation: RepositoryContextRelation,
}

struct ContainedRepositoryContextPath {
    relative_path: String,
    canonical_path: PathBuf,
    metadata: std::fs::Metadata,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextAmbiguity {
    reference: String,
    candidates: Vec<RepositoryNode>,
    truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct RepositoryEdgeIdentity {
    source_id: i64,
    target_id: i64,
    kind: RepositoryRelationKind,
    context: String,
    derived: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryEdge {
    source_key: String,
    source_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_owner_name: Option<String>,
    target_key: String,
    target_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    target_owner_name: Option<String>,
    kind: RepositoryRelationKind,
    direction: RepositoryDirection,
    context: String,
    occurrence_count: usize,
    occurrences: Vec<RepositoryOccurrence>,
    evidence_class: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    derivation: Option<RepositoryDerivation>,
    evidence_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    evidence_continuation: Option<RepositoryEvidenceContinuation>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
struct RepositoryEvidenceContinuation(String);

#[derive(Debug, Clone, Deserialize, Serialize)]
struct RepositoryTraversalContinuation(String);

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryEvidenceContinuationClaims {
    schema_version: u32,
    workspace_root: String,
    graph_generation: u64,
    coverage_sha256: String,
    query_sha256: String,
    resume: RepositoryEvidenceResume,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryEvidenceResume {
    source_key: String,
    target_key: String,
    kind: RepositoryRelationKind,
    context: String,
    derived: bool,
    after_occurrence_id: i64,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct RepositoryTraversalContinuationClaims {
    #[serde(rename = "v")]
    schema_version: u32,
    #[serde(rename = "g")]
    graph_generation: u64,
    #[serde(rename = "c")]
    coverage_sha256: String,
    #[serde(rename = "q")]
    query_sha256: String,
    #[serde(rename = "s")]
    canonical_start_key: String,
    #[serde(rename = "x")]
    resume: RepositoryTraversalResume,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct RepositoryTraversalResume {
    #[serde(rename = "d")]
    depth: usize,
    #[serde(rename = "o")]
    edge_offset: usize,
}

struct RepositoryTraversalContinuationState {
    canonical_start_key: String,
    resume: RepositoryTraversalResume,
}

struct RepositoryContinuationContext {
    workspace_root: String,
    graph_generation: u64,
    query_sha256: String,
    traversal_query_sha256: String,
    coverage_sha256: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryDerivation {
    rule: &'static str,
    source_local_key: String,
    supporting_relations: [&'static str; 2],
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryPath {
    direction: RepositoryDirection,
    relation_kinds: Vec<RepositoryRelationKind>,
    nodes: Vec<RepositoryNode>,
}

enum RepositoryPathTargetResolution {
    Missing,
    Unique(Box<RepositoryNode>),
    Ambiguous {
        candidates: Vec<RepositoryNode>,
        truncated: bool,
    },
}
