fn architecture_boundary_cycle_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let boundaries = graph
        .occurrences
        .iter()
        .flat_map(|occurrence| {
            let source = architecture_node(
                graph,
                occurrence.lifted_source.unwrap_or(occurrence.source_id),
            );
            let target = architecture_node(graph, occurrence.target_id);
            [
                architecture_package_boundary(source),
                architecture_package_boundary(target),
            ]
        })
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect::<Vec<_>>();
    let positions = boundaries
        .iter()
        .enumerate()
        .map(|(position, boundary)| (boundary.clone(), position))
        .collect::<BTreeMap<_, _>>();
    let mut grouped = BTreeMap::<(usize, usize), usize>::new();
    for occurrence in &graph.occurrences {
        let source = architecture_package_boundary(architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        ));
        let target = architecture_package_boundary(architecture_node(graph, occurrence.target_id));
        if source != target {
            *grouped
                .entry((positions[&source], positions[&target]))
                .or_default() += 1;
        }
    }
    let boundary_graph = native_graph_to_csr(
        boundaries
            .iter()
            .map(|boundary| NativeGraphNode {
                database_id: None,
                key: boundary.clone(),
            })
            .collect(),
        grouped
            .into_iter()
            .map(|((source, target), weight)| NativeGraphEdge {
                source,
                target,
                kind: RepositoryRelationKind::Calls.canonical().to_string(),
                context: "BOUNDARY".to_string(),
                weight: weight as f64,
            })
            .collect(),
    );
    let membership = native_tarjan_scc(&boundary_graph);
    let mut components = BTreeMap::<usize, BTreeSet<String>>::new();
    for (position, component) in membership.into_iter().enumerate() {
        components
            .entry(component)
            .or_default()
            .insert(boundaries[position].clone());
    }
    let mut cycles = components
        .into_values()
        .filter_map(|component| {
            if component.len() < 2 {
                return None;
            }
            let occurrences = graph
                .occurrences
                .iter()
                .filter(|occurrence| {
                    let source = architecture_package_boundary(architecture_node(
                        graph,
                        occurrence.lifted_source.unwrap_or(occurrence.source_id),
                    ));
                    let target = architecture_package_boundary(architecture_node(
                        graph,
                        occurrence.target_id,
                    ));
                    source != target && component.contains(&source) && component.contains(&target)
                })
                .cloned()
                .collect::<Vec<_>>();
            let proof = architecture_boundary_cycle_proof(graph, &component, &occurrences);
            (!proof.is_empty()).then_some((component, occurrences.len(), proof))
        })
        .collect::<Vec<_>>();
    cycles.sort_by(|left, right| {
        right
            .0
            .len()
            .cmp(&left.0.len())
            .then_with(|| right.1.cmp(&left.1))
            .then_with(|| left.0.cmp(&right.0))
    });
    cycles
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, (boundaries, occurrence_count, proof))| {
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "CYCLE_CROSSING_BOUNDARY",
                format!("{}-boundary runtime-call cycle", boundaries.len()),
                format!(
                    "{} package or module boundaries form a directed strongly connected component.",
                    boundaries.len()
                ),
                RepositoryArchitectureMetric::Scc.canonical(),
                None,
                json!({
                    "rule": "boundary-projected strongly connected component has more than one member",
                    "boundaryCount": boundaries.len(),
                    "boundaries": boundaries,
                    "projectedOccurrenceCount": occurrence_count,
                    "supportingCycleLength": proof.len()
                }),
                architecture_occurrence_nodes(&proof),
                &proof,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_boundary_cycle_proof(
    graph: &RepositoryArchitectureGraph,
    component: &BTreeSet<String>,
    occurrences: &[RepositoryEdgeOccurrence],
) -> Vec<RepositoryEdgeOccurrence> {
    let mut edges = BTreeMap::<(String, String), RepositoryEdgeOccurrence>::new();
    let mut adjacency = BTreeMap::<String, BTreeSet<String>>::new();
    for occurrence in occurrences {
        let source = architecture_package_boundary(architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        ));
        let target = architecture_package_boundary(architecture_node(graph, occurrence.target_id));
        edges
            .entry((source.clone(), target.clone()))
            .or_insert_with(|| occurrence.clone());
        adjacency.entry(source).or_default().insert(target);
    }
    for start in component {
        for next in adjacency.get(start).into_iter().flatten() {
            let mut queue = std::collections::VecDeque::from([(next.clone(), vec![next.clone()])]);
            let mut visited = BTreeSet::from([next.clone()]);
            while let Some((current, path)) = queue.pop_front() {
                if current == *start {
                    let mut cycle = vec![start.clone()];
                    cycle.extend(path);
                    return cycle
                        .windows(2)
                        .filter_map(|step| edges.get(&(step[0].clone(), step[1].clone())).cloned())
                        .collect();
                }
                for target in adjacency.get(&current).into_iter().flatten() {
                    if visited.insert(target.clone()) {
                        let mut candidate = path.clone();
                        candidate.push(target.clone());
                        queue.push_back((target.clone(), candidate));
                    }
                }
            }
        }
    }
    Vec::new()
}
