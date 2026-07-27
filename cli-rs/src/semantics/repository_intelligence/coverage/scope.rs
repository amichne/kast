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
    let module_matches = scope
        .project
        .as_ref()
        .is_none_or(|project| projects.contains(project));
    let source_set_matches = scope
        .source_set
        .as_ref()
        .is_none_or(|source_set| source_sets.is_some_and(|values| values.contains(source_set)));
    let ownership_proven = scope.project.as_ref().is_none_or(|_| !projects.is_empty())
        && scope
            .source_set
            .as_ref()
            .is_none_or(|_| source_sets.is_some());
    (module_matches && source_set_matches, ownership_proven)
}

fn classify_file(
    workspace_root: &Path,
    file: &WorkspaceInventoryFile,
    semantic: Option<&SemanticFileRow>,
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
    let current_content_hash = std::fs::read(workspace_root.join(file.path().as_path()))
        .ok()
        .map(|content| hex::encode(Sha256::digest(content)));
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
            WorkspaceFileIndexState::Indexed if current_content_hash.is_none() => {
                (GraphFileState::Failed, Some("SOURCE_FILE_MISSING"))
            }
            WorkspaceFileIndexState::Indexed => match semantic {
                None => (GraphFileState::Failed, Some("SEMANTIC_GRAPH_MISSING")),
                Some(row)
                    if !matches!(row.refresh_status.as_str(), "REFRESHED" | "CACHED")
                        || row.content_hash.is_none() =>
                {
                    (
                        GraphFileState::Failed,
                        Some("SEMANTIC_GRAPH_NOT_AUTHORITATIVE"),
                    )
                }
                Some(row) if row.content_hash != current_content_hash => {
                    (GraphFileState::Stale, Some("CONTENT_HASH_MISMATCH"))
                }
                Some(_) => (GraphFileState::Indexed, None),
            },
        }
    };
    let diagnostics = if state == GraphFileState::Failed {
        semantic
            .map(|row| row.diagnostics.clone())
            .filter(|diagnostics| !diagnostics.is_empty())
            .unwrap_or_else(|| vec![json!({"code": reason_code})])
    } else {
        Vec::new()
    };
    GraphFileCoverage {
        path: file.path().to_string(),
        state,
        reason_code,
        indexed_content_hash: semantic.and_then(|row| row.content_hash.clone()),
        current_content_hash,
        diagnostics,
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
