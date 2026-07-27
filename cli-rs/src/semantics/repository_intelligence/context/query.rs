fn context_repository_question(
    workspace_root: &WorkspaceRoot,
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
    execution_scope: &RepositoryExecutionScope,
    limits: &RepositoryLimits,
) -> Result<Value> {
    let RepositoryContextTargetSelection {
        nodes: targets,
        mut unresolved_references,
        mut ambiguous_references,
        truncated: target_selection_truncated,
    } = context_target_nodes(connection, question, execution_scope, limits.results)?;
    let sources = if scope.sources.is_empty() {
        vec![
            RepositoryContextSource::Markdown,
            RepositoryContextSource::Gradle,
            RepositoryContextSource::Schema,
            RepositoryContextSource::Workflow,
            RepositoryContextSource::Rust,
        ]
    } else {
        let mut sources = scope.sources.clone();
        sources.sort_by_key(|source| source.priority());
        sources.dedup();
        sources
    };
    let mut context_nodes = BTreeMap::<RepositoryContextSource, BTreeSet<String>>::new();
    let mut markdown_documents = BTreeMap::<String, String>::new();
    let mut candidates = Vec::new();
    if !targets.is_empty() && ambiguous_references.is_empty() {
        let mut context_paths = repository_context_paths(workspace_root, &sources)?;
        for source in sources {
            context_nodes.entry(source).or_default();
            for path in context_paths.remove(&source).unwrap_or_default() {
                let mut file = std::fs::File::open(&path.canonical_path).map_err(|error| {
                    CliError::new(
                        "REPOSITORY_CONTEXT_UNAVAILABLE",
                        format!(
                            "cannot open repository context candidate {}: {error}",
                            path.relative_path
                        ),
                    )
                })?;
                let metadata = file.metadata().map_err(|error| {
                    CliError::new(
                        "REPOSITORY_CONTEXT_UNAVAILABLE",
                        format!(
                            "cannot inspect opened repository context candidate {}: {error}",
                            path.relative_path
                        ),
                    )
                })?;
                if !same_repository_context_file(&path.metadata, &metadata) {
                    return Err(CliError::new(
                        "REPOSITORY_CONTEXT_CHANGED",
                        format!(
                            "repository context candidate {} changed after containment was proven; retry the query",
                            path.relative_path
                        ),
                    ));
                }
                let mut content = String::new();
                std::io::Read::read_to_string(&mut file, &mut content).map_err(|error| {
                    CliError::new(
                        "REPOSITORY_CONTEXT_UNAVAILABLE",
                        format!("cannot read {}: {error}", path.relative_path),
                    )
                })?;
                context_nodes
                    .entry(source)
                    .or_default()
                    .insert(path.relative_path.clone());
                for target in &targets {
                    let ownership = execution_scope
                        .ownership(target)
                        .expect("repository targets were admitted with ownership proof");
                    let candidate = match source {
                        RepositoryContextSource::Markdown => markdown_context_relation(
                            question,
                            &path.relative_path,
                            &content,
                            target,
                        ),
                        RepositoryContextSource::Gradle => gradle_context_relation(
                            question,
                            &path.relative_path,
                            &content,
                            target,
                            ownership,
                        ),
                        RepositoryContextSource::Schema => {
                            schema_context_relation(question, &path.relative_path, &content, target)
                        }
                        RepositoryContextSource::Workflow => workflow_context_relation(
                            question,
                            &path.relative_path,
                            &content,
                            target,
                            ownership,
                        ),
                        RepositoryContextSource::Rust => {
                            rust_context_relation(question, &path.relative_path, &content, target)
                        }
                    };
                    if let Some(candidate) = candidate {
                        candidates.push(candidate);
                    }
                }
                if source == RepositoryContextSource::Markdown {
                    markdown_documents.insert(path.relative_path, content);
                }
            }
        }
    }
    candidates.sort_by(|left, right| {
        left.relation
            .source_kind
            .priority()
            .cmp(&right.relation.source_kind.priority())
            .then_with(|| right.score.cmp(&left.score))
            .then_with(|| {
                (
                    &left.relation.source_path,
                    &left.relation.target_key,
                    left.relation.kind,
                )
                    .cmp(&(
                        &right.relation.source_path,
                        &right.relation.target_key,
                        right.relation.kind,
                    ))
            })
    });
    let all_relations = candidates
        .iter()
        .map(|candidate| candidate.relation.clone())
        .collect::<Vec<_>>();
    let linked_paths = all_relations
        .iter()
        .map(|relation| relation.source_path.clone())
        .collect::<BTreeSet<_>>();
    let all_linked_keys = all_relations
        .iter()
        .map(|relation| relation.target_key.as_str())
        .collect::<BTreeSet<_>>();
    let all_linked_targets = targets
        .iter()
        .filter(|target| all_linked_keys.contains(target.canonical_key.as_str()))
        .cloned()
        .collect::<Vec<_>>();
    let evidence_distribution = all_relations.iter().fold(
        BTreeMap::<&'static str, usize>::new(),
        |mut counts, relation| {
            *counts.entry(relation.evidence_class).or_default() += 1;
            counts
        },
    );
    let context_node_count = context_nodes.values().map(BTreeSet::len).sum::<usize>();
    let linked_context_node_count = linked_paths.len();
    let unresolved_reference_count = unresolved_references.len();
    let ambiguous_reference_count = ambiguous_references.len();
    let exact_reference_count =
        targets.len() + unresolved_reference_count + ambiguous_reference_count;
    let mut context_findings = context_gap_findings(
        &all_linked_targets,
        &unresolved_references,
        &context_nodes,
        &markdown_documents,
        &all_relations,
    );
    let evidence_classes = all_relations
        .iter()
        .map(|relation| relation.evidence_class)
        .collect::<BTreeSet<_>>();
    let nested_ambiguity_truncated = ambiguous_references
        .iter()
        .any(|ambiguity| ambiguity.truncated);
    let aggregate_record_count = candidates.len()
        + context_findings.len()
        + unresolved_reference_count
        + ambiguous_reference_count;
    let truncated = target_selection_truncated
        || nested_ambiguity_truncated
        || aggregate_record_count > limits.results;
    let mut remaining = limits.results;
    ambiguous_references.truncate(remaining);
    remaining -= ambiguous_references.len();
    let context_relations = candidates
        .into_iter()
        .take(remaining)
        .map(|candidate| candidate.relation)
        .collect::<Vec<_>>();
    remaining -= context_relations.len();
    unresolved_references.truncate(remaining);
    remaining -= unresolved_references.len();
    context_findings.truncate(remaining);
    let result_linked_keys = context_relations
        .iter()
        .map(|relation| relation.target_key.as_str())
        .collect::<BTreeSet<_>>();
    let result_targets = targets
        .into_iter()
        .filter(|target| result_linked_keys.contains(target.canonical_key.as_str()))
        .collect::<Vec<_>>();
    Ok(json!({
        "answered": !context_relations.is_empty(),
        "ambiguous": !ambiguous_references.is_empty(),
        "contextRelations": context_relations,
        "nodes": result_targets,
        "evidenceClasses": evidence_classes,
        "relationVocabulary": repository_context_relation_vocabulary(),
        "contextMetrics": {
            "contextNodeCount": context_node_count,
            "linkedContextNodeCount": linked_context_node_count,
            "exactLinkRate": ratio(linked_context_node_count, context_node_count),
            "orphanRate": 1.0 - ratio(linked_context_node_count, context_node_count),
            "unresolvedReferenceCount": unresolved_reference_count,
            "unresolvedReferenceRate": ratio(unresolved_reference_count, exact_reference_count),
            "ambiguousReferenceCount": ambiguous_reference_count,
            "ambiguousReferenceRate": ratio(ambiguous_reference_count, exact_reference_count),
            "evidenceDistribution": evidence_distribution,
            "bySourceType": context_nodes
                .iter()
                .map(|(source, paths)| (format!("{source:?}").to_ascii_lowercase(), paths.len()))
                .collect::<BTreeMap<_, _>>()
        },
        "unresolvedReferences": unresolved_references,
        "ambiguousReferences": ambiguous_references,
        "contextFindings": context_findings,
        "identityCollisions": 0,
        "truncated": truncated
    }))
}
