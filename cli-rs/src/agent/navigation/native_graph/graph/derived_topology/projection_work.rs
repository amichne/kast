const DERIVED_TOPOLOGY_WORK_EVIDENCE_PREFIX: &str =
    "KAST_TEST_DERIVED_TOPOLOGY_WORK_EVIDENCE=";

#[derive(Debug, Default)]
struct DerivedTopologyCommunityEdgeMetrics {
    internal_edge_count: usize,
    external_edge_count: usize,
    internal_weight: f64,
    external_weight: f64,
    relationship_kinds: BTreeMap<DerivedRelationshipKind, usize>,
}

impl DerivedTopologyCommunityEdgeMetrics {
    fn record_internal(&mut self, edge: &ReferenceEdgeInput) {
        self.internal_edge_count += 1;
        self.internal_weight += edge.normalized_weight;
        self.record_relationship(edge);
    }

    fn record_external(&mut self, edge: &ReferenceEdgeInput) {
        self.external_edge_count += 1;
        self.external_weight += edge.normalized_weight;
        self.record_relationship(edge);
    }

    fn record_relationship(&mut self, edge: &ReferenceEdgeInput) {
        *self.relationship_kinds.entry(edge.kind).or_default() += edge.occurrence_count;
    }
}

#[derive(Debug, Clone, Copy)]
struct DerivedTopologyProjectionWork {
    node_count: usize,
    edge_count: usize,
    community_count: usize,
    edge_visits: usize,
}

fn emit_derived_topology_work_evidence(work: DerivedTopologyProjectionWork) {
    if cfg!(debug_assertions)
        && std::env::var("KAST_TEST_DERIVED_TOPOLOGY_WORK_EVIDENCE").as_deref() == Ok("1")
    {
        eprintln!(
            "{DERIVED_TOPOLOGY_WORK_EVIDENCE_PREFIX}{}",
            serde_json::json!({
                "nodeCount": work.node_count,
                "edgeCount": work.edge_count,
                "communityCount": work.community_count,
                "edgeVisits": work.edge_visits,
            })
        );
    }
}
