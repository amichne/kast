#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryNodeInput {
    canonical_key: String,
    kind: String,
    name: String,
    #[serde(default)]
    fq_name: Option<String>,
    path: String,
    #[serde(default)]
    gradle_projects: Vec<String>,
    #[serde(default)]
    source_sets: Vec<String>,
    declaration_range: AgentRepositorySourceRange,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryCandidateInput {
    rank: usize,
    match_score: usize,
    #[serde(flatten)]
    node: AgentRepositoryNodeInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRepositoryContextAmbiguityInput {
    reference: String,
    candidates: Vec<AgentRepositoryNodeInput>,
    truncated: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositorySourceRange {
    start_offset: i64,
    end_offset: i64,
    line: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryRelationshipInput {
    source_key: String,
    #[serde(rename = "sourceName")]
    _source_name: String,
    target_key: String,
    #[serde(rename = "targetName")]
    _target_name: String,
    kind: crate::cli::AgentRepositoryRelation,
    direction: crate::cli::AgentRepositoryDirection,
    context: String,
    occurrence_count: usize,
    #[serde(default)]
    occurrences: Vec<AgentRepositoryOccurrence>,
    evidence_class: String,
    #[serde(default)]
    derivation: Option<Value>,
    evidence_truncated: bool,
    #[serde(default)]
    evidence_continuation: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryPathInput {
    direction: crate::cli::AgentRepositoryDirection,
    relation_kinds: Vec<crate::cli::AgentRepositoryRelation>,
    nodes: Vec<AgentRepositoryNodeInput>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryOccurrence {
    path: String,
    start_offset: i64,
    end_offset: i64,
    line: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryFindingInput {
    rank: usize,
    #[serde(rename = "type")]
    finding_type: String,
    name: String,
    summary: String,
    projection: String,
    #[serde(default)]
    direction: Option<crate::cli::AgentRepositoryDirection>,
    metric: String,
    trigger: Value,
    graph_generation: u64,
    representative_symbols: Vec<AgentRepositoryNodeInput>,
    supporting_subgraph: AgentRepositorySupportingSubgraphInput,
    relation_composition: std::collections::BTreeMap<crate::cli::AgentRepositoryRelation, usize>,
    #[serde(default)]
    cohesion: Option<f64>,
    evidence_class: String,
    derivation: Value,
    #[serde(rename = "relationTypes")]
    _relation_types: Vec<crate::cli::AgentRepositoryRelation>,
    #[serde(rename = "scope")]
    _scope: Value,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositorySupportingSubgraphInput {
    nodes: Vec<AgentRepositoryNodeInput>,
    edges: Vec<AgentRepositoryRelationshipInput>,
    truncated: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryContextRelation {
    source_path: String,
    source_kind: crate::cli::AgentRepositorySource,
    target_key: String,
    target_name: String,
    kind: AgentRepositoryContextRelationKind,
    direction: crate::cli::AgentRepositoryDirection,
    source_location: AgentRepositoryContextLocation,
    evidence_class: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    derivation: Option<Value>,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRepositoryContextRelationKind {
    MentionsSymbol,
    Documents,
    ConfiguresModule,
    DeclaresDependency,
    Generates,
    ConsumesSchema,
    ImplementsProtocol,
    Supersedes,
    ConflictsWith,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryContextLocation {
    line: usize,
    start_offset: usize,
    end_offset: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentRepositoryContextFinding {
    StaleDocumentReference {
        source_path: String,
        reference: String,
        trigger: String,
        source_location: AgentRepositoryContextLocation,
        evidence_class: String,
    },
    PublicApiDocumentationGap {
        target_key: String,
        target_name: String,
        trigger: String,
        evidence_class: String,
    },
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryWorkspaceIdentity {
    canonical_root: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryCoverage {
    complete: bool,
    eligible_for_complete_negative: bool,
    total: usize,
    indexed: usize,
    excluded: usize,
    pending: usize,
    limited: usize,
    failed: usize,
    stale: usize,
    accounted: usize,
    eligibility_proven: bool,
    pending_update_count: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryBounds {
    depth: usize,
    results: usize,
    evidence: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryIdentity {
    #[serde(skip_serializing_if = "Option::is_none")]
    rank: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    match_score: Option<usize>,
    canonical_key: String,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    fq_name: Option<String>,
    kind: String,
    path: String,
    line: i64,
    gradle_projects: Vec<String>,
    source_sets: Vec<String>,
}

impl From<AgentRepositoryNodeInput> for AgentRepositoryIdentity {
    fn from(node: AgentRepositoryNodeInput) -> Self {
        Self {
            rank: None,
            match_score: None,
            canonical_key: node.canonical_key,
            name: node.name,
            fq_name: node.fq_name,
            kind: node.kind,
            path: node.path,
            line: node.declaration_range.line,
            gradle_projects: node.gradle_projects,
            source_sets: node.source_sets,
        }
    }
}

impl From<AgentRepositoryCandidateInput> for AgentRepositoryIdentity {
    fn from(candidate: AgentRepositoryCandidateInput) -> Self {
        let mut identity = Self::from(candidate.node);
        identity.rank = Some(candidate.rank);
        identity.match_score = Some(candidate.match_score);
        identity
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryContextAmbiguity {
    reference: String,
    candidates: Vec<AgentRepositoryIdentity>,
    truncated: bool,
}

impl From<AgentRepositoryContextAmbiguityInput> for AgentRepositoryContextAmbiguity {
    fn from(ambiguity: AgentRepositoryContextAmbiguityInput) -> Self {
        Self {
            reference: ambiguity.reference,
            candidates: ambiguity
                .candidates
                .into_iter()
                .map(AgentRepositoryIdentity::from)
                .collect(),
            truncated: ambiguity.truncated,
        }
    }
}
