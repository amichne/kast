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
    let eligible_file_count = counts.total.saturating_sub(counts.excluded);
    let semantic_scope_proven = eligible_file_count == 0 || counts.indexed + counts.limited > 0;
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
