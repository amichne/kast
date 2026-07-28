fn architecture_repository_question(
    connection: &Connection,
    generation: u64,
    scope: &RepositoryScope,
    execution_scope: &RepositoryExecutionScope,
    limits: &RepositoryLimits,
) -> Result<Value> {
    let projection = scope.projection.ok_or_else(|| {
        CliError::new(
            "INVALID_REPOSITORY_SCOPE",
            "architecture queries require an explicit relation-specific projection",
        )
    })?;
    let graph = load_repository_architecture_graph(connection, execution_scope, projection)?;
    let mut findings = match scope.metric {
        Some(RepositoryArchitectureMetric::Scc) => {
            architecture_cycle_findings(connection, &graph, generation, scope, projection, limits)?
        }
        Some(RepositoryArchitectureMetric::Communities) => architecture_community_findings(
            connection, &graph, generation, scope, projection, limits,
        )?,
        Some(RepositoryArchitectureMetric::Bridges) => {
            architecture_bridge_findings(connection, &graph, generation, scope, projection, limits)?
        }
        Some(RepositoryArchitectureMetric::PublicApiConsumers) => architecture_public_api_findings(
            connection, &graph, generation, scope, projection, limits,
        )?,
        None if matches!(
            projection,
            RepositoryArchitectureProjection::TypeDependencies
                | RepositoryArchitectureProjection::ModuleDependencies
        ) =>
        {
            architecture_boundary_findings(
                connection, &graph, generation, scope, projection, limits,
            )?
        }
        None => {
            architecture_hub_findings(connection, &graph, generation, scope, projection, limits)?
        }
    };
    let truncated = findings.len() > limits.results;
    findings.truncate(limits.results);
    Ok(json!({
        "answered": !findings.is_empty(),
        "ambiguous": false,
        "findings": findings,
        "nodes": [],
        "identityCollisions": 0,
        "truncated": truncated
    }))
}

fn load_repository_architecture_graph(
    connection: &Connection,
    execution_scope: &RepositoryExecutionScope,
    projection: RepositoryArchitectureProjection,
) -> Result<RepositoryArchitectureGraph> {
    let mut nodes = execution_scope.admit_nodes(load_repository_node(connection, "1 = ?1", 1i64)?);
    nodes.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    let positions = nodes
        .iter()
        .enumerate()
        .map(|(position, node)| (node.database_id, position))
        .collect::<BTreeMap<_, _>>();
    let by_id = nodes
        .iter()
        .map(|node| (node.database_id, node))
        .collect::<BTreeMap<_, _>>();
    let occurrences =
        load_relation_occurrences(connection, projection.relation_kinds(), execution_scope)?
            .into_iter()
            .filter(|occurrence| {
                let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
                let Some(source) = by_id.get(&source_id) else {
                    return false;
                };
                let Some(target) = by_id.get(&occurrence.target_id) else {
                    return false;
                };
                projection_accepts_occurrence(projection, occurrence, source, target)
            })
            .collect::<Vec<_>>();
    let mut grouped = BTreeMap::<(usize, usize), usize>::new();
    for occurrence in &occurrences {
        let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        let source = positions[&source_id];
        let target = positions[&occurrence.target_id];
        *grouped
            .entry((source, target))
            .or_default() += 1;
    }
    let native_nodes = nodes
        .iter()
        .map(|node| NativeGraphNode {
            database_id: u64::try_from(node.database_id).ok(),
            key: node.canonical_key.clone(),
        })
        .collect();
    let native_edges = grouped
        .into_iter()
        .map(|((source, target), occurrence_count)| NativeGraphEdge {
            source,
            target,
            occurrence_count,
            weight: occurrence_count as f64,
        })
        .collect();
    Ok(RepositoryArchitectureGraph {
        nodes,
        positions,
        occurrences,
        native: native_graph_to_csr(native_nodes, native_edges),
        execution_scope: execution_scope.clone(),
    })
}
