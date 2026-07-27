fn traverse_repository_path(
    connection: &Connection,
    all_occurrences: Vec<RepositoryEdgeOccurrence>,
    start: &RepositoryNode,
    target: &RepositoryNode,
    question: &str,
    direction: RepositoryDirection,
    max_depth: usize,
) -> Result<RepositoryTraversal> {
    let directed_step = |occurrence: &RepositoryEdgeOccurrence| {
        let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        match direction {
            RepositoryDirection::Outgoing => (source_id, occurrence.target_id),
            RepositoryDirection::Incoming => (occurrence.target_id, source_id),
        }
    };
    let mut distance_to_target = BTreeMap::from([(target.database_id, 0usize)]);
    let mut reverse_frontier = BTreeSet::from([target.database_id]);
    for depth in 1..=max_depth {
        let mut next_frontier = BTreeSet::new();
        for occurrence in &all_occurrences {
            let (from, to) = directed_step(occurrence);
            if reverse_frontier.contains(&to) && !distance_to_target.contains_key(&from) {
                distance_to_target.insert(from, depth);
                next_frontier.insert(from);
            }
        }
        if next_frontier.is_empty() {
            break;
        }
        reverse_frontier = next_frontier;
    }

    let mut route_tokens = search_tokens(question);
    for token in search_tokens(&start.name)
        .into_iter()
        .chain(search_tokens(&target.name))
        .chain(
            target
                .owner_name
                .as_deref()
                .map(search_tokens)
                .unwrap_or_default(),
        )
    {
        route_tokens.remove(&token);
    }
    let mut relevance = BTreeMap::new();
    let mut frontier = BTreeMap::from([(start.database_id, (0usize, vec![start.database_id]))]);
    let mut best_target: Option<(usize, Vec<i64>)> = None;
    for depth in 1..=max_depth {
        let mut next_frontier = BTreeMap::<i64, (usize, Vec<i64>)>::new();
        for (_, (score, path)) in frontier {
            let current = *path.last().expect("path has a current node");
            for occurrence in &all_occurrences {
                let (from, next) = directed_step(occurrence);
                if from != current
                    || path.contains(&next)
                    || distance_to_target
                        .get(&next)
                        .is_none_or(|distance| depth + distance > max_depth)
                {
                    continue;
                }
                let node_score = match relevance.get(&next).copied() {
                    Some(score) => score,
                    None => {
                        let node = load_repository_node(connection, "symbol.id = ?1", next)?
                            .into_iter()
                            .next()
                            .ok_or_else(|| {
                                CliError::new(
                                    "REPOSITORY_INDEX_INVALID",
                                    format!("semantic edge references missing symbol id {next}"),
                                )
                            })?;
                        let score = search_tokens(&node.name)
                            .intersection(&route_tokens)
                            .count()
                            * 5;
                        relevance.insert(next, score);
                        score
                    }
                };
                let mut candidate_path = path.clone();
                candidate_path.push(next);
                let candidate = (score + node_score, candidate_path);
                let slot = if next == target.database_id {
                    &mut best_target
                } else {
                    next_frontier
                        .entry(next)
                        .or_insert_with(|| candidate.clone());
                    let entry = next_frontier
                        .get_mut(&next)
                        .expect("candidate was inserted");
                    if path_candidate_better(&candidate, entry) {
                        *entry = candidate;
                    }
                    continue;
                };
                if slot
                    .as_ref()
                    .is_none_or(|existing| path_candidate_better(&candidate, existing))
                {
                    *slot = Some(candidate);
                }
            }
        }
        if next_frontier.is_empty() {
            break;
        }
        frontier = next_frontier;
    }

    let Some((_, path)) = best_target else {
        return Ok(RepositoryTraversal {
            occurrences: Vec::new(),
            predecessors: BTreeMap::new(),
            path_targets: BTreeSet::new(),
            visited: BTreeSet::from([start.database_id]),
            target_id: Some(target.database_id),
            resume: None,
        });
    };
    let path_steps = path
        .windows(2)
        .map(|step| (step[0], step[1]))
        .collect::<BTreeSet<_>>();
    let occurrences = all_occurrences
        .into_iter()
        .filter(|occurrence| path_steps.contains(&directed_step(occurrence)))
        .collect();
    let predecessors = path
        .windows(2)
        .map(|step| (step[1], step[0]))
        .collect::<BTreeMap<_, _>>();
    let path_targets = predecessors.keys().copied().collect();
    Ok(RepositoryTraversal {
        occurrences,
        predecessors,
        path_targets,
        visited: path.into_iter().collect(),
        target_id: Some(target.database_id),
        resume: None,
    })
}

fn path_candidate_better(candidate: &(usize, Vec<i64>), existing: &(usize, Vec<i64>)) -> bool {
    candidate.0 > existing.0
        || (candidate.0 == existing.0
            && (candidate.1.len(), &candidate.1) < (existing.1.len(), &existing.1))
}
