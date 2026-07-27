fn resolve_repository_question(
    connection: &Connection,
    question: &RepositoryDiscoveryQuery,
    execution_scope: &RepositoryExecutionScope,
    limit: usize,
    canonical_key: Option<&str>,
    labels: Option<&CompilerIdentityBoundLabels>,
) -> Result<Value> {
    let mut candidates = if let Some(canonical_key) = canonical_key {
        execution_scope
            .admit_nodes(load_repository_node(
                connection,
                "symbol.stable_key = ?1",
                canonical_key,
            )?)
            .into_iter()
            .map(|node| RepositoryCandidate {
                rank: 1,
                match_score: usize::MAX,
                match_reasons: vec![RepositoryMatchReason {
                    field: "canonicalKey",
                    terms: vec![canonical_key.to_string()],
                    score: usize::MAX,
                }],
                node,
            })
            .collect()
    } else {
        match question {
            RepositoryDiscoveryQuery::NaturalLanguage(question) => {
                rank_repository_candidates(connection, question, execution_scope, labels)?
            }
            RepositoryDiscoveryQuery::Regex { source, compiled } => {
                rank_repository_regex_candidates(connection, source, compiled, execution_scope)?
            }
        }
    };
    let tied = candidates
        .first()
        .zip(candidates.get(1))
        .is_some_and(|(first, second)| first.match_score == second.match_score);
    let explicit_nonselection = question.natural_language().is_some_and(|question| {
        question
            .to_ascii_lowercase()
            .contains("without choosing between")
    });
    let bare_name_ambiguity = question
        .natural_language()
        .is_some_and(|question| bare_resolution_name(question).is_some())
        && candidates.len() > 1;
    let outcome = match candidates.first() {
        None => RepositoryResolutionOutcome::Empty,
        Some(_)
            if canonical_key.is_none()
                && (tied || explicit_nonselection || bare_name_ambiguity) =>
        {
            RepositoryResolutionOutcome::Ambiguous
        }
        Some(candidate) => RepositoryResolutionOutcome::Answered(Box::new(candidate.node.clone())),
    };
    let candidate_limit = limit.min(10);
    let truncated = candidates.len() > candidate_limit;
    candidates.truncate(candidate_limit);
    Ok(match outcome {
        RepositoryResolutionOutcome::Empty => json!({
            "answered": false,
            "ambiguous": false,
            "nodes": [],
            "candidates": candidates,
            "identityCollisions": 0,
            "truncated": truncated
        }),
        RepositoryResolutionOutcome::Ambiguous => json!({
            "answered": false,
            "ambiguous": true,
            "nodes": [],
            "candidates": candidates,
            "identityCollisions": 0,
            "truncated": truncated
        }),
        RepositoryResolutionOutcome::Answered(selected) => {
            let selected_identity = selected.canonical_key.clone();
            json!({
                "answered": true,
                "ambiguous": false,
                "selectedIdentity": selected_identity,
                "nodes": [*selected],
                "candidates": candidates,
                "identityCollisions": 0,
                "truncated": truncated
            })
        }
    })
}

fn rank_repository_candidates(
    connection: &Connection,
    question: &str,
    execution_scope: &RepositoryExecutionScope,
    labels: Option<&CompilerIdentityBoundLabels>,
) -> Result<Vec<RepositoryCandidate>> {
    let (nodes, mut documents) =
        repository_discovery_documents(connection, execution_scope, Some(question))?;
    if let Some(labels) = labels {
        for document in &mut documents {
            if let Some(label) = labels.for_identity(&document.identity) {
                document.fields.push(SymbolDiscoveryField {
                    name: "precomputedLabel",
                    value: label.to_string(),
                });
            }
        }
    }
    let query_terms = discovery_query_terms(question);
    let discovery_intent = crate::symbol_query::SymbolDiscoveryIntent::parse(question);
    let preferred_names = explicit_repository_names(question);
    let resolution_name = repository_resolution_name(question, discovery_intent);
    let ranked = rank_symbol_discovery(
        labels.is_none().then_some(resolution_name.as_deref()).flatten(),
        &preferred_names,
        discovery_intent,
        &query_terms.iter().cloned().collect::<Vec<_>>(),
        documents,
    );
    let mut nodes_by_identity = nodes
        .into_iter()
        .map(|node| (node.canonical_key.clone(), node))
        .collect::<BTreeMap<_, _>>();
    Ok(ranked
        .into_iter()
        .filter_map(|ranked| {
            debug_assert!(ranked.rank > 0);
            let node = nodes_by_identity.remove(&ranked.identity)?;
            let baseline_match = resolution_name
                .as_deref()
                .is_none_or(|name| node.name.eq_ignore_ascii_case(name));
            let label_match = ranked
                .reasons
                .iter()
                .any(|reason| reason.field == "precomputedLabel");
            (labels.is_none() || baseline_match || label_match).then_some((ranked, node))
        })
        .enumerate()
        .map(|(index, (ranked, node))| RepositoryCandidate {
            rank: index + 1,
            match_score: ranked.score,
            match_reasons: ranked
                .reasons
                .into_iter()
                .map(|reason| RepositoryMatchReason {
                    field: reason.field,
                    terms: reason.terms,
                    score: reason.score,
                })
                .collect(),
            node,
        })
        .collect())
}

fn load_discovery_neighbor_tokens(
    connection: &Connection,
    nodes: &[RepositoryNode],
) -> Result<BTreeMap<i64, BTreeSet<String>>> {
    let admitted = nodes
        .iter()
        .map(|node| (node.database_id, node.name.as_str()))
        .collect::<BTreeMap<_, _>>();
    let mut statement = connection
        .prepare(
            "SELECT edge.source_id, edge.target_id
             FROM semantic_edge_occurrences edge
             UNION ALL
             SELECT edge.target_id, edge.source_id
             FROM semantic_edge_occurrences edge
             UNION ALL
             SELECT child.owner_id, child.id
             FROM semantic_symbols child
             WHERE child.owner_id IS NOT NULL
             ORDER BY 1, 2",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?))
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let mut neighbors = BTreeMap::<i64, BTreeSet<String>>::new();
    for row in rows {
        let (id, neighbor_id) =
            row.map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        let (Some(_), Some(name)) = (admitted.get(&id), admitted.get(&neighbor_id)) else {
            continue;
        };
        neighbors
            .entry(id)
            .or_default()
            .extend(discovery_lexical_tokens(name));
    }
    Ok(neighbors)
}

fn discovery_query_terms(question: &str) -> BTreeSet<String> {
    let mut terms = discovery_lexical_tokens(question);
    for stopword in [
        "and",
        "are",
        "declaration",
        "does",
        "exact",
        "exactly",
        "find",
        "from",
        "in",
        "kotlin",
        "of",
        "one",
        "resolve",
        "that",
        "the",
        "to",
        "what",
        "which",
        "with",
        "without",
    ] {
        terms.remove(stopword);
    }
    for term in terms.clone() {
        let expansions: &[&str] = match term.as_str() {
            "hash" | "hashe" | "hashing" => &["sha256", "digest", "fingerprint"],
            "persist" | "persisting" => &["replace", "store", "write"],
            "relationship" => &["relation", "edge"],
            "end" | "endpoint" => &["source", "target"],
            "build" | "construct" | "create" => &["build", "construct", "create"],
            "overload" => &["parameter", "receiver", "signature"],
            _ => &[],
        };
        terms.extend(expansions.iter().map(|value| (*value).to_string()));
    }
    terms
}

fn discovery_lexical_tokens(raw: &str) -> BTreeSet<String> {
    let mut tokens = search_tokens(raw);
    let initial = tokens.clone();
    for token in initial {
        if token.len() > 4 && token.ends_with('s') {
            tokens.insert(token[..token.len() - 1].to_string());
        }
        if token.len() > 6 && token.ends_with("ing") {
            tokens.insert(token[..token.len() - 3].to_string());
        }
    }
    tokens
}

fn bare_resolution_name(question: &str) -> Option<String> {
    let words = question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    (words.len() == 2 && words[0].eq_ignore_ascii_case("resolve"))
        .then(|| words[1].to_string())
}

fn repository_resolution_name(
    question: &str,
    intent: crate::symbol_query::SymbolDiscoveryIntent,
) -> Option<String> {
    if let Some(member) = dotted_member_name(question) {
        return Some(member);
    }
    if intent.is_mixed() {
        return None;
    }
    let words = question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    if words
        .first()
        .is_some_and(|word| word.eq_ignore_ascii_case("resolve"))
    {
        let candidates = words
            .iter()
            .skip(1)
            .copied()
            .filter(|word| {
                !matches!(
                    word.to_ascii_lowercase().as_str(),
                    "a" | "an" | "declaration" | "exact" | "exactly" | "kotlin" | "the"
                ) && crate::symbol_query::SymbolDiscoveryFamily::from_word(word).is_none()
            })
            .collect::<Vec<_>>();
        return candidates
            .iter()
            .find(|word| {
                word.contains('_')
                    || word
                        .chars()
                        .filter(|character| character.is_uppercase())
                        .count()
                        >= 2
            })
            .or_else(|| candidates.first())
            .map(|word| (*word).to_string());
    }
    likely_declaration_term(question)
        .filter(|name| name.chars().any(char::is_lowercase))
        .map(str::to_string)
}

include!("query_names.rs");
include!("return_evidence.rs");
