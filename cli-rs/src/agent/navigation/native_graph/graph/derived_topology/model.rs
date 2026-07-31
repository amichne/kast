const DERIVED_TOPOLOGY_SCHEMA_VERSION: u32 = 1;
const DERIVED_TOPOLOGY_ALGORITHM_VERSION: u32 = 1;
const DERIVED_TOPOLOGY_RESOLUTION: f64 = 1.0;

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedEvidenceClass {
    StatisticalDerivation,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedSourceLane {
    ReferenceDerived,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ReferenceQualification {
    Current,
    Qualified,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedAlgorithmName {
    KastDeterministicPartitionV1,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedWeighting {
    Log1pOccurrenceCount,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedRelationshipKind {
    Call,
    TypeRef,
    Inheritance,
    Override,
    Import,
    Annotation,
    Unknown,
}

impl TryFrom<&str> for DerivedRelationshipKind {
    type Error = CliError;

    fn try_from(value: &str) -> Result<Self> {
        match value {
            "CALL" => Ok(Self::Call),
            "TYPE_REF" => Ok(Self::TypeRef),
            "INHERITANCE" => Ok(Self::Inheritance),
            "OVERRIDE" => Ok(Self::Override),
            "IMPORT" => Ok(Self::Import),
            "ANNOTATION" => Ok(Self::Annotation),
            "UNKNOWN" => Ok(Self::Unknown),
            value => Err(CliError::new(
                "DERIVED_TOPOLOGY_EDGE_KIND_INVALID",
                format!("The reference index contains unsupported edge kind `{value}`."),
            )),
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedRelationshipClass {
    Runtime,
    TypeDependency,
    Hierarchy,
    Metadata,
    Unknown,
}

impl From<DerivedRelationshipKind> for DerivedRelationshipClass {
    fn from(kind: DerivedRelationshipKind) -> Self {
        match kind {
            DerivedRelationshipKind::Call => Self::Runtime,
            DerivedRelationshipKind::TypeRef | DerivedRelationshipKind::Import => {
                Self::TypeDependency
            }
            DerivedRelationshipKind::Inheritance | DerivedRelationshipKind::Override => {
                Self::Hierarchy
            }
            DerivedRelationshipKind::Annotation => Self::Metadata,
            DerivedRelationshipKind::Unknown => Self::Unknown,
        }
    }
}

#[derive(PartialEq, Eq)]
struct DerivedTopologyDataVersions {
    main: i64,
    repository_base: Option<i64>,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DerivedStructuralRole {
    Connector,
    Internal,
    Isolated,
    Sink,
    Source,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CommunityLineageStatus {
    Continued,
    Merged,
    New,
    Split,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReferenceCoverage {
    total: usize,
    complete: usize,
    limited: usize,
    pending: usize,
    failed: usize,
    stale: usize,
    external_boundaries: usize,
    pending_updates: usize,
    external_targets: usize,
    unattributed_source_edges: usize,
    #[serde(default)]
    invalidated_target_edges: usize,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    limitations: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologySource {
    lane: DerivedSourceLane,
    qualification: ReferenceQualification,
    generation: u64,
    input_digest: String,
    coverage: ReferenceCoverage,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyAlgorithm {
    name: DerivedAlgorithmName,
    version: u32,
    resolution: f64,
    weighting: DerivedWeighting,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReferenceNodeInput {
    key: String,
    name: String,
    kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    module: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_set: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReferenceEdgeInput {
    source: String,
    target: String,
    kind: DerivedRelationshipKind,
    relationship_class: DerivedRelationshipClass,
    occurrence_count: usize,
    normalized_weight: f64,
}

struct ReferenceEdgeRead {
    edges: Vec<ReferenceEdgeInput>,
    unattributed_source_edges: usize,
    invalidated_target_edges: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyNode {
    #[serde(flatten)]
    input: ReferenceNodeInput,
    community: usize,
    roles: Vec<DerivedStructuralRole>,
    degree: usize,
    weighted_degree: f64,
    retrieval_terms: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyCommunity {
    id: usize,
    label: String,
    label_terms: Vec<String>,
    members: Vec<String>,
    member_count: usize,
    internal_edge_count: usize,
    external_edge_count: usize,
    internal_weight: f64,
    external_weight: f64,
    cohesion: f64,
    conductance: f64,
    representative_symbols: Vec<String>,
    relationship_kinds: BTreeMap<DerivedRelationshipKind, usize>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct CommunityLineage {
    community: usize,
    status: CommunityLineageStatus,
    previous_communities: Vec<usize>,
    overlap_count: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyLineage {
    previous_generation: u64,
    previous_input_digest: String,
    communities: Vec<CommunityLineage>,
    removed_communities: Vec<usize>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyEdgeIdentity {
    source: String,
    target: String,
    kind: DerivedRelationshipKind,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyChanges {
    added_nodes: Vec<String>,
    removed_nodes: Vec<String>,
    added_edges: Vec<DerivedTopologyEdgeIdentity>,
    removed_edges: Vec<DerivedTopologyEdgeIdentity>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedTopologyArtifact {
    r#type: String,
    schema_version: u32,
    evidence_class: DerivedEvidenceClass,
    source: DerivedTopologySource,
    algorithm: DerivedTopologyAlgorithm,
    nodes: Vec<DerivedTopologyNode>,
    edges: Vec<ReferenceEdgeInput>,
    communities: Vec<DerivedTopologyCommunity>,
    #[serde(skip_serializing_if = "Option::is_none")]
    lineage: Option<DerivedTopologyLineage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    changes: Option<DerivedTopologyChanges>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DerivedTopologyReceipt {
    artifact: String,
    generation: u64,
    node_count: usize,
    edge_count: usize,
    community_count: usize,
    sha256: String,
}

struct ReferenceTopologySnapshot {
    generation: u64,
    qualification: ReferenceQualification,
    coverage: ReferenceCoverage,
    nodes: Vec<ReferenceNodeInput>,
    edges: Vec<ReferenceEdgeInput>,
    input_digest: String,
}
