fn architecture_boundary_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let mut groups = BTreeMap::<(String, String), Vec<RepositoryEdgeOccurrence>>::new();
    for occurrence in &graph.occurrences {
        let source = architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        );
        let target = architecture_node(graph, occurrence.target_id);
        let source_module = architecture_ownership_boundary(source);
        let target_module = architecture_ownership_boundary(target);
        if source_module != target_module {
            groups
                .entry((source_module, target_module))
                .or_default()
                .push(occurrence.clone());
        }
    }
    let mut ranked = groups.into_iter().collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .len()
            .cmp(&left.1.len())
            .then_with(|| left.0.cmp(&right.0))
    });
    ranked
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, ((source_module, target_module), occurrences))| {
            let representatives = architecture_occurrence_nodes(&occurrences);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "BOUNDARY_CROSSING",
                format!("{source_module} to {target_module} type boundary"),
                format!(
                    "{} explicit type-dependency occurrences cross from {source_module} to {target_module}.",
                    occurrences.len()
                ),
                "CROSS_BOUNDARY_EDGE_COUNT",
                Some(scope.direction.unwrap_or(RepositoryDirection::Outgoing)),
                json!({
                    "rule": "sourceModule != targetModule, ranked by occurrenceCount",
                    "sourceModule": source_module,
                    "targetModule": target_module,
                    "occurrenceCount": occurrences.len()
                }),
                representatives,
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_community_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let membership = native_weighted_leiden(&graph.native, 1.0);
    let mut communities = BTreeMap::<usize, Vec<i64>>::new();
    for (position, community) in membership.into_iter().enumerate() {
        communities
            .entry(community)
            .or_default()
            .push(graph.nodes[position].database_id);
    }
    let mut ranked = communities
        .into_values()
        .filter_map(|members| {
            if members.len() < 2 {
                return None;
            }
            let member_set = members.iter().copied().collect::<BTreeSet<_>>();
            let occurrences = graph
                .occurrences
                .iter()
                .filter(|occurrence| {
                    member_set.contains(&occurrence.lifted_source.unwrap_or(occurrence.source_id))
                        && member_set.contains(&occurrence.target_id)
                })
                .cloned()
                .collect::<Vec<_>>();
            (!occurrences.is_empty()).then_some((members, occurrences))
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .len()
            .cmp(&left.1.len())
            .then_with(|| right.0.len().cmp(&left.0.len()))
            .then_with(|| {
                architecture_node(graph, left.0[0])
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0[0]).canonical_key)
            })
    });
    ranked
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, (members, occurrences))| {
            let unique_edges = occurrences
                .iter()
                .map(architecture_occurrence_identity)
                .collect::<BTreeSet<_>>()
                .len();
            let possible = members
                .len()
                .saturating_mul(members.len().saturating_sub(1));
            let cohesion = unique_edges as f64 / possible.max(1) as f64;
            let representative = architecture_node(
                graph,
                architecture_highest_degree_member(&members, &occurrences),
            );
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "COMMUNITY",
                format!(
                    "{} / {} runtime call community",
                    architecture_ownership_boundary(representative),
                    representative.name
                ),
                format!(
                    "{} exact symbols share {} internal runtime-call edges.",
                    members.len(),
                    unique_edges
                ),
                RepositoryArchitectureMetric::Communities.canonical(),
                None,
                json!({
                    "rule": "deterministic weighted Leiden at resolution 1.0",
                    "memberCount": members.len(),
                    "internalEdgeCount": unique_edges,
                    "resolution": 1.0
                }),
                members,
                &occurrences,
                Some(cohesion),
                limits,
            )
        })
        .collect()
}

fn architecture_bridge_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let membership = native_weighted_leiden(&graph.native, 1.0);
    let mut bridges = BTreeMap::<(usize, usize), Vec<RepositoryEdgeOccurrence>>::new();
    for occurrence in &graph.occurrences {
        let source = graph.positions[&occurrence.lifted_source.unwrap_or(occurrence.source_id)];
        let target = graph.positions[&occurrence.target_id];
        let source_community = membership[source];
        let target_community = membership[target];
        if source_community != target_community {
            let pair = if source_community < target_community {
                (source_community, target_community)
            } else {
                (target_community, source_community)
            };
            bridges.entry(pair).or_default().push(occurrence.clone());
        }
    }
    let mut ranked = bridges
        .into_iter()
        .map(|(pair, occurrences)| {
            let edge_count = occurrences
                .iter()
                .map(architecture_occurrence_identity)
                .collect::<BTreeSet<_>>()
                .len();
            (pair, edge_count, occurrences)
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| left.1.cmp(&right.1).then_with(|| left.0.cmp(&right.0)));
    ranked
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, ((left, right), edge_count, occurrences))| {
            let first = &occurrences[0];
            let source = architecture_node(
                graph,
                first.lifted_source.unwrap_or(first.source_id),
            );
            let target = architecture_node(graph, first.target_id);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "THIN_BRIDGE",
                format!(
                    "{} to {} reference bridge",
                    architecture_ownership_boundary(source),
                    architecture_ownership_boundary(target)
                ),
                format!(
                    "{edge_count} exact reference edges connect otherwise separate deterministic communities."
                ),
                RepositoryArchitectureMetric::Bridges.canonical(),
                None,
                json!({
                    "rule": "cross-community edge count ranked ascending",
                    "leftCommunity": left,
                    "rightCommunity": right,
                    "edgeCount": edge_count,
                    "resolution": 1.0
                }),
                architecture_occurrence_nodes(&occurrences),
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_public_api_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let mut consumers = BTreeMap::<i64, (BTreeSet<String>, Vec<RepositoryEdgeOccurrence>)>::new();
    for occurrence in &graph.occurrences {
        let target = architecture_node(graph, occurrence.target_id);
        if target.visibility != "PUBLIC" || !is_type_kind(&target.kind) {
            continue;
        }
        let source = architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        );
        let entry = consumers.entry(target.database_id).or_default();
        entry.0.insert(architecture_package_boundary(source));
        entry.1.push(occurrence.clone());
    }
    let mut ranked = consumers
        .into_iter()
        .filter(|(_, (boundaries, _))| boundaries.len() >= 2)
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .0
            .len()
            .cmp(&left.1.0.len())
            .then_with(|| right.1.1.len().cmp(&left.1.1.len()))
            .then_with(|| {
                architecture_node(graph, left.0)
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0).canonical_key)
            })
    });
    ranked
        .into_iter()
        .take(architecture_finding_probe_limit(limits))
        .enumerate()
        .map(|(rank, (target_id, (boundaries, occurrences)))| {
            let target = architecture_node(graph, target_id);
            let mut representatives = vec![target_id];
            representatives.extend(architecture_occurrence_nodes(&occurrences));
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "PUBLIC_API_CONSUMED_BY_UNRELATED_COMPONENTS",
                format!("{} cross-component public API", target.name),
                format!(
                    "{} is consumed from {} unrelated package or module boundaries.",
                    target.name,
                    boundaries.len()
                ),
                RepositoryArchitectureMetric::PublicApiConsumers.canonical(),
                None,
                json!({
                    "rule": "public type has incoming type dependencies from at least two package or module boundaries",
                    "consumerBoundaryCount": boundaries.len(),
                    "occurrenceCount": occurrences.len()
                }),
                representatives,
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

#[allow(clippy::too_many_arguments)]
fn architecture_finding(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    rank: usize,
    finding_type: &'static str,
    name: String,
    summary: String,
    metric: &'static str,
    direction: Option<RepositoryDirection>,
    trigger: Value,
    representative_ids: Vec<i64>,
    occurrences: &[RepositoryEdgeOccurrence],
    cohesion: Option<f64>,
    limits: &RepositoryLimits,
) -> Result<Value> {
    let mut representative_symbols = representative_ids
        .into_iter()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .filter_map(|id| {
            graph
                .positions
                .get(&id)
                .map(|position| graph.nodes[*position].clone())
        })
        .collect::<Vec<_>>();
    representative_symbols.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    representative_symbols.truncate(5);
    let relation_composition = occurrences.iter().fold(
        BTreeMap::<&'static str, usize>::new(),
        |mut counts, occurrence| {
            *counts.entry(occurrence.kind.canonical()).or_default() += 1;
            counts
        },
    );
    let supporting_subgraph =
        architecture_supporting_subgraph(connection, graph, occurrences, limits)?;
    Ok(json!({
        "rank": rank,
        "type": finding_type,
        "name": name,
        "summary": summary,
        "projection": projection.canonical(),
        "relationTypes": projection
            .relation_kinds()
            .iter()
            .map(|relation| relation.canonical())
            .collect::<Vec<_>>(),
        "direction": direction,
        "metric": metric,
        "trigger": trigger,
        "graphGeneration": generation,
        "scope": scope,
        "representativeSymbols": representative_symbols,
        "supportingSubgraph": supporting_subgraph,
        "relationComposition": relation_composition,
        "cohesion": cohesion,
        "evidenceClass": "derived",
        "derivation": {
            "rule": "DETERMINISTIC_RELATION_SPECIFIC_ARCHITECTURE",
            "projection": projection.canonical(),
            "metric": metric
        }
    }))
}
