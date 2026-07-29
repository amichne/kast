#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRepositoryStatus {
    Answered,
    Ambiguous,
    Empty,
    QualifiedEmpty,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRepositoryQuerySyntaxEvidence {
    NaturalLanguage,
    Regex,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentRepositoryDiscoveryEvidence {
    ExactKey,
    Lexical,
    LexicalWithPrecomputedLabels,
    Regex,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryQueryPlanInput {
    query_syntax: AgentRepositoryQuerySyntaxEvidence,
    discovery: AgentRepositoryDiscoveryEvidence,
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
    query_plan: AgentRepositoryQueryPlanInput,
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
        let valid_discovery = matches!(
            (
            self.query_plan.query_syntax,
            self.query_plan.discovery,
            self.intent,
            ),
            (
                AgentRepositoryQuerySyntaxEvidence::NaturalLanguage,
                AgentRepositoryDiscoveryEvidence::Lexical,
                _,
            )
            | (
                AgentRepositoryQuerySyntaxEvidence::NaturalLanguage,
                AgentRepositoryDiscoveryEvidence::LexicalWithPrecomputedLabels
                    | AgentRepositoryDiscoveryEvidence::ExactKey,
                AgentRepositoryIntent::Resolve,
            )
            | (
                AgentRepositoryQuerySyntaxEvidence::Regex,
                AgentRepositoryDiscoveryEvidence::Regex,
                AgentRepositoryIntent::Resolve,
            )
        );
        if !valid_discovery {
            return Err("repository query syntax, discovery, and intent contradict".to_string());
        }
        let classified = self.coverage.indexed
            + self.coverage.excluded
            + self.coverage.pending
            + self.coverage.limited
            + self.coverage.failed
            + self.coverage.stale;
        if self.coverage.accounted != self.coverage.total || classified != self.coverage.total {
            return Err("repository coverage counts do not account for one scope".to_string());
        }
        let blocking = self.coverage.pending
            + self.coverage.limited
            + self.coverage.failed
            + self.coverage.stale;
        if self.coverage.complete
            && (!self.coverage.eligibility_proven
                || blocking != 0
                || self.coverage.pending_update_count != 0)
        {
            return Err(
                "complete repository coverage requires proven eligibility and no persisted or file-stage blocking work"
                    .to_string(),
            );
        }
        if self.coverage.eligible_for_complete_negative && !self.coverage.complete {
            return Err(
                "complete-negative eligibility requires complete repository coverage".to_string(),
            );
        }
        let qualified = self
            .qualification
            .as_deref()
            .is_some_and(|qualification| !qualification.trim().is_empty());
        if (!self.coverage.complete || self.truncated) != qualified {
            return Err(
                "repository qualification must exactly identify partial or bounded evidence"
                    .to_string(),
            );
        }
        match self.status {
            AgentRepositoryStatus::Empty
                if self.truncated
                    || !self.coverage.complete
                    || !self.coverage.eligible_for_complete_negative
                    || !self.coverage.eligibility_proven
                    || self.qualification.is_some() =>
            {
                return Err("EMPTY requires complete eligible coverage and no qualification".into());
            }
            AgentRepositoryStatus::QualifiedEmpty
                if (!self.truncated
                    && (self.coverage.complete
                        || self.coverage.eligible_for_complete_negative))
                    || self
                        .qualification
                        .as_deref()
                        .is_none_or(|qualification| qualification.trim().is_empty()) =>
            {
                return Err("QUALIFIED_EMPTY requires a qualified partial result".into());
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
            return Err("empty status cannot contain answer evidence".into());
        }
        if matches!(self.status, AgentRepositoryStatus::Answered) && !has_actionable_answer {
            return Err(
                "ANSWERED repository status requires intent-specific answer evidence".to_string(),
            );
        }
        if matches!(self.status, AgentRepositoryStatus::Ambiguous) && !has_ambiguity_evidence {
            return Err("AMBIGUOUS requires disambiguation evidence".into());
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
        let context_records = self.context_relations.len()
            + self.context_findings.len()
            + self.unresolved_references.len()
            + self.ambiguous_references.len();
        if self.intent == AgentRepositoryIntent::ContextRelationship
            && context_records > self.bounds.results
        {
            return Err("context records exceed result bound".into());
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

}
