#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRepositoryCardinalityCompleteness {
    Complete,
    LowerBound,
    Unproven,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryRecordCardinality {
    returned: usize,
    completeness: AgentRepositoryCardinalityCompleteness,
}

impl AgentRepositoryRecordCardinality {
    fn new(returned: usize, completeness: AgentRepositoryCardinalityCompleteness) -> Self {
        Self {
            returned,
            completeness,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryCardinality {
    #[serde(skip_serializing_if = "Option::is_none")]
    identities: Option<AgentRepositoryRecordCardinality>,
    #[serde(skip_serializing_if = "Option::is_none")]
    relationships: Option<AgentRepositoryRecordCardinality>,
    #[serde(skip_serializing_if = "Option::is_none")]
    paths: Option<AgentRepositoryRecordCardinality>,
    #[serde(skip_serializing_if = "Option::is_none")]
    findings: Option<AgentRepositoryRecordCardinality>,
    #[serde(skip_serializing_if = "Option::is_none")]
    context_relations: Option<AgentRepositoryRecordCardinality>,
    #[serde(skip_serializing_if = "Option::is_none")]
    context_findings: Option<AgentRepositoryRecordCardinality>,
    identity_collisions: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryContextProjection {
    unresolved_references: Vec<String>,
    relations: Vec<AgentRepositoryContextRelation>,
    findings: Vec<AgentRepositoryContextFinding>,
    ambiguous_references: Vec<AgentRepositoryContextAmbiguity>,
}

impl AgentRepositoryContextProjection {
    fn is_empty(&self) -> bool {
        self.unresolved_references.is_empty()
            && self.relations.is_empty()
            && self.findings.is_empty()
            && self.ambiguous_references.is_empty()
    }
}

fn bool_is_false(value: &bool) -> bool {
    !value
}
