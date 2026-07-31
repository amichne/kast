fn load_reference_topology_snapshot(workspace_root: &Path) -> Result<ReferenceTopologySnapshot> {
    let database = crate::config::workspace_database_path(workspace_root)?;
    let connection = rusqlite::Connection::open_with_flags(
        &database,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY
            | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX
            | rusqlite::OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(derived_topology_database_error)?;
    crate::source_index_db::configure_read_connection(&connection)
        .map_err(derived_topology_database_error)?;
    crate::source_index_db::enable_query_only(&connection)
        .map_err(derived_topology_database_error)?;
    let data_version = derived_topology_data_version(&connection)?;
    connection
        .execute_batch("BEGIN")
        .map_err(derived_topology_query_error)?;
    let result = (|| {
        let generation = derived_topology_generation(&connection)?;
        let (mut qualification, mut coverage) = reference_coverage(&connection)?;
        let nodes = reference_nodes(&connection)?;
        let edges = reference_edges(&connection)?;
        coverage.external_targets = nodes.iter().filter(|node| node.kind == "EXTERNAL").count();
        let unattributed_source_edges: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM symbol_references WHERE source_fq_id IS NULL",
                [],
                |row| row.get(0),
            )
            .map_err(derived_topology_query_error)?;
        coverage.unattributed_source_edges =
            usize::try_from(unattributed_source_edges).map_err(|_| {
                CliError::new(
                    "DERIVED_TOPOLOGY_QUERY_FAILED",
                    "The unattributed reference count is negative.",
                )
            })?;
        if coverage.unattributed_source_edges > 0 {
            qualification = ReferenceQualification::Qualified;
            coverage
                .limitations
                .push("UNATTRIBUTED_REFERENCE_SOURCE".to_string());
            coverage.limitations.sort();
            coverage.limitations.dedup();
        }
        if derived_topology_generation(&connection)? != generation {
            return Err(CliError::new(
                "DERIVED_TOPOLOGY_GENERATION_CHANGED",
                "The source-index generation changed while the artifact input was read.",
            ));
        }
        let digest_input = serde_json::to_vec(&json!({
            "sourceLane": DerivedSourceLane::ReferenceDerived,
            "generation": generation,
            "qualification": qualification,
            "coverage": coverage,
            "nodes": nodes,
            "edges": edges,
            "algorithmVersion": DERIVED_TOPOLOGY_ALGORITHM_VERSION,
            "resolution": DERIVED_TOPOLOGY_RESOLUTION,
            "weighting": DerivedWeighting::Log1pOccurrenceCount,
        }))?;
        Ok(ReferenceTopologySnapshot {
            generation,
            qualification,
            coverage,
            nodes,
            edges,
            input_digest: crate::manifest::sha256_bytes(&digest_input),
        })
    })();
    connection
        .execute_batch(if result.is_ok() { "COMMIT" } else { "ROLLBACK" })
        .map_err(derived_topology_query_error)?;
    if result.is_ok() && derived_topology_data_version(&connection)? != data_version {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_GENERATION_CHANGED",
            "The source index changed while the artifact input was read.",
        ));
    }
    result
}

fn derived_topology_data_version(connection: &rusqlite::Connection) -> Result<i64> {
    connection
        .query_row("PRAGMA main.data_version", [], |row| row.get(0))
        .map_err(derived_topology_query_error)
}

fn derived_topology_generation(connection: &rusqlite::Connection) -> Result<u64> {
    let (version, generation): (i64, i64) = connection
        .query_row(
            "SELECT version, generation FROM schema_version LIMIT 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(derived_topology_query_error)?;
    if version != crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_SCHEMA_MISMATCH",
            format!(
                "source-index.db uses schema {version}; this Kast build requires {}.",
                crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION
            ),
        ));
    }
    u64::try_from(generation).map_err(|_| {
        CliError::new(
            "DERIVED_TOPOLOGY_SCHEMA_INVALID",
            "The source-index generation is negative.",
        )
    })
}

fn reference_coverage(
    connection: &rusqlite::Connection,
) -> Result<(ReferenceQualification, ReferenceCoverage)> {
    let unknown_pending_updates: i64 = connection
        .query_row(
            "SELECT COUNT(*)
             FROM pending_updates pending
             LEFT JOIN file_manifest manifest
               ON manifest.prefix_id = pending.prefix_id
              AND manifest.filename = pending.filename
             WHERE pending.applied = 0
               AND pending.filename LIKE '%.kt'
               AND manifest.filename IS NULL",
            [],
            |row| row.get(0),
        )
        .map_err(derived_topology_query_error)?;
    if unknown_pending_updates != 0 {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_REFERENCE_INCOMPLETE",
            "The reference index has unapplied Kotlin updates without persisted coverage.",
        ));
    }
    let mut statement = connection
        .prepare(
            "SELECT manifest.content_hash, manifest.desired_relationships_version,
                    outcomes.content_hash, outcomes.stage_version,
                    outcomes.outcome_status, outcomes.limitations_json,
                    outcomes.failure_code,
                    EXISTS (
                        SELECT 1 FROM pending_updates pending
                        WHERE pending.prefix_id = manifest.prefix_id
                          AND pending.filename = manifest.filename
                          AND pending.applied = 0
                    )
             FROM file_manifest manifest
             LEFT JOIN file_stage_outcomes outcomes
               ON outcomes.prefix_id = manifest.prefix_id
              AND outcomes.filename = manifest.filename
              AND outcomes.stage = 'RELATIONSHIPS'
             WHERE manifest.filename LIKE '%.kt'
             ORDER BY manifest.prefix_id, manifest.filename",
        )
        .map_err(derived_topology_query_error)?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, Option<String>>(0)?,
                row.get::<_, Option<String>>(1)?,
                row.get::<_, Option<String>>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, Option<String>>(4)?,
                row.get::<_, Option<String>>(5)?,
                row.get::<_, Option<String>>(6)?,
                row.get::<_, bool>(7)?,
            ))
        })
        .map_err(derived_topology_query_error)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(derived_topology_query_error)?;
    let mut coverage = ReferenceCoverage {
        total: rows.len(),
        complete: 0,
        limited: 0,
        pending: 0,
        failed: 0,
        stale: 0,
        external_boundaries: 0,
        pending_updates: 0,
        external_targets: 0,
        unattributed_source_edges: 0,
        limitations: Vec::new(),
    };
    let mut limitations = BTreeSet::new();
    for (
        manifest_hash,
        desired_version,
        outcome_hash,
        outcome_version,
        status,
        limitations_json,
        failure_code,
        has_pending_update,
    ) in rows
    {
        if has_pending_update {
            coverage.pending += 1;
            coverage.pending_updates += 1;
            continue;
        }
        let current = manifest_hash.is_some()
            && desired_version.is_some()
            && manifest_hash == outcome_hash
            && desired_version == outcome_version;
        if !current {
            if status.is_some() {
                coverage.stale += 1;
            } else {
                coverage.pending += 1;
            }
            continue;
        }
        let row_limitations = limitations_json
            .as_deref()
            .map(serde_json::from_str::<Vec<String>>)
            .transpose()?
            .unwrap_or_default();
        limitations.extend(row_limitations.iter().cloned());
        match status.as_deref() {
            Some("COMPLETE") if row_limitations.is_empty() => coverage.complete += 1,
            Some("COMPLETE" | "LIMITED") => coverage.limited += 1,
            Some("EXTERNAL_BOUNDARY") => {
                coverage.limited += 1;
                coverage.external_boundaries += 1;
                limitations.extend(failure_code);
            }
            Some("FAILED") => {
                coverage.failed += 1;
                limitations.extend(failure_code);
            }
            _ => coverage.pending += 1,
        }
    }
    coverage.limitations = limitations.into_iter().collect();
    let qualification = if coverage.total > 0 && coverage.complete == coverage.total {
        ReferenceQualification::Current
    } else if coverage.complete > 0
        && coverage.complete + coverage.limited == coverage.total
        && coverage.pending == 0
        && coverage.failed == 0
        && coverage.stale == 0
    {
        ReferenceQualification::Qualified
    } else {
        return Err(CliError::new(
            "DERIVED_TOPOLOGY_REFERENCE_INCOMPLETE",
            format!(
                "Reference evidence is incomplete: {} complete, {} limited, {} pending, {} failed, {} stale.",
                coverage.complete,
                coverage.limited,
                coverage.pending,
                coverage.failed,
                coverage.stale
            ),
        ));
    };
    Ok((qualification, coverage))
}

fn reference_nodes(connection: &rusqlite::Connection) -> Result<Vec<ReferenceNodeInput>> {
    let mut statement = connection
        .prepare(
            "WITH node_ids(fq_id) AS (
                 SELECT fq_id FROM declarations
                 UNION SELECT source_fq_id FROM symbol_references WHERE source_fq_id IS NOT NULL
                 UNION SELECT target_fq_id FROM symbol_references
             )
             SELECT names.fq_name, declarations.kind, prefixes.dir_path,
                    declarations.filename, declarations.module_path, declarations.source_set
             FROM node_ids
             JOIN fq_names names ON names.fq_id = node_ids.fq_id
             LEFT JOIN declarations ON declarations.fq_id = node_ids.fq_id
             LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
             ORDER BY names.fq_name, declarations.prefix_id, declarations.filename",
        )
        .map_err(derived_topology_query_error)?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, Option<String>>(1)?,
                row.get::<_, Option<String>>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, Option<String>>(4)?,
                row.get::<_, Option<String>>(5)?,
            ))
        })
        .map_err(derived_topology_query_error)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(derived_topology_query_error)?;
    let mut nodes = BTreeMap::new();
    for (key, kind, directory, filename, module, source_set) in rows {
        let path = match (directory, filename) {
            (Some(directory), Some(filename)) => Some(
                native_graph_relative_source_path(&directory, &filename)
                    .map_err(|error| {
                        CliError::new("DERIVED_TOPOLOGY_PATH_UNPORTABLE", error.message)
                    })?
                    .to_string_lossy()
                    .into_owned(),
            ),
            (None, None) => None,
            _ => {
                return Err(CliError::new(
                    "DERIVED_TOPOLOGY_PATH_UNPORTABLE",
                    "A persisted declaration path is incomplete.",
                ));
            }
        };
        let name = key
            .rsplit(['.', '#', '$'])
            .next()
            .unwrap_or(key.as_str())
            .to_string();
        nodes.entry(key.clone()).or_insert(ReferenceNodeInput {
            key,
            name,
            kind: kind.unwrap_or_else(|| "EXTERNAL".to_string()),
            path,
            module,
            source_set,
        });
    }
    Ok(nodes.into_values().collect())
}

fn reference_edges(connection: &rusqlite::Connection) -> Result<Vec<ReferenceEdgeInput>> {
    let mut statement = connection
        .prepare(
            "SELECT source.fq_name, target.fq_name, edge.edge_kind, COUNT(*)
             FROM symbol_references edge
             JOIN fq_names source ON source.fq_id = edge.source_fq_id
             JOIN fq_names target ON target.fq_id = edge.target_fq_id
             WHERE edge.source_fq_id IS NOT NULL
             GROUP BY source.fq_name, target.fq_name, edge.edge_kind
             ORDER BY source.fq_name, target.fq_name, edge.edge_kind",
        )
        .map_err(derived_topology_query_error)?;
    statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })
        .map_err(derived_topology_query_error)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(derived_topology_query_error)?
        .into_iter()
        .map(|(source, target, raw_kind, occurrence_count)| {
            let kind = DerivedRelationshipKind::try_from(raw_kind.as_str())?;
            let occurrence_count = usize::try_from(occurrence_count).map_err(|_| {
                CliError::new(
                    "DERIVED_TOPOLOGY_QUERY_FAILED",
                    "A reference occurrence count is negative.",
                )
            })?;
            Ok(ReferenceEdgeInput {
                source,
                target,
                kind,
                relationship_class: kind.into(),
                occurrence_count,
                normalized_weight: (occurrence_count as f64).ln_1p(),
            })
        })
        .collect()
}

fn derived_topology_database_error(error: rusqlite::Error) -> CliError {
    CliError::new("DERIVED_TOPOLOGY_INDEX_UNAVAILABLE", error.to_string())
}

fn derived_topology_query_error(error: rusqlite::Error) -> CliError {
    CliError::new("DERIVED_TOPOLOGY_QUERY_FAILED", error.to_string())
}
