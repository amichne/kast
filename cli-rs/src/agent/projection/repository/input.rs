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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRepositoryQueryPlanInput {
    query_syntax: AgentRepositoryQuerySyntaxEvidence,
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
            query_syntax: self.query_plan.query_syntax,
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
