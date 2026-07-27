fn resolve_repository_question(
    connection: &Connection,
    question: &str,
    execution_scope: &RepositoryExecutionScope,
    limit: usize,
    canonical_key: Option<&str>,
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
        rank_repository_candidates(connection, question, execution_scope)?
    };
    let tied = candidates
        .first()
        .zip(candidates.get(1))
        .is_some_and(|(first, second)| first.match_score == second.match_score);
    let explicit_nonselection = question
        .to_ascii_lowercase()
        .contains("without choosing between");
    let bare_name_ambiguity = bare_resolution_name(question).is_some() && candidates.len() > 1;
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
) -> Result<Vec<RepositoryCandidate>> {
    if !semantic_graph_tables_exist(connection)? {
        return Ok(Vec::new());
    }
    let neighbors = load_discovery_neighbor_tokens(connection)?;
    let nodes = execution_scope.admit_nodes(load_repository_node(connection, "1 = ?1", 1i64)?);
    let query_terms = discovery_query_terms(question);
    let compact_question = compact_search_text(question);
    let documents = nodes
        .iter()
        .map(|node| SymbolDiscoveryDocument {
            identity: node.canonical_key.clone(),
            simple_name: node.name.clone(),
            sort_key: node.canonical_key.clone(),
            fields: vec![
                SymbolDiscoveryField {
                    name: "name",
                    value: node.name.clone(),
                },
                SymbolDiscoveryField {
                    name: "exactMember",
                    value: node
                        .owner_name
                        .as_ref()
                        .map(|owner| format!("{owner}.{}", node.name))
                        .filter(|member| {
                            compact_question.contains(&compact_search_text(member))
                        })
                        .unwrap_or_default(),
                },
                SymbolDiscoveryField {
                    name: "qualifiedName",
                    value: [
                        node.owner_name.as_deref().unwrap_or_default(),
                        node.fq_name.as_deref().unwrap_or_default(),
                    ]
                    .join(" "),
                },
                SymbolDiscoveryField {
                    name: "signature",
                    value: node.signature.clone().unwrap_or_default(),
                },
                SymbolDiscoveryField {
                    name: "parameterTypes",
                    value: node.parameter_types.join(" "),
                },
                SymbolDiscoveryField {
                    name: "receiverType",
                    value: node.receiver_type.clone().unwrap_or_default(),
                },
                SymbolDiscoveryField {
                    name: "returnType",
                    value: node.return_type.clone().unwrap_or_default(),
                },
                SymbolDiscoveryField {
                    name: "annotations",
                    value: node.annotations.join(" "),
                },
                SymbolDiscoveryField {
                    name: "scope",
                    value: format!(
                        "{} {} {}",
                        node.path,
                        node.gradle_projects.join(" "),
                        node.source_sets.join(" ")
                    ),
                },
                SymbolDiscoveryField {
                    name: "declarationKind",
                    value: node.kind.clone(),
                },
            ],
            graph_terms: neighbors
                .get(&node.database_id)
                .cloned()
                .unwrap_or_default(),
        })
        .collect();
    let preferred_names = explicit_repository_names(question);
    let ranked = rank_symbol_discovery(
        repository_resolution_name(question).as_deref(),
        &preferred_names,
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
            nodes_by_identity
                .remove(&ranked.identity)
                .map(|node| RepositoryCandidate {
                    rank: ranked.rank,
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
        })
        .collect())
}

fn load_discovery_neighbor_tokens(
    connection: &Connection,
) -> Result<BTreeMap<i64, BTreeSet<String>>> {
    let mut statement = connection
        .prepare(
            "SELECT edge.source_id, target.name
             FROM semantic_edge_occurrences edge
             JOIN semantic_symbols target ON target.id = edge.target_id
             UNION ALL
             SELECT edge.target_id, source.name
             FROM semantic_edge_occurrences edge
             JOIN semantic_symbols source ON source.id = edge.source_id
             UNION ALL
             SELECT child.owner_id, child.name
             FROM semantic_symbols child
             WHERE child.owner_id IS NOT NULL
             ORDER BY 1, 2",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let mut neighbors = BTreeMap::<i64, BTreeSet<String>>::new();
    for row in rows {
        let (id, name) =
            row.map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        neighbors
            .entry(id)
            .or_default()
            .extend(discovery_lexical_tokens(&name));
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

fn repository_resolution_name(question: &str) -> Option<String> {
    if let Some(member) = dotted_member_name(question) {
        return Some(member);
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
                    "a" | "an"
                        | "declaration"
                        | "exact"
                        | "exactly"
                        | "function"
                        | "helper"
                        | "kotlin"
                        | "method"
                        | "model"
                        | "the"
                        | "type"
                )
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

fn explicit_repository_names(question: &str) -> BTreeSet<String> {
    let words = question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    let mut names = BTreeSet::new();
    if words
        .first()
        .is_some_and(|word| word.eq_ignore_ascii_case("resolve"))
        && let Some(name) = words.get(1)
    {
        names.insert((*name).to_string());
    }
    if let Some(member) = dotted_member_name(question) {
        names.insert(member);
    }
    names.extend(words.windows(2).filter_map(|pair| {
        matches!(
            pair[1].to_ascii_lowercase().as_str(),
            "function" | "helper" | "method"
        )
        .then_some(pair[0])
        .filter(|name| !matches!(name.to_ascii_lowercase().as_str(), "a" | "the" | "which"))
        .map(|name| (*name).to_string())
    }));
    names.extend(words.into_iter().filter_map(|word| {
        let uppercase = word
            .chars()
            .filter(|character| character.is_uppercase())
            .count();
        (uppercase >= 2 || word.contains('_')).then(|| word.to_string())
    }));
    names
}
