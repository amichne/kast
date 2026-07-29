impl AgentRepositoryProjectionInput {
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
        let completeness = if self.truncated || !self.coverage.complete {
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
            discovery: self.query_plan.discovery,
            workspace_root: self.workspace_identity.canonical_root,
            generation: self.generation,
            coverage: self.coverage,
            bounds: self.bounds,
            cardinality,
            selected_identity: self.selected_identity,
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
