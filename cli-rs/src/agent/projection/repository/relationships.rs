#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryRelationship {
    source_key: String,
    target_key: String,
    kind: crate::cli::AgentRepositoryRelation,
    direction: crate::cli::AgentRepositoryDirection,
    context: String,
    occurrence_count: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    first_occurrence: Option<AgentRepositoryOccurrence>,
    evidence_class: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    derivation: Option<Value>,
    #[serde(skip_serializing_if = "bool_is_false")]
    evidence_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    evidence_continuation: Option<String>,
}

impl From<AgentRepositoryRelationshipInput> for AgentRepositoryRelationship {
    fn from(relationship: AgentRepositoryRelationshipInput) -> Self {
        Self {
            source_key: relationship.source_key,
            target_key: relationship.target_key,
            kind: relationship.kind,
            direction: relationship.direction,
            context: relationship.context,
            occurrence_count: relationship.occurrence_count,
            first_occurrence: relationship.occurrences.into_iter().next(),
            evidence_class: relationship.evidence_class,
            derivation: relationship.derivation,
            evidence_truncated: relationship.evidence_truncated,
            evidence_continuation: relationship.evidence_continuation,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryPath {
    direction: crate::cli::AgentRepositoryDirection,
    relation_kinds: Vec<crate::cli::AgentRepositoryRelation>,
    canonical_keys: Vec<String>,
}

impl From<AgentRepositoryPathInput> for AgentRepositoryPath {
    fn from(path: AgentRepositoryPathInput) -> Self {
        Self {
            direction: path.direction,
            relation_kinds: path.relation_kinds,
            canonical_keys: path
                .nodes
                .into_iter()
                .map(|node| node.canonical_key)
                .collect(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryFinding {
    rank: usize,
    #[serde(rename = "type")]
    finding_type: String,
    name: String,
    summary: String,
    projection: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    direction: Option<crate::cli::AgentRepositoryDirection>,
    metric: String,
    trigger: Value,
    graph_generation: u64,
    representative_symbols: Vec<AgentRepositoryIdentity>,
    supporting_subgraph: AgentRepositorySupportingSubgraph,
    relation_composition: std::collections::BTreeMap<crate::cli::AgentRepositoryRelation, usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cohesion: Option<f64>,
    evidence_class: String,
    derivation: Value,
}

impl From<AgentRepositoryFindingInput> for AgentRepositoryFinding {
    fn from(finding: AgentRepositoryFindingInput) -> Self {
        Self {
            rank: finding.rank,
            finding_type: finding.finding_type,
            name: finding.name,
            summary: finding.summary,
            projection: finding.projection,
            direction: finding.direction,
            metric: finding.metric,
            trigger: finding.trigger,
            graph_generation: finding.graph_generation,
            representative_symbols: finding
                .representative_symbols
                .into_iter()
                .map(AgentRepositoryIdentity::from)
                .collect(),
            supporting_subgraph: AgentRepositorySupportingSubgraph::from(
                finding.supporting_subgraph,
            ),
            relation_composition: finding.relation_composition,
            cohesion: finding.cohesion,
            evidence_class: finding.evidence_class,
            derivation: finding.derivation,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositorySupportingSubgraph {
    nodes: Vec<AgentRepositoryIdentity>,
    edges: Vec<AgentRepositoryRelationship>,
    truncated: bool,
}

impl From<AgentRepositorySupportingSubgraphInput> for AgentRepositorySupportingSubgraph {
    fn from(subgraph: AgentRepositorySupportingSubgraphInput) -> Self {
        Self {
            nodes: subgraph
                .nodes
                .into_iter()
                .map(AgentRepositoryIdentity::from)
                .collect(),
            edges: subgraph
                .edges
                .into_iter()
                .map(AgentRepositoryRelationship::from)
                .collect(),
            truncated: subgraph.truncated,
        }
    }
}
