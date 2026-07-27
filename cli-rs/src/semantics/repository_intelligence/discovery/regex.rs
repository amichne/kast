fn repository_discovery_documents(
    connection: &Connection,
    execution_scope: &RepositoryExecutionScope,
    natural_language_question: Option<&str>,
) -> Result<(Vec<RepositoryNode>, Vec<SymbolDiscoveryDocument>)> {
    if !semantic_graph_tables_exist(connection)? {
        return Ok((Vec::new(), Vec::new()));
    }
    let neighbors = if natural_language_question.is_some() {
        load_discovery_neighbor_tokens(connection)?
    } else {
        BTreeMap::new()
    };
    let nodes = execution_scope.admit_nodes(load_repository_node(connection, "1 = ?1", 1i64)?);
    let returned_by = returning_callable_index(&nodes);
    let compact_question = natural_language_question
        .map(compact_search_text)
        .unwrap_or_default();
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
                    value: natural_language_question
                        .and(node.owner_name.as_ref())
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
                    name: "returningCallables",
                    value: returning_callables(&returned_by, node),
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
    Ok((nodes, documents))
}

fn rank_repository_regex_candidates(
    connection: &Connection,
    source: &str,
    compiled: &regex::Regex,
    execution_scope: &RepositoryExecutionScope,
) -> Result<Vec<RepositoryCandidate>> {
    let (nodes, documents) =
        repository_discovery_documents(connection, execution_scope, None)?;
    let mut nodes_by_identity = nodes
        .into_iter()
        .map(|node| (node.canonical_key.clone(), node))
        .collect::<BTreeMap<_, _>>();
    let mut candidates = documents
        .into_iter()
        .filter_map(|document| {
            let reason = repository_regex_reason(source, compiled, &document)?;
            let node = nodes_by_identity.remove(&document.identity)?;
            Some(RepositoryCandidate {
                rank: 0,
                match_score: 1,
                match_reasons: vec![reason],
                node,
            })
        })
        .collect::<Vec<_>>();
    candidates.sort_by(|left, right| left.node.canonical_key.cmp(&right.node.canonical_key));
    for (index, candidate) in candidates.iter_mut().enumerate() {
        candidate.rank = index + 1;
    }
    Ok(candidates)
}

fn repository_regex_reason(
    source: &str,
    compiled: &regex::Regex,
    document: &SymbolDiscoveryDocument,
) -> Option<RepositoryMatchReason> {
    let field = if compiled.is_match(&document.identity) {
        Some("canonicalKey")
    } else {
        document
            .fields
            .iter()
            .filter(|field| field.name != "exactMember" && !field.value.is_empty())
            .find(|field| compiled.is_match(&field.value))
            .map(|field| field.name)
    }?;
    Some(RepositoryMatchReason {
        field,
        terms: vec![source.to_string()],
        score: 1,
    })
}
