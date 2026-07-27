fn graph_repository_question(
    connection: &Connection,
    question: &str,
    intent: RepositoryIntent,
    execution: &RepositoryGraphExecution<'_>,
    continuation_context: &RepositoryContinuationContext,
    traversal_resume: Option<&RepositoryTraversalContinuationState>,
    evidence_resume: Option<&RepositoryEvidenceResume>,
) -> Result<Value> {
    let mentions = repository_symbol_mentions(connection, question)?;
    let fallback = likely_declaration_term(question).map(str::to_string);
    let Some(start_name) = mentions.first().map(|(_, name)| name.clone()).or(fallback) else {
        return Ok(json!({
            "answered": false,
            "ambiguous": false,
            "nodes": [],
            "edges": [],
            "paths": [],
            "identityCollisions": 0,
            "truncated": false
        }));
    };
    let starts = best_question_nodes(connection, question, execution.admitted, Some(&start_name))?;
    if starts.len() != 1 {
        return Ok(json!({
            "answered": false,
            "ambiguous": starts.len() > 1,
            "nodes": starts,
            "edges": [],
            "paths": [],
            "identityCollisions": 0,
            "truncated": false
        }));
    }
    let start = starts[0].clone();
    if traversal_resume.is_some_and(|resume| resume.canonical_start_key != start.canonical_key) {
        return Err(invalid_repository_continuation(
            "Repository traversal continuation canonical start is unavailable.",
        ));
    }
    let direction = match intent {
        RepositoryIntent::IncomingImpact => RepositoryDirection::Incoming,
        RepositoryIntent::OutgoingImpact => RepositoryDirection::Outgoing,
        RepositoryIntent::Path => execution
            .request_scope
            .direction
            .unwrap_or(RepositoryDirection::Outgoing),
        RepositoryIntent::Resolve
        | RepositoryIntent::Architecture
        | RepositoryIntent::ContextRelationship => {
            unreachable!("non-graph intent is handled separately")
        }
    };
    let relations = if execution.request_scope.relations.is_empty() {
        vec![RepositoryRelationKind::Calls]
    } else {
        execution.request_scope.relations.clone()
    };
    let target = if intent == RepositoryIntent::Path && mentions.len() > 1 {
        let target_name = &mentions[mentions.len() - 1].1;
        let candidates = execution.admitted.admit_nodes(load_repository_node(
            connection,
            "symbol.name = ?1",
            target_name,
        )?);
        let occurrences =
            load_relation_occurrences(connection, &relations, execution.admitted)?;
        match resolve_path_target(
            candidates,
            &start,
            question,
            direction,
            &occurrences,
            execution.limits.results,
        ) {
            RepositoryPathTargetResolution::Missing => {
                return Ok(json!({
                    "answered": false,
                    "ambiguous": false,
                    "nodes": [],
                    "edges": [],
                    "paths": [],
                    "identityCollisions": 0,
                    "truncated": false
                }));
            }
            RepositoryPathTargetResolution::Unique(target) => Some(*target),
            RepositoryPathTargetResolution::Ambiguous {
                candidates,
                truncated,
            } => {
                return Ok(json!({
                    "answered": false,
                    "ambiguous": true,
                    "nodes": candidates,
                    "edges": [],
                    "paths": [],
                    "identityCollisions": 0,
                    "truncated": truncated
                }));
            }
        }
    } else {
        None
    };
    let traversal = if evidence_resume.is_some() && traversal_resume.is_some() {
        RepositoryTraversal {
            occurrences: load_relation_occurrences(
                connection,
                &relations,
                execution.admitted,
            )?,
            predecessors: BTreeMap::new(),
            path_targets: BTreeSet::new(),
            visited: BTreeSet::from([start.database_id]),
            target_id: None,
            resume: traversal_resume.map(|state| state.resume),
        }
    } else {
        traverse_repository_graph(
            connection,
            &start,
            target.as_ref(),
            question,
            direction,
            execution,
            traversal_resume.map(|resume| &resume.resume),
        )?
    };
    let continuation = traversal
        .resume
        .map(|resume| {
            issue_repository_traversal_continuation(
                continuation_context,
                &start.canonical_key,
                resume,
            )
        })
        .transpose()?;
    let mut node_cache = RepositoryNodeCache {
        execution_scope: execution.admitted,
        nodes: BTreeMap::new(),
    };
    node_cache.nodes.insert(start.database_id, start.clone());
    if let Some(target) = target {
        node_cache.nodes.insert(target.database_id, target);
    }
    let edges = repository_edges(
        connection,
        &traversal.occurrences,
        direction,
        execution.limits.evidence,
        Some(continuation_context),
        evidence_resume,
        &mut node_cache,
    )?;
    let path_projection = RepositoryPathProjection {
        start_id: start.database_id,
        predecessors: &traversal.predecessors,
        path_targets: &traversal.path_targets,
        relations: &relations,
        direction,
        limit: execution.limits.results,
    };
    let paths = repository_paths(connection, &path_projection, &mut node_cache)?;
    let mut nodes = node_cache.nodes.into_values().collect::<Vec<_>>();
    nodes.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    let target_reached = traversal
        .target_id
        .is_none_or(|target_id| traversal.visited.contains(&target_id));
    let answered = !edges.is_empty() && target_reached;
    let truncated = continuation.is_some()
        || edges
            .iter()
            .any(|edge| edge.evidence_continuation.is_some());
    Ok(json!({
        "answered": answered,
        "ambiguous": false,
        "nodes": nodes,
        "edges": edges,
        "paths": paths,
        "identityCollisions": 0,
        "truncated": truncated,
        "continuation": continuation
    }))
}

fn resolve_path_target(
    candidates: Vec<RepositoryNode>,
    start: &RepositoryNode,
    question: &str,
    direction: RepositoryDirection,
    occurrences: &[RepositoryEdgeOccurrence],
    limit: usize,
) -> RepositoryPathTargetResolution {
    let question = question.to_ascii_lowercase();
    let explicitly_named = candidates
        .iter()
        .filter(|candidate| {
            candidate.fq_name.as_deref().is_some_and(|fq_name| {
                identifier_position(&question, &fq_name.to_ascii_lowercase()).is_some()
            }) || candidate.owner_name.as_deref().is_some_and(|owner| {
                identifier_position(
                    &question,
                    &format!("{owner}.{}", candidate.name).to_ascii_lowercase(),
                )
                .is_some()
            })
        })
        .cloned()
        .collect::<Vec<_>>();
    let directly_related = candidates
        .iter()
        .filter(|candidate| {
            occurrences.iter().any(|occurrence| {
                let source = occurrence.lifted_source.unwrap_or(occurrence.source_id);
                let (from, to) = match direction {
                    RepositoryDirection::Outgoing => (source, occurrence.target_id),
                    RepositoryDirection::Incoming => (occurrence.target_id, source),
                };
                from == start.database_id && to == candidate.database_id
            })
        })
        .cloned()
        .collect::<Vec<_>>();
    let mut selected = if !explicitly_named.is_empty() {
        explicitly_named
    } else if !directly_related.is_empty() {
        directly_related
    } else {
        candidates
    };
    selected.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    match selected.len() {
        0 => RepositoryPathTargetResolution::Missing,
        1 => selected
            .pop()
            .map_or(RepositoryPathTargetResolution::Missing, |target| {
                RepositoryPathTargetResolution::Unique(Box::new(target))
            }),
        _ => {
            let truncated = selected.len() > limit;
            selected.truncate(limit);
            RepositoryPathTargetResolution::Ambiguous {
                candidates: selected,
                truncated,
            }
        }
    }
}
