include!("read/readiness.rs");

pub(crate) fn semantic_graph_refresh_plan(
    workspace_root: &Path,
) -> Result<SemanticGraphRefreshPlan> {
    let snapshot = read_coverage_with_orphans(
        workspace_root,
        RepositoryScope {
            language: Some("kotlin".to_string()),
            ..RepositoryScope::default()
        },
        true,
    )?;
    let (file_paths, removed_file_paths) = plan_semantic_graph_refresh_files(
        &snapshot.files,
        &snapshot.semantic_scope,
        &snapshot.orphaned_semantic_paths,
    );
    Ok(SemanticGraphRefreshPlan {
        file_paths,
        removed_file_paths,
    })
}

fn plan_semantic_graph_refresh_files(
    files: &[GraphFileCoverage],
    semantic_scope: &BTreeSet<String>,
    orphaned_semantic_paths: &[String],
) -> (Vec<String>, Vec<String>) {
    let mut removed_file_paths = orphaned_semantic_paths
        .iter()
        .cloned()
        .collect::<BTreeSet<_>>();
    removed_file_paths.extend(
        files
            .iter()
            .filter(|file| {
                file.reason_code == Some("SEMANTIC_GRAPH_EXTERNAL_BOUNDARY")
                    && semantic_scope.contains(&file.path)
            })
            .map(|file| file.path.clone()),
    );
    let required = files
        .iter()
        .filter(|file| {
            matches!(
                file.state,
                GraphFileState::Pending | GraphFileState::Failed | GraphFileState::Stale
            )
        })
        .map(|file| file.path.clone())
        .collect::<BTreeSet<_>>();
    let scope_changes = !removed_file_paths.is_empty()
        || required
            .iter()
            .any(|path| !semantic_scope.contains(path));
    let file_paths = if scope_changes {
        files
            .iter()
            .filter(|file| {
                matches!(
                    file.state,
                    GraphFileState::Indexed
                        | GraphFileState::Pending
                        | GraphFileState::Failed
                        | GraphFileState::Stale
                ) && !removed_file_paths.contains(&file.path)
            })
            .map(|file| file.path.clone())
            .collect()
    } else {
        required.into_iter().collect()
    };
    (
        file_paths,
        removed_file_paths.into_iter().collect(),
    )
}

pub(crate) fn semantic_graph_read_admission(
    workspace_root: &Path,
) -> Result<SemanticGraphReadAdmission> {
    let snapshot = read_coverage(
        workspace_root,
        RepositoryScope {
            language: Some("kotlin".to_string()),
            ..RepositoryScope::default()
        },
    )?;
    let evidence = SemanticGraphEvidenceCoverage {
        total: snapshot.coverage.counts.total,
        indexed: snapshot.coverage.counts.indexed,
        excluded: snapshot.coverage.counts.excluded,
        pending: snapshot.coverage.counts.pending,
        limited: snapshot.coverage.counts.limited,
        failed: snapshot.coverage.counts.failed,
        stale: snapshot.coverage.counts.stale,
        limitations: snapshot.coverage.limitations.clone(),
    };
    if snapshot.coverage.complete {
        return Ok(SemanticGraphReadAdmission::Current {
            generation: snapshot.generation,
            coverage: evidence,
        });
    }
    let source_inventory_complete = !snapshot
        .coverage
        .limitations
        .iter()
        .any(|limitation| limitation == "SOURCE_INVENTORY_INCOMPLETE");
    let eligible_file_count = snapshot
        .coverage
        .counts
        .total
        .saturating_sub(snapshot.coverage.counts.excluded);
    let qualified = source_inventory_complete
        && snapshot.coverage.eligibility_proven
        && snapshot.coverage.pending_update_count == 0
        && !has_critical_path_gap(&snapshot.coverage)
        && eligible_file_count > 0;
    Ok(if qualified {
        SemanticGraphReadAdmission::Qualified {
            generation: snapshot.generation,
            coverage: evidence,
        }
    } else {
        SemanticGraphReadAdmission::Rejected {
            generation: snapshot.generation,
            coverage: evidence,
        }
    })
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
    read_coverage_with_orphans(workspace_root, scope, false)
}

fn read_coverage_with_orphans(
    workspace_root: &Path,
    scope: RepositoryScope,
    allow_orphans: bool,
) -> Result<CoverageSnapshot> {
    for _ in 0..2 {
        let root = workspace_inventory::model::WorkspaceRoot::try_from(workspace_root)
            .map_err(|error| CliError::new("INVALID_REPOSITORY_SCOPE", error.to_string()))?;
        let index = match workspace_inventory::read_persisted_workspace_index(&root) {
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
        if generation == 0 {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                "The compatible source-index store has no committed current inventory.",
            ));
        }
        let resolved_scope = resolve_repository_scope(scope.clone(), index.files())?;
        let PersistedSemanticCoverageRead {
            generation: semantic_generation,
            scope_fingerprint,
            semantic_files,
            pending_updates,
            semantic_scope,
            orphaned_semantic_paths,
        } = read_semantic_files(workspace_root)?;
        if generation != semantic_generation {
            continue;
        }
        if !allow_orphans
            && let Some(unaccounted) = orphaned_semantic_paths.first()
        {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!(
                    "semantic graph source path `{unaccounted}` has no persisted manifest authority"
                ),
            ));
        }
        let mut snapshot = classify_coverage(
            index,
            semantic_files,
            &scope_fingerprint,
            &pending_updates,
            resolved_scope,
            semantic_scope,
            orphaned_semantic_paths,
        );
        if snapshot.scope.module.is_none() && snapshot.scope.source_set.is_none() {
            apply_critical_path_coverage(workspace_root, &mut snapshot)?;
        }
        return Ok(snapshot);
    }
    Err(CliError::new(
        "GRAPH_COVERAGE_UNSTABLE",
        "source-index generation moved twice while reading graph coverage",
    ))
}

fn apply_critical_path_coverage(
    workspace_root: &Path,
    snapshot: &mut CoverageSnapshot,
) -> Result<()> {
    let configured = config::KastConfig::load(workspace_root)?
        .indexing
        .critical_paths;
    if configured.is_empty() {
        return Ok(());
    }

    let mut unmatched = false;
    let mut incomplete = false;
    for raw in configured {
        let pattern = config::WorkspaceCollectionPattern::parse(&raw).map_err(|error| {
            CliError::new(
                "INDEXING_SCOPE_INVALID",
                format!("invalid indexing.criticalPaths pattern `{raw}`: {error}"),
            )
        })?;
        let mut matched = false;
        for file in snapshot
            .files
            .iter()
            .filter(|file| pattern.matches(&file.path))
        {
            matched = true;
            incomplete |= matches!(
                file.state,
                GraphFileState::Pending
                    | GraphFileState::Limited
                    | GraphFileState::Failed
                    | GraphFileState::Stale
            );
        }
        if !matched {
            unmatched = true;
        }
    }
    if unmatched {
        snapshot
            .coverage
            .limitations
            .push("SEMANTIC_GRAPH_CRITICAL_PATH_UNMATCHED".to_string());
    }
    if incomplete {
        snapshot
            .coverage
            .limitations
            .push("SEMANTIC_GRAPH_CRITICAL_PATH_INCOMPLETE".to_string());
    }
    if unmatched || incomplete {
        snapshot.coverage.complete = false;
        snapshot.coverage.eligible_for_complete_negative = false;
    }
    Ok(())
}

fn has_critical_path_gap(coverage: &CoverageSummary) -> bool {
    coverage.limitations.iter().any(|limitation| {
        matches!(
            limitation.as_str(),
            "SEMANTIC_GRAPH_CRITICAL_PATH_UNMATCHED" | "SEMANTIC_GRAPH_CRITICAL_PATH_INCOMPLETE"
        )
    })
}

include!("read/classify.rs");
