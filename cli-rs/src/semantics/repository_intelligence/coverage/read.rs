pub fn semantic_graph_readiness(workspace_root: &Path) -> SemanticGraphReadiness {
    match read_coverage(
        workspace_root,
        RepositoryScope {
            language: Some("kotlin".to_string()),
            ..RepositoryScope::default()
        },
    ) {
        Ok(snapshot) => {
            let coverage = snapshot.coverage;
            SemanticGraphReadiness {
                state: if coverage.complete {
                    SemanticGraphReadinessState::Ready
                } else {
                    SemanticGraphReadinessState::Incomplete
                },
                generation: Some(snapshot.generation),
                total: coverage.counts.total,
                indexed: coverage.counts.indexed,
                excluded: coverage.counts.excluded,
                failed: coverage.counts.failed,
                stale: coverage.counts.stale,
                limitations: coverage.limitations,
                error: None,
            }
        }
        Err(error) => SemanticGraphReadiness {
            state: SemanticGraphReadinessState::Unavailable,
            generation: None,
            total: 0,
            indexed: 0,
            excluded: 0,
            failed: 0,
            stale: 0,
            limitations: vec![error.code.to_string()],
            error: Some(SemanticGraphReadinessError {
                code: error.code.to_string(),
                message: error.message,
            }),
        },
    }
}

fn validate_scope(scope: &RepositoryScope) -> Result<()> {
    if scope
        .language
        .as_deref()
        .is_some_and(|value| value != "kotlin")
    {
        return Err(CliError::new(
            "INVALID_REPOSITORY_SCOPE",
            "repository intelligence currently supports language=kotlin",
        ));
    }
    Ok(())
}

fn validate_limits(limits: &RepositoryLimits) -> Result<()> {
    if limits.depth > 6
        || !(1..=500).contains(&limits.results)
        || !(1..=50).contains(&limits.evidence)
    {
        return Err(CliError::new(
            "INVALID_REPOSITORY_LIMITS",
            "depth must be at most 6, results from 1 through 500, and evidence from 1 through 50",
        ));
    }
    Ok(())
}

fn open_repository_connection(workspace_root: &Path) -> Result<Connection> {
    let database = config::workspace_database_path(workspace_root)?;
    let connection = Connection::open_with_flags(
        database,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    source_index_db::configure_read_connection(&connection)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    Ok(connection)
}

fn read_coverage(workspace_root: &Path, scope: RepositoryScope) -> Result<CoverageSnapshot> {
    for _ in 0..2 {
        let root = workspace_inventory::model::WorkspaceRoot::try_from(workspace_root)
            .map_err(|error| CliError::new("INVALID_REPOSITORY_SCOPE", error.to_string()))?;
        let index = match workspace_inventory::read_workspace_index(&root) {
            WorkspaceIndexRead::Snapshot(index) => index,
            WorkspaceIndexRead::Unavailable(failure)
            | WorkspaceIndexRead::Incompatible(failure) => {
                return Err(CliError::new(
                    "GRAPH_COVERAGE_UNAVAILABLE",
                    failure.detail().to_string(),
                ));
            }
        };
        let generation = index.stamp().generation().value();
        let resolved_scope = resolve_repository_scope(scope.clone(), index.files())?;
        let (semantic_generation, semantic_files) = read_semantic_files(workspace_root)?;
        if generation != semantic_generation {
            continue;
        }
        return Ok(classify_coverage(
            workspace_root,
            index,
            semantic_files,
            resolved_scope,
        ));
    }
    Err(CliError::new(
        "GRAPH_COVERAGE_UNSTABLE",
        "source-index generation moved twice while reading graph coverage",
    ))
}

fn read_semantic_files(workspace_root: &Path) -> Result<(u64, BTreeMap<String, SemanticFileRow>)> {
    let database = config::workspace_database_path(workspace_root)?;
    let mut connection = Connection::open_with_flags(
        database,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    source_index_db::configure_read_connection(&connection)
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Deferred)
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let generation = transaction
        .query_row("SELECT generation FROM schema_version", [], |row| {
            row.get::<_, i64>(0)
        })
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))
        .and_then(|generation| {
            u64::try_from(generation).map_err(|_| {
                CliError::new(
                    "GRAPH_COVERAGE_UNAVAILABLE",
                    "source-index generation is negative",
                )
            })
        })?;
    let mut statement = transaction
        .prepare(
            "SELECT path, content_hash, refresh_status, diagnostics_json
             FROM semantic_files
             ORDER BY path",
        )
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            let diagnostics_json = row.get::<_, String>(3)?;
            Ok((
                row.get::<_, String>(0)?,
                SemanticFileRow {
                    content_hash: row.get(1)?,
                    refresh_status: row.get(2)?,
                    diagnostics: serde_json::from_str::<Vec<Value>>(&diagnostics_json).map_err(
                        |error| {
                            rusqlite::Error::FromSqlConversionFailure(
                                3,
                                Type::Text,
                                Box::new(error),
                            )
                        },
                    )?,
                },
            ))
        })
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let semantic_files = rows
        .collect::<rusqlite::Result<BTreeMap<_, _>>>()
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    drop(statement);
    transaction
        .commit()
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    Ok((generation, semantic_files))
}

fn classify_coverage(
    workspace_root: &Path,
    index: workspace_inventory::model::WorkspaceIndexSnapshot,
    semantic_files: BTreeMap<String, SemanticFileRow>,
    scope: ResolvedRepositoryScope,
) -> CoverageSnapshot {
    let mut files = Vec::new();
    let mut eligibility_proven = true;
    let filtered = index
        .files()
        .iter()
        .filter(|file| {
            let (matches, proven) = file_matches_scope(file, &scope);
            eligibility_proven &= proven;
            matches
        })
        .collect::<Vec<_>>();
    for file in filtered {
        files.push(classify_file(
            workspace_root,
            file,
            semantic_files.get(&file.path().to_string()),
        ));
    }
    files.sort_by(|left, right| left.path.cmp(&right.path));
    eligibility_proven &= files.iter().all(|file| {
        file.state != GraphFileState::Excluded
            || matches!(
                file.reason_code,
                Some("GENERATED_SOURCE" | "NOT_COMPILATION_SOURCE")
            )
    });
    let counts = count_states(files.iter().map(|file| file.state));
    let modules = coverage_groups(files.iter().flat_map(|file| {
        file.gradle_projects
            .iter()
            .cloned()
            .map(|name| (name, file.state))
    }));
    let compilations = coverage_groups(files.iter().flat_map(|file| {
        file.source_sets
            .iter()
            .cloned()
            .map(|name| (name, file.state))
    }));
    let index_modules = index
        .stamp()
        .module_progress()
        .iter()
        .map(|progress| IndexModuleCoverage {
            name: progress.module_name().as_str().to_string(),
            status: progress.status().canonical(),
            indexed_file_count: progress.indexed_file_count(),
            total_file_count: progress.total_file_count(),
        })
        .collect::<Vec<_>>();
    let progress_complete = !index_modules.is_empty()
        && index_modules.iter().all(|module| {
            module.status == SourceIndexProgressStatus::Complete.canonical()
                && module.indexed_file_count == module.total_file_count
        });
    let pending_update_count = index.stamp().pending_count().value();
    let inventory_complete =
        index.coverage().candidate_inventory() == WorkspaceCoverageDimension::Complete;
    let complete = inventory_complete
        && eligibility_proven
        && progress_complete
        && pending_update_count == 0
        && counts.failed == 0
        && counts.stale == 0;
    let mut limitations = Vec::new();
    if !inventory_complete {
        limitations.push("SOURCE_INVENTORY_INCOMPLETE".to_string());
    }
    if !eligibility_proven {
        limitations.push("SCOPE_OWNERSHIP_UNPROVEN".to_string());
    }
    if !progress_complete {
        limitations.push("MODULE_INDEX_INCOMPLETE".to_string());
    }
    if pending_update_count > 0 {
        limitations.push("SOURCE_INDEX_UPDATES_PENDING".to_string());
    }
    if counts.failed > 0 {
        limitations.push("SEMANTIC_GRAPH_FILES_FAILED".to_string());
    }
    if counts.stale > 0 {
        limitations.push("SEMANTIC_GRAPH_FILES_STALE".to_string());
    }
    let resolved_scope = ResolvedRepositoryScopeProof {
        project: scope.project.as_ref().map(canonical_gradle_project),
        source_set: scope.source_set.as_ref().map(canonical_gradle_source_set),
    };
    CoverageSnapshot {
        generation: index.stamp().generation().value(),
        scope: scope.request,
        resolved_scope,
        coverage: CoverageSummary {
            complete,
            eligible_for_complete_negative: complete,
            counts,
            accounted: counts.total,
            eligibility_proven,
            pending_update_count,
            modules,
            compilations,
            index_modules,
            limitations,
        },
        files,
    }
}
