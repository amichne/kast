struct AgentRepositoryProjection {
    question: String,
    status: AgentRepositoryStatus,
    intent: AgentRepositoryIntent,
    query_syntax: AgentRepositoryQuerySyntaxEvidence,
    workspace_root: String,
    generation: u64,
    coverage: AgentRepositoryCoverage,
    bounds: AgentRepositoryBounds,
    cardinality: AgentRepositoryCardinality,
    identities: Vec<AgentRepositoryIdentity>,
    relationships: Vec<AgentRepositoryRelationship>,
    paths: Vec<AgentRepositoryPath>,
    findings: Vec<AgentRepositoryFinding>,
    context: AgentRepositoryContextProjection,
    truncated: bool,
    continuation: Option<String>,
    continuations: Vec<String>,
    qualification: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositorySummary {
    question: String,
    status: AgentRepositoryStatus,
    intent: AgentRepositoryIntent,
    query_syntax: AgentRepositoryQuerySyntaxEvidence,
    workspace_root: String,
    generation: u64,
    bounds: AgentRepositoryBounds,
    cardinality: AgentRepositoryCardinality,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    qualification: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    question: String,
    status: AgentRepositoryStatus,
    intent: AgentRepositoryIntent,
    query_syntax: AgentRepositoryQuerySyntaxEvidence,
    workspace_root: String,
    generation: u64,
    coverage: AgentRepositoryCoverage,
    bounds: AgentRepositoryBounds,
    cardinality: AgentRepositoryCardinality,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    identities: Vec<AgentRepositoryIdentity>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    relationships: Vec<AgentRepositoryRelationship>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    paths: Vec<AgentRepositoryPath>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    findings: Vec<AgentRepositoryCompactFinding>,
    #[serde(skip_serializing_if = "Option::is_none")]
    finding_evidence: Option<AgentRepositoryCompactFindingEvidence>,
    #[serde(skip_serializing_if = "AgentRepositoryContextProjection::is_empty")]
    context: AgentRepositoryContextProjection,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    continuation: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    continuations: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    qualification: Option<String>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "status",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentRepositoryCompactFindingEvidence {
    OmittedInCompactView { help: &'static str },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositorySelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    summary: Option<AgentRepositorySummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    coverage: Option<AgentRepositoryCoverage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    identities: Option<Vec<AgentRepositoryIdentity>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    relationships: Option<Vec<AgentRepositoryRelationship>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    paths: Option<Vec<AgentRepositoryPath>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    findings: Option<Vec<AgentRepositoryFinding>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    context: Option<AgentRepositoryContextProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    continuation: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    continuations: Option<Vec<String>>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    status: AgentRepositoryStatus,
    intent: AgentRepositoryIntent,
    query_syntax: AgentRepositoryQuerySyntaxEvidence,
    generation: u64,
    coverage: AgentRepositoryCoverage,
    bounds: AgentRepositoryBounds,
    cardinality: AgentRepositoryCardinality,
    truncated: bool,
    schema_version: u32,
}
