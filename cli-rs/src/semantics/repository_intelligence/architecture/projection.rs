fn projection_accepts_occurrence(
    projection: RepositoryArchitectureProjection,
    occurrence: &RepositoryEdgeOccurrence,
    source: &RepositoryNode,
    target: &RepositoryNode,
) -> bool {
    match projection {
        RepositoryArchitectureProjection::TypeDependencies => {
            occurrence.kind == RepositoryRelationKind::References
                && matches!(
                    occurrence.context.as_str(),
                    "FIELD" | "GENERIC_ARG" | "PARAMETER_TYPE" | "RETURN_TYPE"
                )
        }
        RepositoryArchitectureProjection::ModuleDependencies => {
            architecture_ownership_boundary(source) != architecture_ownership_boundary(target)
        }
        RepositoryArchitectureProjection::RuntimeCalls
        | RepositoryArchitectureProjection::SymbolReferences
        | RepositoryArchitectureProjection::InterfaceImplementation
        | RepositoryArchitectureProjection::ContainmentOwnership => true,
    }
}

fn architecture_hub_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let direction = scope.direction.unwrap_or(RepositoryDirection::Incoming);
    let mut by_subject =
        BTreeMap::<i64, (BTreeSet<i64>, usize, Vec<RepositoryEdgeOccurrence>)>::new();
    for occurrence in &graph.occurrences {
        let source = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        let target = occurrence.target_id;
        let (subject, neighbor) = match direction {
            RepositoryDirection::Incoming => (target, source),
            RepositoryDirection::Outgoing => (source, target),
        };
        let entry = by_subject.entry(subject).or_default();
        entry.0.insert(neighbor);
        entry.1 += 1;
        entry.2.push(occurrence.clone());
    }
    let mut ranked = by_subject
        .into_iter()
        .filter(|(id, _)| {
            graph
                .positions
                .get(id)
                .is_some_and(|position| is_callable_kind(&graph.nodes[*position].kind))
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .0
            .len()
            .cmp(&left.1.0.len())
            .then_with(|| right.1.1.cmp(&left.1.1))
            .then_with(|| {
                architecture_node(graph, left.0)
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0).canonical_key)
            })
    });
    let internal = ranked
        .iter()
        .filter(|(id, _)| architecture_node(graph, *id).visibility != "PUBLIC")
        .cloned()
        .collect::<Vec<_>>();
    let ranked = if internal.is_empty() {
        ranked
    } else {
        internal
    };
    ranked
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, (id, (neighbors, occurrence_count, occurrences)))| {
            let node = architecture_node(graph, id);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "HIGH_CENTRALITY_INTERNAL_IMPLEMENTATION",
                format!("{} {} call hub", node.name, direction_label(direction)),
                format!(
                    "{} has {} distinct {} neighbors across {} compiler occurrences.",
                    node.name,
                    neighbors.len(),
                    direction_label(direction),
                    occurrence_count
                ),
                match direction {
                    RepositoryDirection::Incoming => "INCOMING_CENTRALITY",
                    RepositoryDirection::Outgoing => "OUTGOING_CENTRALITY",
                },
                Some(direction),
                json!({
                    "rule": "distinctNeighborCount ranks first, occurrenceCount breaks ties",
                    "distinctNeighborCount": neighbors.len(),
                    "occurrenceCount": occurrence_count
                }),
                vec![id],
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_cycle_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let membership = native_tarjan_scc(&graph.native);
    let mut components = BTreeMap::<usize, Vec<i64>>::new();
    for (position, component) in membership.into_iter().enumerate() {
        components
            .entry(component)
            .or_default()
            .push(graph.nodes[position].database_id);
    }
    let mut cycles = components
        .into_values()
        .filter_map(|members| {
            if members.len() < 2 {
                return None;
            }
            let member_set = members.iter().copied().collect::<BTreeSet<_>>();
            let boundaries = members
                .iter()
                .map(|id| architecture_package_boundary(architecture_node(graph, *id)))
                .collect::<BTreeSet<_>>();
            if boundaries.len() < 2 {
                return None;
            }
            let occurrences = graph
                .occurrences
                .iter()
                .filter(|occurrence| {
                    member_set.contains(&occurrence.lifted_source.unwrap_or(occurrence.source_id))
                        && member_set.contains(&occurrence.target_id)
                })
                .cloned()
                .collect::<Vec<_>>();
            (!occurrences.is_empty()).then_some((members, boundaries, occurrences))
        })
        .collect::<Vec<_>>();
    cycles.sort_by(|left, right| {
        right
            .0
            .len()
            .cmp(&left.0.len())
            .then_with(|| right.2.len().cmp(&left.2.len()))
            .then_with(|| {
                architecture_node(graph, left.0[0])
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0[0]).canonical_key)
            })
    });
    if cycles.is_empty() {
        return architecture_boundary_cycle_findings(
            connection, graph, generation, scope, projection, limits,
        );
    }
    cycles
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, (members, boundaries, occurrences))| {
            let first = architecture_node(graph, members[0]);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "CYCLE_CROSSING_BOUNDARY",
                format!("{} cross-boundary call cycle", first.name),
                format!(
                    "{} exact symbols form a strongly connected component across {} boundaries.",
                    members.len(),
                    boundaries.len()
                ),
                RepositoryArchitectureMetric::Scc.canonical(),
                None,
                json!({
                    "rule": "componentSize > 1 and packageOrModuleBoundaryCount > 1",
                    "componentSize": members.len(),
                    "boundaryCount": boundaries.len()
                }),
                members,
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}
