include!("fingerprint/types.rs");
include!("fingerprint/overlay.rs");
include!("fingerprint/path.rs");

struct PersistedSemanticCoverageRead {
    generation: u64,
    scope_fingerprint: SemanticGraphStageInputFingerprint,
    semantic_files: BTreeMap<String, SemanticFileRow>,
    pending_updates: Vec<PersistedPendingUpdateTarget>,
    semantic_scope: BTreeSet<String>,
    orphaned_semantic_paths: Vec<String>,
}

struct CurrentSemanticGraphScope {
    source_paths: Vec<PersistedSemanticGraphSourcePath>,
}

fn read_semantic_files(workspace_root: &Path) -> Result<PersistedSemanticCoverageRead> {
    let database = config::workspace_database_path(workspace_root)?;
    let mut connection = Connection::open_with_flags(
        &database,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(graph_coverage_unavailable)?;
    source_index_db::configure_read_connection(&connection)
        .map_err(graph_coverage_unavailable)?;
    let has_repository_base =
        crate::agent::native_graph_attach_repository_base(&connection, &database)
            .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.message))?;
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Deferred)
        .map_err(graph_coverage_unavailable)?;
    reject_effective_repository_base_facts(&transaction, has_repository_base)?;
    let generation = semantic_graph_generation(&transaction)?;
    let current_scope =
        current_semantic_graph_scope_fingerprint(&transaction, has_repository_base)?;
    let rows = semantic_graph_outcome_rows(&transaction)?;
    let pending_updates = persisted_pending_update_targets(&transaction)?;
    transaction.commit().map_err(graph_coverage_unavailable)?;
    let semantic_files = decode_semantic_graph_outcomes(rows)?;
    let orphaned_semantic_paths = current_scope
        .source_paths
        .iter()
        .filter(|path| !semantic_files.contains_key(path.as_str()))
        .map(|path| path.as_str().to_string())
        .collect::<Vec<_>>();
    let semantic_scope = current_scope
        .source_paths
        .iter()
        .map(|path| path.as_str().to_string())
        .collect::<BTreeSet<_>>();
    let scope_fingerprint = SemanticGraphStageInputFingerprint::from_inputs(
        current_scope.source_paths.into_iter().filter_map(|path| {
            semantic_files
                .get(path.as_str())
                .and_then(|row| row.manifest_content_hash.clone())
                .map(|content_hash| (path, content_hash))
        }),
    );
    Ok(PersistedSemanticCoverageRead {
        generation,
        scope_fingerprint,
        semantic_files,
        pending_updates,
        semantic_scope,
        orphaned_semantic_paths,
    })
}

fn semantic_graph_generation(transaction: &rusqlite::Transaction<'_>) -> Result<u64> {
    transaction
        .query_row("SELECT generation FROM schema_version", [], |row| {
            row.get::<_, i64>(0)
        })
        .map_err(graph_coverage_unavailable)
        .and_then(|generation| {
            u64::try_from(generation).map_err(|_| {
                CliError::new(
                    "GRAPH_COVERAGE_UNAVAILABLE",
                    "source-index generation is negative",
                )
            })
        })
}

fn current_semantic_graph_scope_fingerprint(
    transaction: &rusqlite::Transaction<'_>,
    has_repository_base: bool,
) -> Result<CurrentSemanticGraphScope> {
    let sql = if has_repository_base {
        "SELECT path
         FROM semantic_files
         WHERE refresh_status != 'CACHED'
         UNION
         SELECT base.path
         FROM repository_base.semantic_files base
         WHERE base.refresh_status != 'CACHED'
           AND NOT EXISTS (
               SELECT 1
               FROM repository_overlay_tombstones tombstones
               WHERE tombstones.path = base.path
           )
           AND NOT EXISTS (
               SELECT 1
               FROM semantic_files overlay
               WHERE overlay.path = base.path
           )
         ORDER BY path"
            .to_string()
    } else {
        "SELECT path FROM semantic_files WHERE refresh_status != 'CACHED' ORDER BY path".to_string()
    };
    let mut statement = transaction
        .prepare(&sql)
        .map_err(graph_coverage_unavailable)?;
    let paths = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(graph_coverage_unavailable)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(graph_coverage_unavailable)?;
    let source_paths = paths
        .into_iter()
        .map(PersistedSemanticGraphSourcePath::parse)
        .collect::<Result<Vec<_>>>()?;
    Ok(CurrentSemanticGraphScope { source_paths })
}

struct SemanticGraphOutcomeRow {
    directory: String,
    filename: String,
    manifest_content_hash: Option<String>,
    desired_stage_version: Option<String>,
    desired_relationships_version: Option<String>,
    relationship_content_hash: Option<String>,
    relationship_stage_version: Option<String>,
    relationship_status: Option<String>,
    relationship_failure_code: Option<String>,
    outcome_content_hash: Option<String>,
    outcome_stage_version: Option<String>,
    outcome_input_fingerprint: Option<String>,
    outcome_status: Option<String>,
    outcome_limitations: Option<String>,
}

fn semantic_graph_outcome_rows(
    transaction: &rusqlite::Transaction<'_>,
) -> Result<Vec<SemanticGraphOutcomeRow>> {
    let mut statement = transaction
        .prepare(
            "SELECT prefixes.dir_path, manifest.filename,
                    manifest.content_hash, manifest.desired_semantic_graph_version,
                    manifest.desired_relationships_version,
                    relationships.content_hash, relationships.stage_version,
                    relationships.outcome_status, relationships.failure_code,
                    outcomes.content_hash, outcomes.stage_version,
                    outcomes.stage_input_fingerprint, outcomes.outcome_status,
                    outcomes.limitations_json
             FROM file_manifest manifest
             JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
             LEFT JOIN file_stage_outcomes outcomes
              ON outcomes.prefix_id = manifest.prefix_id
              AND outcomes.filename = manifest.filename
              AND outcomes.stage = 'SEMANTIC_GRAPH'
             LEFT JOIN file_stage_outcomes relationships
               ON relationships.prefix_id = manifest.prefix_id
              AND relationships.filename = manifest.filename
              AND relationships.stage = 'RELATIONSHIPS'
             ORDER BY prefixes.dir_path, manifest.filename",
        )
        .map_err(graph_coverage_unavailable)?;
    statement
        .query_map([], |row| {
            Ok(SemanticGraphOutcomeRow {
                directory: row.get(0)?,
                filename: row.get(1)?,
                manifest_content_hash: row.get(2)?,
                desired_stage_version: row.get(3)?,
                desired_relationships_version: row.get(4)?,
                relationship_content_hash: row.get(5)?,
                relationship_stage_version: row.get(6)?,
                relationship_status: row.get(7)?,
                relationship_failure_code: row.get(8)?,
                outcome_content_hash: row.get(9)?,
                outcome_stage_version: row.get(10)?,
                outcome_input_fingerprint: row.get(11)?,
                outcome_status: row.get(12)?,
                outcome_limitations: row.get(13)?,
            })
        })
        .map_err(graph_coverage_unavailable)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(graph_coverage_unavailable)
}

fn persisted_pending_update_targets(
    transaction: &rusqlite::Transaction<'_>,
) -> Result<Vec<PersistedPendingUpdateTarget>> {
    let mut statement = transaction
        .prepare(
            "SELECT prefixes.dir_path, pending.filename
             FROM pending_updates pending
             LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = pending.prefix_id
             WHERE pending.applied = 0
             ORDER BY pending.prefix_id, pending.filename",
        )
        .map_err(graph_coverage_unavailable)?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, Option<String>>(0)?,
                row.get::<_, String>(1)?,
            ))
        })
        .map_err(graph_coverage_unavailable)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(graph_coverage_unavailable)?;
    rows.into_iter()
        .map(|(directory, filename)| match directory {
            Some(directory) => semantic_manifest_path(&directory, &filename).map(|path| {
                path.map_or(
                    PersistedPendingUpdateTarget::Unproven,
                    PersistedPendingUpdateTarget::CanonicalPath,
                )
            }),
            None => Ok(PersistedPendingUpdateTarget::Unproven),
        })
        .collect()
}

fn decode_semantic_graph_outcomes(
    rows: Vec<SemanticGraphOutcomeRow>,
) -> Result<BTreeMap<String, SemanticFileRow>> {
    let mut semantic_files = BTreeMap::new();
    for row in rows {
        let Some(path) = semantic_manifest_path(&row.directory, &row.filename)? else {
            continue;
        };
        let manifest_content_hash = row
            .manifest_content_hash
            .map(|value| PersistedFileContentHash::parse(value, &path, "manifest content hash"))
            .transpose()?;
        let desired_stage_version = row
            .desired_stage_version
            .map(|value| {
                PersistedFileStageVersion::parse(value, &path, "desired stage version")
            })
            .transpose()?;
        let desired_relationships_version = row
            .desired_relationships_version
            .map(|value| {
                PersistedFileStageVersion::parse(
                    value,
                    &path,
                    "desired relationships version",
                )
            })
            .transpose()?;
        let relationship_boundary = decode_relationship_boundary(
            &path,
            row.relationship_content_hash,
            row.relationship_stage_version,
            row.relationship_status,
            row.relationship_failure_code,
        )?;
        let outcome = decode_semantic_graph_outcome(
            &path,
            row.outcome_content_hash,
            row.outcome_stage_version,
            row.outcome_input_fingerprint,
            row.outcome_status,
            row.outcome_limitations,
        )?;
        if semantic_files
            .insert(
                path.clone(),
                SemanticFileRow {
                    manifest_content_hash,
                    desired_stage_version,
                    desired_relationships_version,
                    relationship_boundary,
                    outcome,
                },
            )
            .is_some()
        {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!("semantic graph manifest contains duplicate path `{path}`"),
            ));
        }
    }
    Ok(semantic_files)
}

fn decode_relationship_boundary(
    path: &str,
    content_hash: Option<String>,
    stage_version: Option<String>,
    status: Option<String>,
    failure_code: Option<String>,
) -> Result<Option<RelationshipExternalBoundary>> {
    if status.as_deref() != Some("EXTERNAL_BOUNDARY") {
        return Ok(None);
    }
    let (Some(content_hash), Some(stage_version), Some("PSI_UNAVAILABLE")) =
        (content_hash, stage_version, failure_code.as_deref())
    else {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            format!("external relationship boundary for `{path}` is incomplete"),
        ));
    };
    Ok(Some(RelationshipExternalBoundary {
        content_hash: PersistedFileContentHash::parse(
            content_hash,
            path,
            "relationship outcome content hash",
        )?,
        stage_version: PersistedFileStageVersion::parse(
            stage_version,
            path,
            "relationship outcome stage version",
        )?,
    }))
}

fn decode_semantic_graph_outcome(
    path: &str,
    content_hash: Option<String>,
    stage_version: Option<String>,
    input_fingerprint: Option<String>,
    status: Option<String>,
    limitations: Option<String>,
) -> Result<Option<SemanticFileOutcome>> {
    let (content_hash, stage_version, status, limitations) =
        match (content_hash, stage_version, status, limitations) {
            (None, None, None, None) if input_fingerprint.is_none() => return Ok(None),
            (Some(content_hash), Some(stage_version), Some(status), Some(limitations)) => {
                (content_hash, stage_version, status, limitations)
            }
            _ => {
                return Err(CliError::new(
                    "GRAPH_COVERAGE_UNAVAILABLE",
                    format!("semantic graph outcome for `{path}` is incomplete"),
                ));
            }
        };
    let content_hash =
        PersistedFileContentHash::parse(content_hash, path, "outcome content hash")?;
    let stage_version =
        PersistedFileStageVersion::parse(stage_version, path, "outcome stage version")?;
    let status = match status.as_str() {
        "COMPLETE" => SemanticFileOutcomeStatus::Complete,
        "LIMITED" => SemanticFileOutcomeStatus::Limited,
        "FAILED" => SemanticFileOutcomeStatus::Failed,
        _ => {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!("semantic graph outcome for `{path}` has invalid status"),
            ));
        }
    };
    let limitations = serde_json::from_str::<Vec<String>>(&limitations).map_err(|error| {
        CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            format!("semantic graph outcome for `{path}` has invalid limitations: {error}"),
        )
    })?;
    let input_fingerprint = input_fingerprint
        .map(|value| SemanticGraphStageInputFingerprint::parse(value, path))
        .transpose()?;
    Ok(Some(SemanticFileOutcome {
        content_hash,
        stage_version,
        input_fingerprint,
        status,
        limitations,
    }))
}

fn graph_coverage_unavailable(error: impl std::fmt::Display) -> CliError {
    CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string())
}
