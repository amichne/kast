fn load_relation_occurrences(
    connection: &Connection,
    relations: &[RepositoryRelationKind],
    execution_scope: &RepositoryExecutionScope,
) -> Result<Vec<RepositoryEdgeOccurrence>> {
    let relation_names = relations
        .iter()
        .map(|relation| format!("'{}'", relation.canonical()))
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!(
        "SELECT edge.id,
                edge.source_id,
                edge.target_id,
                edge.kind,
                edge.context,
                edge.start_offset,
                edge.end_offset,
                edge.line,
                source.kind,
                source.stable_key,
                source.owner_id,
                source_owner.kind,
                occurrence_file.path,
                COALESCE(source_owner_file.path, source_file.path),
                target_file.path
         FROM semantic_edge_occurrences edge
         JOIN semantic_symbols source ON source.id = edge.source_id
         JOIN semantic_files source_file ON source_file.id = source.file_id
         LEFT JOIN semantic_symbols source_owner ON source_owner.id = source.owner_id
         LEFT JOIN semantic_files source_owner_file ON source_owner_file.id = source_owner.file_id
         JOIN semantic_symbols target ON target.id = edge.target_id
         JOIN semantic_files target_file ON target_file.id = target.file_id
         JOIN semantic_files occurrence_file ON occurrence_file.id = edge.source_file_id
         WHERE edge.kind IN ({relation_names})
         ORDER BY edge.source_id, edge.target_id, edge.kind, edge.context, edge.id"
    );
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            let occurrence_path = row.get::<_, String>(12)?;
            let source_path = row.get::<_, String>(13)?;
            let target_path = row.get::<_, String>(14)?;
            if !execution_scope.admits_path(&occurrence_path)
                || !execution_scope.admits_path(&source_path)
                || !execution_scope.admits_path(&target_path)
            {
                return Ok(None);
            }
            let kind = parse_relation_kind(&row.get::<_, String>(3)?)?;
            let source_kind = row.get::<_, String>(8)?;
            let source_owner_id = row.get::<_, Option<i64>>(10)?;
            let source_owner_kind = row.get::<_, Option<String>>(11)?;
            let lifted_source = source_owner_id.filter(|_| {
                !is_callable_kind(&source_kind)
                    && source_owner_kind.as_deref().is_some_and(is_callable_kind)
            });
            Ok(Some(RepositoryEdgeOccurrence {
                source_id: row.get(1)?,
                target_id: row.get(2)?,
                kind,
                context: row.get(4)?,
                occurrence: RepositoryOccurrence {
                    id: row.get(0)?,
                    path: occurrence_path,
                    start_offset: row.get(5)?,
                    end_offset: row.get(6)?,
                    line: row.get(7)?,
                },
                lifted_source,
                source_local_key: lifted_source.map(|_| row.get(9)).transpose()?,
            }))
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map(|rows| rows.into_iter().flatten().collect())
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))
}

fn parse_relation_kind(raw: &str) -> rusqlite::Result<RepositoryRelationKind> {
    match raw {
        "CALLS" => Ok(RepositoryRelationKind::Calls),
        "CASE_OF" => Ok(RepositoryRelationKind::CaseOf),
        "CONTAINS" => Ok(RepositoryRelationKind::Contains),
        "DELEGATES" => Ok(RepositoryRelationKind::Delegates),
        "EXPECT_ACTUAL" => Ok(RepositoryRelationKind::ExpectActual),
        "IMPLEMENTS" => Ok(RepositoryRelationKind::Implements),
        "INHERITS" => Ok(RepositoryRelationKind::Inherits),
        "METHOD" => Ok(RepositoryRelationKind::Method),
        "OVERRIDES" => Ok(RepositoryRelationKind::Overrides),
        "REFERENCES" => Ok(RepositoryRelationKind::References),
        "SEALED_MEMBER" => Ok(RepositoryRelationKind::SealedMember),
        _ => Err(rusqlite::Error::FromSqlConversionFailure(
            3,
            Type::Text,
            Box::new(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("unknown semantic relation kind `{raw}`"),
            )),
        )),
    }
}

fn is_callable_kind(kind: &str) -> bool {
    matches!(
        kind,
        "FUNCTION" | "MEMBER_FUNCTION" | "CONSTRUCTOR" | "GETTER" | "SETTER"
    )
}

fn repository_edges(
    connection: &Connection,
    occurrences: &[RepositoryEdgeOccurrence],
    direction: RepositoryDirection,
    evidence_limit: usize,
    continuation_context: Option<&RepositoryContinuationContext>,
    evidence_resume: Option<&RepositoryEvidenceResume>,
    node_cache: &mut RepositoryNodeCache<'_>,
) -> Result<Vec<RepositoryEdge>> {
    let evidence_identity = evidence_resume
        .map(|continuation| {
            let source = load_repository_node(
                connection,
                "symbol.stable_key = ?1",
                &continuation.source_key,
            )?
            .into_iter()
            .find_map(|node| node_cache.execution_scope.admit_node(node));
            let target = load_repository_node(
                connection,
                "symbol.stable_key = ?1",
                &continuation.target_key,
            )?
            .into_iter()
            .find_map(|node| node_cache.execution_scope.admit_node(node));
            let (Some(source), Some(target)) = (source, target) else {
                return Err(invalid_repository_continuation(
                    "Repository evidence continuation resume identity is unavailable.",
                ));
            };
            let identity = RepositoryEdgeIdentity {
                source_id: source.database_id,
                target_id: target.database_id,
                kind: continuation.kind,
                context: continuation.context.clone(),
                derived: continuation.derived,
            };
            node_cache.nodes.insert(source.database_id, source);
            node_cache.nodes.insert(target.database_id, target);
            Ok(identity)
        })
        .transpose()?;
    let mut grouped = BTreeMap::<RepositoryEdgeIdentity, Vec<&RepositoryEdgeOccurrence>>::new();
    for occurrence in occurrences {
        let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        grouped
            .entry(RepositoryEdgeIdentity {
                source_id,
                target_id: occurrence.target_id,
                kind: occurrence.kind,
                context: occurrence.context.clone(),
                derived: occurrence.lifted_source.is_some(),
            })
            .or_default()
            .push(occurrence);
    }
    let mut edges = Vec::new();
    for (identity, mut grouped_occurrences) in grouped {
        if evidence_identity
            .as_ref()
            .is_some_and(|expected| expected != &identity)
        {
            continue;
        }
        grouped_occurrences.sort_by_key(|occurrence| occurrence.occurrence.id);
        let source = cached_repository_node(connection, identity.source_id, node_cache)?;
        let target = cached_repository_node(connection, identity.target_id, node_cache)?;
        let occurrence_count = grouped_occurrences.len();
        let after_occurrence_id = evidence_resume
            .map(|continuation| continuation.after_occurrence_id)
            .unwrap_or(i64::MIN);
        let remaining = grouped_occurrences
            .iter()
            .copied()
            .filter(|occurrence| occurrence.occurrence.id > after_occurrence_id)
            .collect::<Vec<_>>();
        if evidence_resume.is_some() && remaining.is_empty() {
            continue;
        }
        let page = remaining
            .iter()
            .take(evidence_limit)
            .map(|occurrence| occurrence.occurrence.clone())
            .collect::<Vec<_>>();
        let evidence_truncated = remaining.len() > page.len();
        let next_continuation = match (evidence_truncated, continuation_context) {
            (true, Some(continuation_context)) => Some(issue_repository_continuation(
                continuation_context,
                RepositoryEvidenceResume {
                    source_key: source.canonical_key.clone(),
                    target_key: target.canonical_key.clone(),
                    kind: identity.kind,
                    context: identity.context.clone(),
                    derived: identity.derived,
                    after_occurrence_id: page
                        .last()
                        .expect("truncated evidence page is non-empty")
                        .id,
                },
            )?),
            (false, _) | (true, None) => None,
        };
        let derivation = identity.derived.then(|| RepositoryDerivation {
            rule: "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER",
            source_local_key: grouped_occurrences[0]
                .source_local_key
                .clone()
                .expect("derived edge has local source identity"),
            supporting_relations: ["CONTAINS", identity.kind.canonical()],
        });
        edges.push(RepositoryEdge {
            source_key: source.canonical_key.clone(),
            source_name: source.name.clone(),
            source_owner_name: source.owner_name.clone(),
            target_key: target.canonical_key.clone(),
            target_name: target.name.clone(),
            target_owner_name: target.owner_name.clone(),
            kind: identity.kind,
            direction,
            context: identity.context,
            occurrence_count,
            occurrences: page,
            evidence_class: "compiler",
            derivation,
            evidence_truncated,
            evidence_continuation: next_continuation,
        });
    }
    edges.sort_by(|left, right| {
        (&left.source_key, &left.target_key, left.kind, &left.context).cmp(&(
            &right.source_key,
            &right.target_key,
            right.kind,
            &right.context,
        ))
    });
    if evidence_resume.is_some() && edges.is_empty() {
        return Err(invalid_repository_continuation(
            "Repository evidence continuation resume identity is unavailable.",
        ));
    }
    Ok(edges)
}

struct RepositoryPathProjection<'a> {
    start_id: i64,
    predecessors: &'a BTreeMap<i64, i64>,
    path_targets: &'a BTreeSet<i64>,
    relations: &'a [RepositoryRelationKind],
    direction: RepositoryDirection,
    limit: usize,
}

fn repository_paths(
    connection: &Connection,
    projection: &RepositoryPathProjection<'_>,
    node_cache: &mut RepositoryNodeCache<'_>,
) -> Result<Vec<RepositoryPath>> {
    let mut paths = Vec::new();
    for target_id in projection
        .path_targets
        .iter()
        .copied()
        .take(projection.limit)
    {
        let mut ids = vec![target_id];
        let mut current = target_id;
        while let Some(previous) = projection.predecessors.get(&current).copied() {
            ids.push(previous);
            current = previous;
            if current == projection.start_id {
                break;
            }
        }
        if ids.last().copied() != Some(projection.start_id) {
            continue;
        }
        ids.reverse();
        let nodes = ids
            .into_iter()
            .map(|id| cached_repository_node(connection, id, node_cache))
            .collect::<Result<Vec<_>>>()?;
        paths.push(RepositoryPath {
            direction: projection.direction,
            relation_kinds: projection.relations.to_vec(),
            nodes,
        });
    }
    paths.sort_by(|left, right| {
        let left_keys = left
            .nodes
            .iter()
            .map(|node| node.canonical_key.as_str())
            .collect::<Vec<_>>();
        let right_keys = right
            .nodes
            .iter()
            .map(|node| node.canonical_key.as_str())
            .collect::<Vec<_>>();
        left_keys.cmp(&right_keys)
    });
    Ok(paths)
}

fn cached_repository_node(
    connection: &Connection,
    id: i64,
    cache: &mut RepositoryNodeCache<'_>,
) -> Result<RepositoryNode> {
    if let Some(node) = cache.nodes.get(&id) {
        return Ok(node.clone());
    }
    let node = load_repository_node(connection, "symbol.id = ?1", id)?
        .into_iter()
        .next()
        .and_then(|node| cache.execution_scope.admit_node(node))
        .ok_or_else(|| {
            CliError::new(
                "REPOSITORY_INDEX_INVALID",
                format!("semantic edge references missing or unadmitted symbol id {id}"),
            )
        })?;
    cache.nodes.insert(id, node.clone());
    Ok(node)
}
