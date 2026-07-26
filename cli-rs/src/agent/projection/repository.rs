#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRepositoryStatus {
    Answered,
    Ambiguous,
    Empty,
    QualifiedEmpty,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRepositoryProjectionInput {
    #[serde(rename = "type")]
    result_type: String,
    canonical_result_model: bool,
    status: AgentRepositoryStatus,
    question: String,
    intent: AgentRepositoryIntent,
    #[serde(rename = "queryPlan")]
    _query_plan: Value,
    workspace_identity: AgentRepositoryWorkspaceIdentity,
    generation: u64,
    inventory_generation: u64,
    graph_generation: u64,
    #[serde(rename = "scope")]
    _scope: Value,
    coverage: AgentRepositoryCoverage,
    #[serde(rename = "appliedFilters")]
    _applied_filters: Value,
    bounds: AgentRepositoryBounds,
    #[serde(rename = "ordering")]
    _ordering: String,
    truncated: bool,
    #[serde(default)]
    continuation: Option<String>,
    #[serde(default)]
    qualification: Option<String>,
    schema_version: u32,
    #[serde(default)]
    nodes: Vec<AgentRepositoryNodeInput>,
    #[serde(default)]
    candidates: Vec<AgentRepositoryCandidateInput>,
    identity_collisions: usize,
    #[serde(default)]
    edges: Vec<AgentRepositoryRelationshipInput>,
    #[serde(default)]
    paths: Vec<AgentRepositoryPathInput>,
    #[serde(default)]
    findings: Vec<AgentRepositoryFindingInput>,
    #[serde(default)]
    context_relations: Vec<AgentRepositoryContextRelation>,
    #[serde(default, rename = "contextMetrics")]
    _context_metrics: Option<Value>,
    #[serde(default, rename = "evidenceClasses")]
    _evidence_classes: Vec<String>,
    #[serde(default, rename = "relationVocabulary")]
    _relation_vocabulary: Vec<Value>,
    #[serde(default, rename = "unresolvedReferences")]
    unresolved_references: Vec<String>,
    #[serde(default, rename = "ambiguousReferences")]
    ambiguous_references: Vec<AgentRepositoryContextAmbiguityInput>,
    #[serde(default)]
    context_findings: Vec<AgentRepositoryContextFinding>,
    #[serde(default)]
    selected_identity: Option<String>,
}

impl AgentRepositoryProjectionInput {
    fn validated(self) -> std::result::Result<Self, String> {
        if self.result_type != "KAST_REPOSITORY_QUERY_RESULT" || !self.canonical_result_model {
            return Err("expected one canonical KAST_REPOSITORY_QUERY_RESULT".to_string());
        }
        if self.schema_version != SCHEMA_VERSION {
            return Err(format!(
                "expected schema version {SCHEMA_VERSION}, found {}",
                self.schema_version
            ));
        }
        if self.generation != self.inventory_generation || self.generation != self.graph_generation
        {
            return Err("repository generations do not identify one snapshot".to_string());
        }
        let classified = self.coverage.indexed
            + self.coverage.excluded
            + self.coverage.failed
            + self.coverage.stale;
        if self.coverage.accounted != self.coverage.total || classified != self.coverage.total {
            return Err("repository coverage counts do not account for one scope".to_string());
        }
        match self.status {
            AgentRepositoryStatus::Empty
                if !self.coverage.complete
                    || !self.coverage.eligible_for_complete_negative
                    || !self.coverage.eligibility_proven
                    || self.qualification.is_some() =>
            {
                return Err(
                    "EMPTY repository status requires complete coverage with proven complete-negative eligibility and no qualification"
                        .to_string(),
                );
            }
            AgentRepositoryStatus::QualifiedEmpty
                if self.coverage.complete
                    || self.coverage.eligible_for_complete_negative
                    || self
                        .qualification
                        .as_deref()
                        .is_none_or(|qualification| qualification.trim().is_empty()) =>
            {
                return Err(
                    "QUALIFIED_EMPTY repository status requires incomplete ineligible coverage and a non-empty qualification"
                        .to_string(),
                );
            }
            AgentRepositoryStatus::Ambiguous if self.selected_identity.is_some() => {
                return Err("AMBIGUOUS repository status cannot select one identity".to_string());
            }
            AgentRepositoryStatus::Answered
            | AgentRepositoryStatus::Ambiguous
            | AgentRepositoryStatus::Empty
            | AgentRepositoryStatus::QualifiedEmpty => {}
        }
        let has_answer_evidence = match self.intent {
            AgentRepositoryIntent::Resolve => !self.nodes.is_empty() || !self.candidates.is_empty(),
            AgentRepositoryIntent::Path => !self.edges.is_empty() || !self.paths.is_empty(),
            AgentRepositoryIntent::IncomingImpact | AgentRepositoryIntent::OutgoingImpact => {
                !self.edges.is_empty()
            }
            AgentRepositoryIntent::Architecture => !self.findings.is_empty(),
            AgentRepositoryIntent::ContextRelationship => !self.context_relations.is_empty(),
        };
        let has_actionable_answer = match self.intent {
            AgentRepositoryIntent::Resolve => self.selected_identity.is_some(),
            AgentRepositoryIntent::Path
            | AgentRepositoryIntent::IncomingImpact
            | AgentRepositoryIntent::OutgoingImpact => !self.edges.is_empty(),
            AgentRepositoryIntent::Architecture => !self.findings.is_empty(),
            AgentRepositoryIntent::ContextRelationship => !self.context_relations.is_empty(),
        };
        let has_ambiguity_evidence = match self.intent {
            AgentRepositoryIntent::Resolve => !self.candidates.is_empty(),
            AgentRepositoryIntent::Path
            | AgentRepositoryIntent::IncomingImpact
            | AgentRepositoryIntent::OutgoingImpact => !self.nodes.is_empty(),
            AgentRepositoryIntent::Architecture => false,
            AgentRepositoryIntent::ContextRelationship => !self.ambiguous_references.is_empty(),
        };
        if matches!(
            self.status,
            AgentRepositoryStatus::Empty | AgentRepositoryStatus::QualifiedEmpty
        ) && has_answer_evidence
        {
            return Err(
                "definitive-empty repository status cannot contain affirmative answer evidence"
                    .to_string(),
            );
        }
        if matches!(self.status, AgentRepositoryStatus::Answered) && !has_actionable_answer {
            return Err(
                "ANSWERED repository status requires intent-specific answer evidence".to_string(),
            );
        }
        if matches!(self.status, AgentRepositoryStatus::Ambiguous) && !has_ambiguity_evidence {
            return Err(
                "AMBIGUOUS repository status requires intent-specific disambiguation evidence"
                    .to_string(),
            );
        }
        if self.continuation.as_ref().is_some_and(String::is_empty) {
            return Err("repository traversal continuation cannot be empty".to_string());
        }
        if !self.truncated
            && (self.continuation.is_some()
                || self
                    .edges
                    .iter()
                    .any(|edge| edge.evidence_continuation.is_some()))
        {
            return Err("untruncated repository result cannot expose continuations".to_string());
        }
        if let Some(selected) = self.selected_identity.as_deref()
            && !self.nodes.iter().any(|node| node.canonical_key == selected)
            && !self
                .candidates
                .iter()
                .any(|candidate| candidate.node.canonical_key == selected)
        {
            return Err(
                "selected repository identity is absent from returned evidence".to_string(),
            );
        }
        if self
            .paths
            .iter()
            .any(|path| path.nodes.is_empty() || path.relation_kinds.is_empty())
        {
            return Err(
                "repository path requires ordered identities and relation kinds".to_string(),
            );
        }
        if self
            .findings
            .iter()
            .any(|finding| finding.graph_generation != self.generation)
        {
            return Err(
                "repository architecture proof does not match the result generation".to_string(),
            );
        }
        if self.intent == AgentRepositoryIntent::ContextRelationship
            && matches!(self.status, AgentRepositoryStatus::Ambiguous)
                == self.ambiguous_references.is_empty()
        {
            return Err(
                "context ambiguity status must agree with exact candidate identities".to_string(),
            );
        }
        if self
            .unresolved_references
            .iter()
            .any(|reference| reference.is_empty())
            || self
                .unresolved_references
                .windows(2)
                .any(|pair| pair[0].as_str() >= pair[1].as_str())
        {
            return Err(
                "context unresolved references must be non-empty, sorted, and unique".to_string(),
            );
        }
        if self.ambiguous_references.iter().any(|ambiguity| {
            ambiguity.reference.is_empty()
                || ambiguity.candidates.is_empty()
                || ambiguity.candidates.len() > self.bounds.results
                || (!ambiguity.truncated && ambiguity.candidates.len() < 2)
        }) {
            return Err(
                "context ambiguity requires a reference and bounded collision candidates"
                    .to_string(),
            );
        }
        Ok(self)
    }

    fn into_projection(self) -> AgentRepositoryProjection {
        let relationship_count = self.edges.len();
        let path_count = self.paths.len();
        let finding_count = self.findings.len();
        let context_relation_count = self.context_relations.len();
        let context_finding_count = self.context_findings.len();
        let identities: Vec<AgentRepositoryIdentity> =
            if !matches!(self.intent, AgentRepositoryIntent::Resolve) {
                Vec::new()
            } else if self.candidates.is_empty() {
                self.nodes
                    .into_iter()
                    .map(AgentRepositoryIdentity::from)
                    .collect()
            } else {
                self.candidates
                    .into_iter()
                    .map(AgentRepositoryIdentity::from)
                    .collect()
            };
        let continuation = self.continuation;
        let mut continuations = self
            .edges
            .iter()
            .filter_map(|edge| edge.evidence_continuation.clone())
            .collect::<Vec<_>>();
        continuations.sort();
        continuations.dedup();
        let completeness = if self.truncated {
            AgentRepositoryCardinalityCompleteness::LowerBound
        } else if matches!(
            self.intent,
            AgentRepositoryIntent::Architecture | AgentRepositoryIntent::ContextRelationship
        ) {
            AgentRepositoryCardinalityCompleteness::Unproven
        } else {
            AgentRepositoryCardinalityCompleteness::Complete
        };
        let cardinality = AgentRepositoryCardinality {
            identities: (self.intent == AgentRepositoryIntent::Resolve)
                .then(|| AgentRepositoryRecordCardinality::new(identities.len(), completeness)),
            relationships: matches!(
                self.intent,
                AgentRepositoryIntent::Path
                    | AgentRepositoryIntent::IncomingImpact
                    | AgentRepositoryIntent::OutgoingImpact
            )
            .then(|| AgentRepositoryRecordCardinality::new(relationship_count, completeness)),
            paths: (self.intent == AgentRepositoryIntent::Path)
                .then(|| AgentRepositoryRecordCardinality::new(path_count, completeness)),
            findings: (self.intent == AgentRepositoryIntent::Architecture)
                .then(|| AgentRepositoryRecordCardinality::new(finding_count, completeness)),
            context_relations: (self.intent == AgentRepositoryIntent::ContextRelationship).then(
                || AgentRepositoryRecordCardinality::new(context_relation_count, completeness),
            ),
            context_findings: (self.intent == AgentRepositoryIntent::ContextRelationship).then(
                || AgentRepositoryRecordCardinality::new(context_finding_count, completeness),
            ),
            identity_collisions: self.identity_collisions,
        };
        AgentRepositoryProjection {
            question: self.question,
            status: self.status,
            intent: self.intent,
            workspace_root: self.workspace_identity.canonical_root,
            generation: self.generation,
            coverage: self.coverage,
            bounds: self.bounds,
            cardinality,
            identities,
            relationships: self
                .edges
                .into_iter()
                .map(AgentRepositoryRelationship::from)
                .collect(),
            paths: if self.intent == AgentRepositoryIntent::Path {
                self.paths
                    .into_iter()
                    .map(AgentRepositoryPath::from)
                    .collect()
            } else {
                Vec::new()
            },
            findings: self
                .findings
                .into_iter()
                .map(AgentRepositoryFinding::from)
                .collect(),
            context: AgentRepositoryContextProjection {
                unresolved_references: self.unresolved_references,
                relations: self.context_relations,
                findings: self.context_findings,
                ambiguous_references: self
                    .ambiguous_references
                    .into_iter()
                    .map(AgentRepositoryContextAmbiguity::from)
                    .collect(),
            },
            truncated: self.truncated,
            continuation,
            continuations,
            qualification: self.qualification,
        }
    }
}

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

struct AgentRepositoryProjection {
    question: String,
    status: AgentRepositoryStatus,
    intent: AgentRepositoryIntent,
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
    findings: Vec<AgentRepositoryFinding>,
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
    generation: u64,
    coverage: AgentRepositoryCoverage,
    bounds: AgentRepositoryBounds,
    cardinality: AgentRepositoryCardinality,
    truncated: bool,
    schema_version: u32,
}

fn project_repository_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentRepositoryField>,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let method = envelope.method;
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "repository query returned no result");
    };
    if view.detailed() {
        let validation = serde_json::from_value::<AgentRepositoryProjectionInput>(result.clone())
            .map_err(|error| error.to_string())
            .and_then(AgentRepositoryProjectionInput::validated);
        if let Err(error) = validation {
            return invalid_projection_envelope(
                method,
                format!("repository result violated the closed projection contract: {error}"),
            );
        }
        return result_envelope(method, result);
    }
    let input = match serde_json::from_value::<AgentRepositoryProjectionInput>(result)
        .map_err(|error| error.to_string())
        .and_then(AgentRepositoryProjectionInput::validated)
    {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("repository result violated the closed projection contract: {error}"),
            );
        }
    };
    let projection = input.into_projection();
    match view {
        AgentResultView::Compact => project_compact_repository(method, projection),
        AgentResultView::Fields(fields) => project_selected_repository(method, projection, &fields),
        AgentResultView::Count => project_repository_count(method, projection),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed repository views returned before projection")
        }
    }
}

fn project_compact_repository(
    method: String,
    projection: AgentRepositoryProjection,
) -> AgentEnvelope {
    result_envelope(
        method,
        AgentRepositoryCompactResult {
            result_type: "KAST_AGENT_REPOSITORY_RESULT",
            ok: true,
            question: projection.question,
            status: projection.status,
            intent: projection.intent,
            workspace_root: projection.workspace_root,
            generation: projection.generation,
            coverage: projection.coverage,
            bounds: projection.bounds,
            cardinality: projection.cardinality,
            identities: projection.identities,
            relationships: projection.relationships,
            paths: projection.paths,
            findings: projection.findings,
            context: projection.context,
            truncated: projection.truncated,
            continuation: projection.continuation,
            continuations: projection.continuations,
            qualification: projection.qualification,
            schema_version: SCHEMA_VERSION,
        },
    )
}

fn project_selected_repository(
    method: String,
    projection: AgentRepositoryProjection,
    fields: &[AgentRepositoryField],
) -> AgentEnvelope {
    let selected = |field| fields.contains(&field);
    let summary = selected(AgentRepositoryField::Summary).then_some(AgentRepositorySummary {
        question: projection.question,
        status: projection.status,
        intent: projection.intent,
        workspace_root: projection.workspace_root,
        generation: projection.generation,
        bounds: projection.bounds,
        cardinality: projection.cardinality,
        truncated: projection.truncated,
        qualification: projection.qualification,
    });
    result_envelope(
        method,
        AgentRepositorySelectedResult {
            result_type: "KAST_AGENT_REPOSITORY_SELECTION",
            ok: true,
            summary,
            coverage: selected(AgentRepositoryField::Coverage).then_some(projection.coverage),
            identities: selected(AgentRepositoryField::Identities).then_some(projection.identities),
            relationships: selected(AgentRepositoryField::Relationships)
                .then_some(projection.relationships),
            paths: selected(AgentRepositoryField::Paths).then_some(projection.paths),
            findings: selected(AgentRepositoryField::Findings).then_some(projection.findings),
            context: selected(AgentRepositoryField::Context).then_some(projection.context),
            continuation: selected(AgentRepositoryField::Continuation)
                .then_some(projection.continuation)
                .flatten(),
            continuations: selected(AgentRepositoryField::Continuation)
                .then_some(projection.continuations),
            schema_version: SCHEMA_VERSION,
        },
    )
}

fn project_repository_count(
    method: String,
    projection: AgentRepositoryProjection,
) -> AgentEnvelope {
    result_envelope(
        method,
        AgentRepositoryCountResult {
            result_type: "KAST_AGENT_REPOSITORY_COUNT",
            ok: true,
            status: projection.status,
            intent: projection.intent,
            generation: projection.generation,
            coverage: projection.coverage,
            bounds: projection.bounds,
            cardinality: projection.cardinality,
            truncated: projection.truncated,
            schema_version: SCHEMA_VERSION,
        },
    )
}
