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
                pending: coverage.counts.pending,
                limited: coverage.counts.limited,
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
            pending: 0,
            limited: 0,
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
    let qualified = source_inventory_complete
        && snapshot.coverage.eligibility_proven
        && snapshot.coverage.pending_update_count == 0
        && snapshot.coverage.counts.indexed + snapshot.coverage.counts.limited > 0
        && snapshot.coverage.counts.pending == 0
        && snapshot.coverage.counts.failed == 0
        && snapshot.coverage.counts.stale == 0
        && snapshot.coverage.counts.limited > 0;
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
        return Ok(classify_coverage(
            index,
            semantic_files,
            &scope_fingerprint,
            &pending_updates,
            resolved_scope,
            semantic_scope,
            orphaned_semantic_paths,
        ));
    }
    Err(CliError::new(
        "GRAPH_COVERAGE_UNSTABLE",
        "source-index generation moved twice while reading graph coverage",
    ))
}

fn classify_coverage(
    index: workspace_inventory::model::WorkspaceIndexSnapshot,
    semantic_files: BTreeMap<String, SemanticFileRow>,
    scope_fingerprint: &SemanticGraphStageInputFingerprint,
    pending_updates: &[PersistedPendingUpdateTarget],
    scope: ResolvedRepositoryScope,
    semantic_scope: BTreeSet<String>,
    orphaned_semantic_paths: Vec<String>,
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
            file,
            semantic_files.get(&file.path().to_string()),
            scope_fingerprint,
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
    let pending_update_count = scoped_pending_update_count(&index, &scope, pending_updates);
    let inventory_complete = index.limitations().keys().all(|limitation| {
        !matches!(
            limitation,
            WorkspaceInventoryLimitationCode::SourceIndexIncompatible
                | WorkspaceInventoryLimitationCode::PathContainmentUnprovable
                | WorkspaceInventoryLimitationCode::OutOfRootExcluded
        )
    });
    let semantic_scope_proven = counts.indexed + counts.limited > 0;
    let persisted_updates_complete = pending_update_count == 0;
    let complete = inventory_complete
        && eligibility_proven
        && semantic_scope_proven
        && persisted_updates_complete
        && counts.pending == 0
        && counts.limited == 0
        && counts.failed == 0
        && counts.stale == 0;
    let mut limitations = Vec::new();
    if !inventory_complete {
        limitations.push("SOURCE_INVENTORY_INCOMPLETE".to_string());
    }
    if !eligibility_proven {
        limitations.push("SCOPE_OWNERSHIP_UNPROVEN".to_string());
    }
    if !semantic_scope_proven {
        limitations.push("SEMANTIC_GRAPH_SCOPE_UNPROVEN".to_string());
    }
    if !persisted_updates_complete {
        limitations.push("SOURCE_INDEX_UPDATES_PENDING".to_string());
    }
    if counts.pending > 0 {
        limitations.push("SEMANTIC_GRAPH_FILES_PENDING".to_string());
    }
    if counts.limited > 0 {
        limitations.push("SEMANTIC_GRAPH_FILES_LIMITED".to_string());
        limitations.extend(
            files
                .iter()
                .filter(|file| file.state == GraphFileState::Limited)
                .flat_map(|file| file.limitations.iter().cloned())
                .collect::<BTreeSet<_>>(),
        );
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
        semantic_scope,
        orphaned_semantic_paths,
    }
}
