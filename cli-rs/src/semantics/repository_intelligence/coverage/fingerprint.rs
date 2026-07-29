include!("fingerprint/types.rs");
include!("fingerprint/overlay.rs");

struct PersistedSemanticCoverageRead {
    generation: u64,
    scope_fingerprint: SemanticGraphStageInputFingerprint,
    semantic_files: BTreeMap<String, SemanticFileRow>,
    pending_updates: Vec<PersistedPendingUpdateTarget>,
}

struct CurrentSemanticGraphScope {
    fingerprint: SemanticGraphStageInputFingerprint,
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
    if let Some(unaccounted) = current_scope
        .source_paths
        .iter()
        .find(|path| !semantic_files.contains_key(path.as_str()))
    {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            format!(
                "semantic graph source path `{}` has no persisted manifest authority",
                unaccounted.as_str()
            ),
        ));
    }
    Ok(PersistedSemanticCoverageRead {
        generation,
        scope_fingerprint: current_scope.fingerprint,
        semantic_files,
        pending_updates,
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
    let fingerprint =
        SemanticGraphStageInputFingerprint::from_paths(source_paths.iter().cloned());
    Ok(CurrentSemanticGraphScope {
        fingerprint,
        source_paths,
    })
}

type SemanticGraphOutcomeRow = (
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

fn semantic_graph_outcome_rows(
    transaction: &rusqlite::Transaction<'_>,
) -> Result<Vec<SemanticGraphOutcomeRow>> {
    let mut statement = transaction
        .prepare(
            "SELECT prefixes.dir_path, manifest.filename,
                    manifest.content_hash, manifest.desired_semantic_graph_version,
                    outcomes.content_hash, outcomes.stage_version,
                    outcomes.stage_input_fingerprint, outcomes.outcome_status,
                    outcomes.limitations_json
             FROM file_manifest manifest
             JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
             LEFT JOIN file_stage_outcomes outcomes
               ON outcomes.prefix_id = manifest.prefix_id
              AND outcomes.filename = manifest.filename
              AND outcomes.stage = 'SEMANTIC_GRAPH'
             ORDER BY prefixes.dir_path, manifest.filename",
        )
        .map_err(graph_coverage_unavailable)?;
    statement
        .query_map([], |row| {
            Ok((
                row.get(0)?,
                row.get(1)?,
                row.get(2)?,
                row.get(3)?,
                row.get(4)?,
                row.get(5)?,
                row.get(6)?,
                row.get(7)?,
                row.get(8)?,
            ))
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
        let (
            directory,
            filename,
            manifest_content_hash,
            desired_stage_version,
            outcome_content_hash,
            outcome_stage_version,
            outcome_input_fingerprint,
            outcome_status,
            outcome_limitations,
        ) = row;
        let Some(path) = semantic_manifest_path(&directory, &filename)? else {
            continue;
        };
        let manifest_content_hash = manifest_content_hash
            .map(|value| PersistedFileContentHash::parse(value, &path, "manifest content hash"))
            .transpose()?;
        let desired_stage_version = desired_stage_version
            .map(|value| {
                PersistedFileStageVersion::parse(value, &path, "desired stage version")
            })
            .transpose()?;
        let outcome = decode_semantic_graph_outcome(
            &path,
            outcome_content_hash,
            outcome_stage_version,
            outcome_input_fingerprint,
            outcome_status,
            outcome_limitations,
        )?;
        if semantic_files
            .insert(
                path.clone(),
                SemanticFileRow {
                    manifest_content_hash,
                    desired_stage_version,
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

fn semantic_manifest_path(directory: &str, filename: &str) -> Result<Option<String>> {
    if directory.starts_with("__kast_abs__/") {
        return Ok(None);
    }
    if filename.contains(['/', '\\']) {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            "semantic graph manifest filename is not canonical",
        ));
    }
    let directory = directory
        .strip_prefix("__kast_rel__/")
        .unwrap_or(directory);
    let path = if directory.is_empty() {
        PathBuf::from(filename)
    } else {
        PathBuf::from(directory).join(filename)
    };
    if path
        .components()
        .any(|component| !matches!(component, std::path::Component::Normal(_)))
    {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            "semantic graph manifest path is not canonical",
        ));
    }
    Ok(Some(path.to_string_lossy().into_owned()))
}

fn graph_coverage_unavailable(error: impl std::fmt::Display) -> CliError {
    CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string())
}
