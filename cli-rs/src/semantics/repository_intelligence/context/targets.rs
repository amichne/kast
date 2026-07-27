fn context_target_nodes(
    connection: &Connection,
    question: &str,
    execution_scope: &RepositoryExecutionScope,
    result_limit: usize,
) -> Result<(
    Vec<RepositoryNode>,
    Vec<String>,
    Vec<RepositoryContextAmbiguity>,
)> {
    let ignored = ["ADR", "CI", "CALLS"];
    let names = explicit_repository_names(question)
        .into_iter()
        .filter(|name| !ignored.contains(&name.as_str()))
        .collect::<Vec<_>>();
    let has_explicit_names = !names.is_empty();
    let mut targets = Vec::new();
    let mut unresolved = Vec::new();
    let mut ambiguous = Vec::new();
    for name in names {
        let candidates = execution_scope.admit_nodes(load_repository_node(
            connection,
            "symbol.name = ?1",
            &name,
        )?);
        let scores = candidates
            .iter()
            .map(|candidate| repository_node_score(candidate, question))
            .collect::<Vec<_>>();
        let best = scores.iter().copied().max().unwrap_or_default();
        let mut selected = candidates
            .into_iter()
            .zip(scores)
            .filter_map(|(candidate, score)| (score == best).then_some(candidate))
            .collect::<Vec<_>>();
        selected.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
        match selected.len() {
            0 => unresolved.push(name),
            1 => targets.push(selected.remove(0)),
            _ => {
                let truncated = selected.len() > result_limit;
                selected.truncate(result_limit);
                ambiguous.push(RepositoryContextAmbiguity {
                    reference: name,
                    candidates: selected,
                    truncated,
                });
            }
        }
    }
    if !has_explicit_names {
        targets.extend(
            rank_repository_candidates(connection, question, execution_scope)?
                .into_iter()
                .filter(|candidate| {
                    matches!(
                        candidate.node.kind.as_str(),
                        "CLASS" | "ENUM_CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS"
                    )
                })
                .map(|candidate| candidate.node),
        );
    }
    targets.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    targets.dedup_by(|left, right| left.canonical_key == right.canonical_key);
    Ok((targets, unresolved, ambiguous))
}
