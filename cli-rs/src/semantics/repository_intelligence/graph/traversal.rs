struct RepositoryTraversal {
    occurrences: Vec<RepositoryEdgeOccurrence>,
    predecessors: BTreeMap<i64, i64>,
    path_targets: BTreeSet<i64>,
    visited: BTreeSet<i64>,
    target_id: Option<i64>,
    resume: Option<RepositoryTraversalResume>,
}

fn traverse_repository_graph(
    connection: &Connection,
    start: &RepositoryNode,
    target: Option<&RepositoryNode>,
    question: &str,
    direction: RepositoryDirection,
    execution: &RepositoryGraphExecution<'_>,
    resume: Option<&RepositoryTraversalResume>,
) -> Result<RepositoryTraversal> {
    let relations = if execution.request_scope.relations.is_empty() {
        vec![RepositoryRelationKind::Calls]
    } else {
        execution.request_scope.relations.clone()
    };
    let max_depth = execution
        .request_scope
        .max_depth
        .unwrap_or(execution.limits.depth)
        .min(execution.limits.depth);
    let all_occurrences = load_relation_occurrences(connection, &relations, execution.admitted)?;
    if let Some(target) = target {
        if resume.is_some() {
            return Err(invalid_repository_continuation(
                "Repository traversal continuation cannot resume an exact target path.",
            ));
        }
        return traverse_repository_path(
            connection,
            all_occurrences,
            start,
            target,
            question,
            direction,
            max_depth,
        );
    }
    let grouped = group_repository_edge_occurrences(all_occurrences);
    let mut state = RepositoryTraversalState::from_resume(
        resume,
        start.database_id,
        max_depth,
        &grouped,
        direction,
    )?;
    let mut occurrences = Vec::new();
    let mut path_targets = BTreeSet::new();
    let mut returned_relationships = 0usize;
    while state.depth < max_depth {
        while state.edge_offset < grouped.len() {
            let (identity, grouped_occurrences) = &grouped[state.edge_offset];
            let Some((current_id, next_id)) = state.current_edge(identity, direction) else {
                state.edge_offset += 1;
                continue;
            };
            if returned_relationships == execution.limits.results {
                let resume = state.resume();
                return Ok(RepositoryTraversal {
                    occurrences,
                    predecessors: state.predecessors,
                    path_targets,
                    visited: state.visited,
                    target_id: None,
                    resume: Some(resume),
                });
            }
            if state.advance_edge(current_id, next_id) {
                path_targets.insert(next_id);
            }
            occurrences.extend(grouped_occurrences.iter().cloned());
            returned_relationships += 1;
        }
        if state.next_frontier.is_empty() || state.depth + 1 >= max_depth {
            break;
        }
        state.depth += 1;
        state.edge_offset = 0;
        state.frontier = std::mem::take(&mut state.next_frontier);
    }
    Ok(RepositoryTraversal {
        occurrences,
        predecessors: state.predecessors,
        path_targets,
        visited: state.visited,
        target_id: None,
        resume: None,
    })
}

struct RepositoryTraversalState {
    depth: usize,
    edge_offset: usize,
    frontier: BTreeSet<i64>,
    next_frontier: BTreeSet<i64>,
    visited: BTreeSet<i64>,
    predecessors: BTreeMap<i64, i64>,
}

impl RepositoryTraversalState {
    fn from_resume(
        resume: Option<&RepositoryTraversalResume>,
        start_id: i64,
        max_depth: usize,
        grouped: &[(RepositoryEdgeIdentity, Vec<RepositoryEdgeOccurrence>)],
        direction: RepositoryDirection,
    ) -> Result<Self> {
        let mut state = Self {
            depth: 0,
            edge_offset: 0,
            frontier: BTreeSet::from([start_id]),
            next_frontier: BTreeSet::new(),
            visited: BTreeSet::from([start_id]),
            predecessors: BTreeMap::new(),
        };
        let Some(resume) = resume else {
            return Ok(state);
        };
        if max_depth == 0 || resume.depth >= max_depth || resume.edge_offset >= grouped.len() {
            return Err(invalid_repository_continuation(
                "Repository traversal continuation contains invalid resume state.",
            ));
        }
        while state.depth <= resume.depth {
            while state.edge_offset < grouped.len() {
                if state.depth == resume.depth && state.edge_offset == resume.edge_offset {
                    if state
                        .current_edge(&grouped[state.edge_offset].0, direction)
                        .is_some()
                    {
                        return Ok(state);
                    }
                    return Err(invalid_repository_continuation(
                        "Repository traversal continuation does not identify a resumable edge.",
                    ));
                }
                let identity = &grouped[state.edge_offset].0;
                if let Some((current_id, next_id)) = state.current_edge(identity, direction) {
                    state.advance_edge(current_id, next_id);
                } else {
                    state.edge_offset += 1;
                }
            }
            if state.next_frontier.is_empty() || state.depth + 1 >= max_depth {
                break;
            }
            state.depth += 1;
            state.edge_offset = 0;
            state.frontier = std::mem::take(&mut state.next_frontier);
        }
        Err(invalid_repository_continuation(
            "Repository traversal continuation cannot be reconstructed from this snapshot.",
        ))
    }

    fn current_edge(
        &self,
        identity: &RepositoryEdgeIdentity,
        direction: RepositoryDirection,
    ) -> Option<(i64, i64)> {
        let endpoints = match direction {
            RepositoryDirection::Outgoing => (identity.source_id, identity.target_id),
            RepositoryDirection::Incoming => (identity.target_id, identity.source_id),
        };
        self.frontier.contains(&endpoints.0).then_some(endpoints)
    }

    fn advance_edge(&mut self, current_id: i64, next_id: i64) -> bool {
        self.edge_offset += 1;
        if !self.visited.insert(next_id) {
            return false;
        }
        self.predecessors.insert(next_id, current_id);
        self.next_frontier.insert(next_id);
        true
    }

    fn resume(&self) -> RepositoryTraversalResume {
        RepositoryTraversalResume {
            depth: self.depth,
            edge_offset: self.edge_offset,
        }
    }
}

fn group_repository_edge_occurrences(
    occurrences: Vec<RepositoryEdgeOccurrence>,
) -> Vec<(RepositoryEdgeIdentity, Vec<RepositoryEdgeOccurrence>)> {
    let mut grouped = BTreeMap::<RepositoryEdgeIdentity, Vec<RepositoryEdgeOccurrence>>::new();
    for occurrence in occurrences {
        let identity = RepositoryEdgeIdentity {
            source_id: occurrence.lifted_source.unwrap_or(occurrence.source_id),
            target_id: occurrence.target_id,
            kind: occurrence.kind,
            context: occurrence.context.clone(),
            derived: occurrence.lifted_source.is_some(),
        };
        grouped.entry(identity).or_default().push(occurrence);
    }
    grouped.into_iter().collect()
}
