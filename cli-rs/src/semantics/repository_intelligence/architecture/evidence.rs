fn architecture_supporting_subgraph(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    occurrences: &[RepositoryEdgeOccurrence],
    limits: &RepositoryLimits,
) -> Result<Value> {
    let selected_identities = occurrences
        .iter()
        .map(architecture_occurrence_identity)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .take(limits.results.min(10))
        .collect::<BTreeSet<_>>();
    let selected = occurrences
        .iter()
        .filter(|occurrence| {
            selected_identities.contains(&architecture_occurrence_identity(occurrence))
        })
        .cloned()
        .collect::<Vec<_>>();
    let mut node_cache = RepositoryNodeCache {
        execution_scope: &graph.execution_scope,
        nodes: graph
            .nodes
            .iter()
            .map(|node| (node.database_id, node.clone()))
            .collect(),
    };
    let edges = repository_edges(
        connection,
        &selected,
        RepositoryDirection::Outgoing,
        limits.evidence,
        None,
        None,
        &mut node_cache,
    )?;
    let mut node_ids = BTreeSet::new();
    for occurrence in &selected {
        node_ids.insert(occurrence.lifted_source.unwrap_or(occurrence.source_id));
        node_ids.insert(occurrence.target_id);
    }
    let nodes = node_ids
        .into_iter()
        .filter_map(|id| node_cache.nodes.get(&id).cloned())
        .collect::<Vec<_>>();
    Ok(json!({
        "nodes": nodes,
        "edges": edges,
        "truncated": selected_identities.len()
            < occurrences
                .iter()
                .map(architecture_occurrence_identity)
                .collect::<BTreeSet<_>>()
                .len()
    }))
}

fn architecture_occurrence_identity(
    occurrence: &RepositoryEdgeOccurrence,
) -> (i64, i64, RepositoryRelationKind, String) {
    (
        occurrence.lifted_source.unwrap_or(occurrence.source_id),
        occurrence.target_id,
        occurrence.kind,
        occurrence.context.clone(),
    )
}

fn architecture_finding_probe_limit(limits: &RepositoryLimits) -> usize {
    limits.results.saturating_add(1)
}

fn architecture_occurrence_nodes(occurrences: &[RepositoryEdgeOccurrence]) -> Vec<i64> {
    occurrences
        .iter()
        .flat_map(|occurrence| {
            [
                occurrence.lifted_source.unwrap_or(occurrence.source_id),
                occurrence.target_id,
            ]
        })
        .collect()
}

fn architecture_highest_degree_member(
    members: &[i64],
    occurrences: &[RepositoryEdgeOccurrence],
) -> i64 {
    let mut degree = BTreeMap::<i64, usize>::new();
    for occurrence in occurrences {
        *degree
            .entry(occurrence.lifted_source.unwrap_or(occurrence.source_id))
            .or_default() += 1;
        *degree.entry(occurrence.target_id).or_default() += 1;
    }
    members
        .iter()
        .copied()
        .max_by_key(|id| {
            (
                degree.get(id).copied().unwrap_or_default(),
                std::cmp::Reverse(*id),
            )
        })
        .expect("architecture community is non-empty")
}

fn architecture_node(graph: &RepositoryArchitectureGraph, id: i64) -> &RepositoryNode {
    &graph.nodes[graph.positions[&id]]
}

fn architecture_ownership_boundary(node: &RepositoryNode) -> String {
    if node.gradle_projects.is_empty() {
        "<unowned>".to_string()
    } else {
        node.gradle_projects.join(" + ")
    }
}

fn architecture_package_boundary(node: &RepositoryNode) -> String {
    let package = node
        .fq_name
        .as_deref()
        .and_then(|name| name.rsplit_once('.').map(|(package, _)| package))
        .unwrap_or("<root>");
    format!("{}:{package}", architecture_ownership_boundary(node))
}

fn direction_label(direction: RepositoryDirection) -> &'static str {
    match direction {
        RepositoryDirection::Incoming => "incoming",
        RepositoryDirection::Outgoing => "outgoing",
    }
}

fn is_type_kind(kind: &str) -> bool {
    matches!(
        kind,
        "CLASS" | "ENUM_CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS"
    )
}
