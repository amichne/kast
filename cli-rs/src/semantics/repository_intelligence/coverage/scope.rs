fn resolve_repository_scope(
    request: RepositoryScope,
    files: &[WorkspaceInventoryFile],
) -> Result<ResolvedRepositoryScope> {
    let projects = files
        .iter()
        .flat_map(|file| file.indexed_gradle_projects().iter().cloned())
        .collect::<BTreeSet<_>>();
    let mut project = request
        .module
        .as_deref()
        .map(|selector| resolve_gradle_project(selector, &projects))
        .transpose()?;
    let source_sets = files
        .iter()
        .filter_map(|file| match file.source_sets() {
            WorkspaceSourceSetEvidence::Proven(source_sets) => Some(source_sets),
            WorkspaceSourceSetEvidence::Unproven(_) | WorkspaceSourceSetEvidence::Unavailable => {
                None
            }
        })
        .flat_map(|source_sets| source_sets.iter().cloned())
        .collect::<BTreeSet<_>>();
    let source_set = match request.source_set.as_deref() {
        Some(source_set_name) => {
            let matches = source_sets
                .iter()
                .filter(|source_set| {
                    source_set.source_set_name().as_str() == source_set_name
                        && project
                            .as_ref()
                            .is_none_or(|project| source_set.project() == project)
                })
                .cloned()
                .collect::<Vec<_>>();
            match matches.as_slice() {
                [source_set] => {
                    if project.is_none() {
                        project = Some(source_set.project().clone());
                    }
                    Some(source_set.clone())
                }
                [] => {
                    let available = source_sets
                        .iter()
                        .filter(|source_set| {
                            project
                                .as_ref()
                                .is_none_or(|project| source_set.project() == project)
                        })
                        .map(canonical_gradle_source_set)
                        .collect::<Vec<_>>()
                        .join(", ");
                    return Err(CliError::new(
                        "INVALID_REPOSITORY_SCOPE",
                        format!(
                            "repository sourceSet `{source_set_name}` does not identify an authoritative Gradle compilation; available compilations: {available}"
                        ),
                    ));
                }
                _ => {
                    let candidates = matches
                        .iter()
                        .map(canonical_gradle_source_set)
                        .collect::<Vec<_>>()
                        .join(", ");
                    return Err(CliError::new(
                        "AMBIGUOUS_REPOSITORY_SCOPE",
                        format!(
                            "repository sourceSet `{source_set_name}` is ambiguous; select a module from: {candidates}"
                        ),
                    ));
                }
            }
        }
        None => None,
    };
    Ok(ResolvedRepositoryScope {
        request,
        project,
        source_set,
    })
}

fn resolve_gradle_project(
    selector: &str,
    projects: &BTreeSet<BuildQualifiedGradleProjectIdentity>,
) -> Result<BuildQualifiedGradleProjectIdentity> {
    if let Some(project) = projects
        .iter()
        .find(|project| canonical_gradle_project(project) == selector)
    {
        return Ok(project.clone());
    }
    let matches = projects
        .iter()
        .filter(|project| {
            let project_path = project.project_path().as_str();
            project_path != ":"
                && project_path
                    .rsplit(':')
                    .next()
                    .is_some_and(|name| name == selector)
        })
        .cloned()
        .collect::<Vec<_>>();
    match matches.as_slice() {
        [project] => Ok(project.clone()),
        [] => {
            let available = projects
                .iter()
                .map(canonical_gradle_project)
                .collect::<Vec<_>>()
                .join(", ");
            Err(CliError::new(
                "INVALID_REPOSITORY_SCOPE",
                format!(
                    "repository module selector `{selector}` does not identify an authoritative Gradle project; available projects: {available}"
                ),
            ))
        }
        _ => {
            let candidates = matches
                .iter()
                .map(canonical_gradle_project)
                .collect::<Vec<_>>()
                .join(", ");
            Err(CliError::new(
                "AMBIGUOUS_REPOSITORY_SCOPE",
                format!(
                    "repository module selector `{selector}` is ambiguous; use one of: {candidates}"
                ),
            ))
        }
    }
}

fn file_matches_scope(
    file: &WorkspaceInventoryFile,
    scope: &ResolvedRepositoryScope,
) -> (bool, bool) {
    let projects = file.indexed_gradle_projects();
    let source_sets = match file.source_sets() {
        WorkspaceSourceSetEvidence::Proven(source_sets) => Some(source_sets),
        WorkspaceSourceSetEvidence::Unproven(_) | WorkspaceSourceSetEvidence::Unavailable => None,
    };
    if let Some(project) = scope.project.as_ref() {
        if projects.is_empty() {
            return (false, false);
        }
        if !projects.contains(project) {
            return (false, true);
        }
    }
    if let Some(source_set) = scope.source_set.as_ref() {
        return source_sets
            .map(|values| (values.contains(source_set), true))
            .unwrap_or((false, false));
    }
    (true, true)
}

fn scoped_pending_update_count(
    index: &workspace_inventory::model::WorkspaceIndexSnapshot,
    scope: &ResolvedRepositoryScope,
    pending_updates: &[PersistedPendingUpdateTarget],
) -> u64 {
    let relevant = pending_updates.iter().filter(|target| match target {
        PersistedPendingUpdateTarget::CanonicalPath(path) => index
            .files()
            .iter()
            .find(|file| file.path().to_string() == *path)
            .map(|file| {
                let (matches, proven) = file_matches_scope(file, scope);
                matches || !proven
            })
            .unwrap_or(true),
        PersistedPendingUpdateTarget::Unproven => true,
    });
    u64::try_from(relevant.count()).unwrap_or(u64::MAX)
}

fn classify_file(
    file: &WorkspaceInventoryFile,
    semantic: Option<&SemanticFileRow>,
    scope_fingerprint: &SemanticGraphStageInputFingerprint,
) -> GraphFileCoverage {
    let ownership = RepositoryFileOwnership {
        gradle_projects: file.indexed_gradle_projects().clone(),
        source_sets: match file.source_sets() {
            WorkspaceSourceSetEvidence::Proven(source_sets) => source_sets.clone(),
            WorkspaceSourceSetEvidence::Unproven(_) | WorkspaceSourceSetEvidence::Unavailable => {
                BTreeSet::new()
            }
        },
    };
    let gradle_projects = ownership
        .gradle_projects
        .iter()
        .map(canonical_gradle_project)
        .collect::<Vec<_>>();
    let source_sets = ownership
        .source_sets
        .iter()
        .map(canonical_gradle_source_set)
        .collect::<Vec<_>>();
    let current_content_hash = semantic
        .and_then(|row| row.manifest_content_hash.as_ref())
        .map(|hash| hash.as_str().to_string());
    let (state, reason_code) = if is_generated_source(file.path().as_path()) {
        (GraphFileState::Excluded, Some("GENERATED_SOURCE"))
    } else {
        match file.index_state() {
            WorkspaceFileIndexState::Incompatible(_) => (
                GraphFileState::Excluded,
                Some("SOURCE_INDEX_METADATA_INCOMPATIBLE"),
            ),
            WorkspaceFileIndexState::MetadataUnavailable => (
                GraphFileState::Excluded,
                Some("SOURCE_INDEX_METADATA_UNAVAILABLE"),
            ),
            WorkspaceFileIndexState::NotApplicable => {
                (GraphFileState::Excluded, Some("NOT_COMPILATION_SOURCE"))
            }
            WorkspaceFileIndexState::Indexed => match semantic {
                None => (
                    GraphFileState::Pending,
                    Some("SEMANTIC_GRAPH_MANIFEST_MISSING"),
                ),
                Some(row)
                    if row.manifest_content_hash.is_none()
                        || row.desired_stage_version.is_none() =>
                {
                    (
                        GraphFileState::Pending,
                        Some("SEMANTIC_GRAPH_NOT_PLANNED"),
                    )
                }
                Some(row) if row.outcome.is_none() => {
                    (GraphFileState::Pending, Some("SEMANTIC_GRAPH_MISSING"))
                }
                Some(row)
                    if row.outcome.as_ref().is_some_and(|outcome| {
                        Some(&outcome.content_hash) != row.manifest_content_hash.as_ref()
                            || Some(&outcome.stage_version) != row.desired_stage_version.as_ref()
                    }) =>
                {
                    (GraphFileState::Stale, Some("SEMANTIC_GRAPH_OUTCOME_STALE"))
                }
                Some(row)
                    if row.outcome.as_ref().is_some_and(|outcome| {
                        outcome.input_fingerprint.as_ref() != Some(scope_fingerprint)
                    }) =>
                {
                    (
                        GraphFileState::Stale,
                        Some("SEMANTIC_GRAPH_SCOPE_FINGERPRINT_STALE"),
                    )
                }
                Some(row) => match row.outcome.as_ref().map(|outcome| outcome.status) {
                    Some(SemanticFileOutcomeStatus::Complete)
                        if row
                            .outcome
                            .as_ref()
                            .is_some_and(|outcome| outcome.limitations.is_empty()) =>
                    {
                        (GraphFileState::Indexed, None)
                    }
                    Some(SemanticFileOutcomeStatus::Limited)
                        if row
                            .outcome
                            .as_ref()
                            .is_some_and(|outcome| !outcome.limitations.is_empty()) =>
                    {
                        (GraphFileState::Limited, Some("SEMANTIC_GRAPH_LIMITED"))
                    }
                    Some(SemanticFileOutcomeStatus::Failed)
                        if row
                            .outcome
                            .as_ref()
                            .is_some_and(|outcome| outcome.limitations.is_empty()) =>
                    {
                        (GraphFileState::Failed, Some("SEMANTIC_GRAPH_FAILED"))
                    }
                    _ => (
                        GraphFileState::Failed,
                        Some("SEMANTIC_GRAPH_OUTCOME_INVALID"),
                    ),
                },
            },
        }
    };
    let diagnostics = if matches!(state, GraphFileState::Pending | GraphFileState::Failed) {
        vec![json!({"code": reason_code})]
    } else {
        Vec::new()
    };
    let limitations = if state == GraphFileState::Limited {
        semantic
            .and_then(|row| row.outcome.as_ref())
            .map(|outcome| outcome.limitations.clone())
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    GraphFileCoverage {
        path: file.path().to_string(),
        state,
        reason_code,
        indexed_content_hash: semantic
            .and_then(|row| row.outcome.as_ref())
            .map(|outcome| outcome.content_hash.as_str().to_string()),
        current_content_hash,
        diagnostics,
        limitations,
        gradle_projects,
        source_sets,
        ownership,
    }
}

fn is_generated_source(path: &Path) -> bool {
    path.components()
        .zip(path.components().skip(1))
        .any(|(left, right)| {
            left.as_os_str() == "build" && right.as_os_str() == "generated-sources"
        })
}

fn canonical_gradle_project(project: &BuildQualifiedGradleProjectIdentity) -> String {
    format!(
        "{}#{}",
        display_build_root(project.build_root().as_path()),
        project.project_path().as_str()
    )
}

fn canonical_gradle_source_set(source_set: &BuildQualifiedGradleSourceSetIdentity) -> String {
    format!(
        "{}[{}]",
        canonical_gradle_project(source_set.project()),
        source_set.source_set_name().as_str()
    )
}

fn display_build_root(path: &Path) -> String {
    if path.as_os_str().is_empty() {
        ".".to_string()
    } else {
        path.display().to_string()
    }
}

fn count_states(states: impl Iterator<Item = GraphFileState>) -> StateCounts {
    let mut counts = StateCounts::default();
    for state in states {
        counts.add(state);
    }
    counts
}

fn coverage_groups(values: impl Iterator<Item = (String, GraphFileState)>) -> Vec<CoverageGroup> {
    let mut groups = BTreeMap::<String, StateCounts>::new();
    for (name, state) in values {
        groups.entry(name).or_default().add(state);
    }
    groups
        .into_iter()
        .map(|(name, counts)| CoverageGroup { name, counts })
        .collect()
}
