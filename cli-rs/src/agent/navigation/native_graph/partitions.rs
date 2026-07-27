fn native_condensation_topological_order(
    graph: &NativeGraph,
    membership: &[usize],
) -> Vec<usize> {
    let component_count = membership.iter().copied().max().map_or(0, |value| value + 1);
    let mut outgoing = vec![BTreeSet::new(); component_count];
    let mut incoming = vec![0usize; component_count];
    for edge in &graph.edges {
        let source = membership[edge.source];
        let target = membership[edge.target];
        if source != target && outgoing[source].insert(target) {
            incoming[target] += 1;
        }
    }
    let mut ready = (0..component_count)
        .filter(|&component| incoming[component] == 0)
        .collect::<BTreeSet<_>>();
    let mut order = Vec::with_capacity(component_count);
    while let Some(component) = ready.pop_first() {
        order.push(component);
        for &target in &outgoing[component] {
            incoming[target] -= 1;
            if incoming[target] == 0 {
                ready.insert(target);
            }
        }
    }
    order
}

pub(crate) fn native_weighted_leiden(graph: &NativeGraph, resolution: f64) -> Vec<usize> {
    let mut adjacency = native_undirected_adjacency(graph);
    let mut original_to_current = (0..graph.nodes.len()).collect::<Vec<_>>();
    loop {
        let moved = native_leiden_local_move(&adjacency, resolution);
        let refined = native_leiden_refine(&adjacency, &moved);
        let (partition, community_count) = native_compress_partition(&refined);
        for current in &mut original_to_current {
            *current = partition[*current];
        }
        if community_count == adjacency.len() || community_count <= 1 {
            break;
        }
        adjacency = native_leiden_aggregate(&adjacency, &partition, community_count);
    }
    native_compress_partition(&original_to_current).0
}

fn native_undirected_adjacency(graph: &NativeGraph) -> Vec<BTreeMap<usize, f64>> {
    let mut adjacency = vec![BTreeMap::new(); graph.nodes.len()];
    for edge in &graph.edges {
        *adjacency[edge.source].entry(edge.target).or_default() += edge.weight;
        if edge.source != edge.target {
            *adjacency[edge.target].entry(edge.source).or_default() += edge.weight;
        }
    }
    adjacency
}

fn native_leiden_local_move(
    adjacency: &[BTreeMap<usize, f64>],
    resolution: f64,
) -> Vec<usize> {
    let count = adjacency.len();
    let degree = adjacency
        .iter()
        .map(|row| row.values().sum::<f64>())
        .collect::<Vec<_>>();
    let total_weight = degree.iter().sum::<f64>().max(f64::EPSILON);
    let mut membership = (0..count).collect::<Vec<_>>();
    let mut community_weight = degree.clone();
    for _ in 0..100 {
        let mut changed = false;
        for node in 0..count {
            let current = membership[node];
            community_weight[current] -= degree[node];
            let mut by_community = BTreeMap::<usize, f64>::new();
            for (&target, &weight) in &adjacency[node] {
                *by_community.entry(membership[target]).or_default() += weight;
            }
            by_community.entry(current).or_default();
            let mut best = current;
            let mut best_score = by_community.get(&current).copied().unwrap_or_default()
                - resolution * degree[node] * community_weight[current] / total_weight;
            for (candidate, internal_weight) in by_community {
                let score = internal_weight
                    - resolution * degree[node] * community_weight[candidate] / total_weight;
                if score > best_score + 1e-12
                    || ((score - best_score).abs() <= 1e-12 && candidate < best)
                {
                    best = candidate;
                    best_score = score;
                }
            }
            membership[node] = best;
            community_weight[best] += degree[node];
            changed |= best != current;
        }
        if !changed {
            break;
        }
    }
    membership
}

fn native_leiden_refine(
    adjacency: &[BTreeMap<usize, f64>],
    membership: &[usize],
) -> Vec<usize> {
    let mut refined = vec![usize::MAX; membership.len()];
    let mut next = 0;
    for root in 0..membership.len() {
        if refined[root] != usize::MAX {
            continue;
        }
        refined[root] = next;
        let community = membership[root];
        let mut queue = std::collections::VecDeque::from([root]);
        while let Some(node) = queue.pop_front() {
            for &target in adjacency[node].keys() {
                if membership[target] == community && refined[target] == usize::MAX {
                    refined[target] = next;
                    queue.push_back(target);
                }
            }
        }
        next += 1;
    }
    refined
}

fn native_compress_partition(partition: &[usize]) -> (Vec<usize>, usize) {
    let mut ids = BTreeMap::new();
    let mut next = 0;
    let compressed = partition
        .iter()
        .map(|community| {
            *ids.entry(*community).or_insert_with(|| {
                let value = next;
                next += 1;
                value
            })
        })
        .collect();
    (compressed, next)
}

fn native_leiden_aggregate(
    adjacency: &[BTreeMap<usize, f64>],
    partition: &[usize],
    community_count: usize,
) -> Vec<BTreeMap<usize, f64>> {
    let mut aggregated = vec![BTreeMap::new(); community_count];
    for (source, row) in adjacency.iter().enumerate() {
        for (&target, &weight) in row {
            *aggregated[partition[source]]
                .entry(partition[target])
                .or_default() += weight;
        }
    }
    aggregated
}

fn native_graph_neighbors(
    graph: &NativeGraph,
    generation: u64,
    scope: NativeGraphScope,
    key: &str,
) -> std::result::Result<Value, AgentError> {
    let index = graph
        .nodes
        .iter()
        .position(|node| node.key == key)
        .ok_or_else(|| agent_error("NATIVE_GRAPH_SYMBOL_NOT_FOUND", format!("Graph node not found: {key}")))?;
    let outgoing = graph
        .edges
        .iter()
        .filter(|edge| edge.source == index)
        .map(|edge| {
            json!({
                "target": graph.nodes[edge.target].key,
                "kind": edge.kind,
                "context": edge.context,
                "weight": edge.weight
            })
        })
        .collect::<Vec<_>>();
    let incoming = graph
        .edges
        .iter()
        .filter(|edge| edge.target == index)
        .map(|edge| {
            json!({
                "source": graph.nodes[edge.source].key,
                "kind": edge.kind,
                "context": edge.context,
                "weight": edge.weight
            })
        })
        .collect::<Vec<_>>();
    Ok(json!({
        "type": "KAST_NATIVE_GRAPH_NEIGHBORS",
        "scope": scope,
        "generation": generation,
        "key": key,
        "outgoing": outgoing,
        "incoming": incoming,
        "schemaVersion": SCHEMA_VERSION
    }))
}
