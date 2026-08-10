#[derive(Debug, Default)]
struct NativeGraphSourceCandidate {
    projects: BTreeSet<BuildQualifiedGradleProjectIdentity>,
    source_sets: BTreeSet<BuildQualifiedGradleSourceSetIdentity>,
    ownership_unproven: bool,
    pending_update: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum NativeGraphSourceCandidateMatch {
    Selected,
    Excluded,
    Unproven,
}

struct NativeGraphRefreshScopeSnapshot {
    generation: u64,
    selected: Vec<String>,
    persisted: Vec<String>,
}

fn native_graph_refresh_scope_snapshot(
    args: &AgentNativeGraphArgs,
) -> std::result::Result<NativeGraphRefreshScopeSnapshot, AgentError> {
    let workspace_root = native_graph_workspace_root(args)?;
    let semantic_read = runtime::semantic_workspace_read_ready(Some(workspace_root.clone()))
        .map_err(AgentError::from_cli_error)?;
    let published = semantic_read.published();
    let database = native_graph_database_path(args, Some(published))?;
    let connection = native_graph_scope_connection(&database)?;
    let has_repository_base =
        native_graph_attach_database_base(args, &connection, &database, Some(published))?;
    crate::source_index_db::enable_query_only(&connection)
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    connection
        .execute_batch("BEGIN")
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    let result = (|| {
        let generation = native_graph_generation(&connection)?;
        let selected = if args.modules.is_empty() && args.source_sets.is_empty() {
            Vec::new()
        } else {
            native_graph_source_scope_paths(args, &workspace_root, &connection)?
        };
        let persisted = native_graph_persisted_source_paths(
            &workspace_root,
            &connection,
            has_repository_base,
        )?;
        if native_graph_generation(&connection)? != generation {
            return Err(agent_error(
                "NATIVE_GRAPH_GENERATION_CHANGED",
                "Source-index generation changed while the graph refresh scope was planned.",
            ));
        }
        Ok(NativeGraphRefreshScopeSnapshot {
            generation,
            selected,
            persisted,
        })
    })();
    let _ = connection.execute_batch(if result.is_ok() { "COMMIT" } else { "ROLLBACK" });
    let snapshot = result?;
    let snapshot = semantic_read
        .revalidate()
        .map_err(AgentError::from_cli_error)?
        .finish(snapshot);
    Ok(snapshot)
}

fn native_graph_source_scope_paths(
    args: &AgentNativeGraphArgs,
    workspace_root: &Path,
    connection: &rusqlite::Connection,
) -> std::result::Result<Vec<String>, AgentError> {
    if args
        .modules
        .iter()
        .any(|selector| matches!(selector, WorkspaceModuleSelector::Backend(_)))
    {
        return Err(agent_error(
            "AGENT_USAGE",
            "Graph source scopes require model-proven `gradle:<build-root>#<project-path>` module selectors.",
        ));
    }

    let mut candidates = BTreeMap::<PathBuf, NativeGraphSourceCandidate>::new();
    let mut statement = connection
        .prepare(
            "SELECT prefixes.dir_path, manifest.filename,
                    projects.build_root, projects.project_path,
                    source_sets.build_root, source_sets.project_path, source_sets.source_set_name,
                    CASE WHEN EXISTS (
                        SELECT 1
                        FROM pending_updates pending
                        WHERE pending.prefix_id = manifest.prefix_id
                          AND pending.filename = manifest.filename
                          AND pending.applied = 0
                    ) THEN 1 ELSE 0 END
             FROM file_manifest manifest
             JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
             LEFT JOIN file_gradle_projects projects
               ON projects.prefix_id = manifest.prefix_id AND projects.filename = manifest.filename
             LEFT JOIN file_gradle_source_sets source_sets
               ON source_sets.prefix_id = manifest.prefix_id AND source_sets.filename = manifest.filename
             WHERE manifest.filename LIKE '%.kt'
             ORDER BY prefixes.dir_path, manifest.filename",
        )
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    let mut rows = statement
        .query([])
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    while let Some(row) = rows
        .next()
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?
    {
        let directory: String = row
            .get(0)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        let filename: String = row
            .get(1)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        let relative = native_graph_relative_source_path(&directory, &filename)?;
        let candidate = candidates.entry(relative).or_default();
        let project_build_root: Option<String> = row
            .get(2)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        let project_path: Option<String> = row
            .get(3)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        match (project_build_root, project_path) {
            (Some(build_root), Some(project_path)) => {
                if let Some(project) =
                    BuildQualifiedGradleProjectIdentity::parse(build_root, project_path)
                {
                    candidate.projects.insert(project);
                } else {
                    candidate.ownership_unproven = true;
                }
            }
            (None, None) => {}
            _ => candidate.ownership_unproven = true,
        }
        let source_build_root: Option<String> = row
            .get(4)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        let source_project_path: Option<String> = row
            .get(5)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        let source_set: Option<String> = row
            .get(6)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
        match (source_build_root, source_project_path, source_set) {
            (Some(build_root), Some(project_path), Some(source_set)) => {
                if let Some(source_set) = BuildQualifiedGradleSourceSetIdentity::parse(
                    build_root,
                    project_path,
                    source_set,
                ) {
                    candidate.source_sets.insert(source_set);
                } else {
                    candidate.ownership_unproven = true;
                }
            }
            (None, None, None) => {}
            _ => candidate.ownership_unproven = true,
        }
        candidate.pending_update = row
            .get::<_, i64>(7)
            .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?
            == 1;
    }
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
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    if unknown_pending_updates != 0 {
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_UNPROVEN",
            "The source index has unapplied Kotlin updates without persisted scope ownership.",
        ));
    }
    let mut selected = Vec::new();
    for (relative, candidate) in candidates {
        match native_graph_source_candidate_match(&candidate, args) {
            NativeGraphSourceCandidateMatch::Selected => selected.push((relative, candidate)),
            NativeGraphSourceCandidateMatch::Excluded => {}
            NativeGraphSourceCandidateMatch::Unproven => {
                return Err(agent_error(
                    "GRAPH_SOURCE_SCOPE_UNPROVEN",
                    "The requested Gradle module/source-set scope includes a persisted Kotlin file without sufficient model ownership evidence.",
                ));
            }
        }
    }
    if selected.is_empty() {
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_EMPTY",
            "The requested Gradle module/source-set scope matched no indexed Kotlin files.",
        ));
    }
    if selected
        .iter()
        .any(|(_, candidate)| candidate.pending_update)
    {
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_INCOMPLETE",
            "The selected module/source-set has unapplied persisted source updates.",
        ));
    }
    Ok(selected
        .into_iter()
        .map(|(relative, _)| workspace_root.join(relative).to_string_lossy().into_owned())
        .collect())
}

fn native_graph_source_candidate_match(
    candidate: &NativeGraphSourceCandidate,
    args: &AgentNativeGraphArgs,
) -> NativeGraphSourceCandidateMatch {
    if candidate.ownership_unproven
        || candidate
            .source_sets
            .iter()
            .any(|source_set| !candidate.projects.contains(source_set.project()))
    {
        return NativeGraphSourceCandidateMatch::Unproven;
    }
    if !args.modules.is_empty() {
        if candidate.projects.is_empty() {
            return NativeGraphSourceCandidateMatch::Unproven;
        }
        let module_matches = args.modules.iter().any(|selector| {
            let WorkspaceModuleSelector::Gradle {
                build_root,
                project_path,
            } = selector
            else {
                return false;
            };
            BuildQualifiedGradleProjectIdentity::parse(
                build_root.as_str().to_string(),
                project_path.as_str().to_string(),
            )
            .is_some_and(|expected| candidate.projects.contains(&expected))
        });
        if !module_matches {
            return NativeGraphSourceCandidateMatch::Excluded;
        }
    }
    if args.source_sets.is_empty() {
        return NativeGraphSourceCandidateMatch::Selected;
    }
    if candidate.source_sets.is_empty() {
        return NativeGraphSourceCandidateMatch::Unproven;
    }
    if args.source_sets.iter().any(|expected| {
        candidate
            .source_sets
            .iter()
                .any(|actual| {
                    actual.source_set_name().as_str() == expected.as_str()
                        && (args.modules.is_empty()
                            || args.modules.iter().any(|selector| {
                                matches!(
                                    selector,
                                    WorkspaceModuleSelector::Gradle {
                                        build_root,
                                        project_path,
                                    } if BuildQualifiedGradleProjectIdentity::parse(
                                        build_root.as_str().to_string(),
                                        project_path.as_str().to_string(),
                                    ).as_ref() == Some(actual.project())
                                )
                            }))
            })
    }) {
        NativeGraphSourceCandidateMatch::Selected
    } else {
        NativeGraphSourceCandidateMatch::Excluded
    }
}

fn native_graph_relative_source_path(
    directory: &str,
    filename: &str,
) -> std::result::Result<PathBuf, AgentError> {
    if directory.starts_with("__kast_abs__/") || filename.contains(['/', '\\']) {
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_UNPROVEN",
            "The source index contains a Kotlin path outside the workspace scope.",
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
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_UNPROVEN",
            "The source index contains a non-canonical workspace path.",
        ));
    }
    Ok(path)
}

fn native_graph_persisted_source_paths(
    workspace_root: &Path,
    connection: &rusqlite::Connection,
    has_repository_base: bool,
) -> std::result::Result<Vec<String>, AgentError> {
    let sql = if has_repository_base {
        format!(
            "{} SELECT path FROM effective_files WHERE refresh_status != 'CACHED' ORDER BY path",
            native_graph_overlay_cte(),
        )
    } else {
        "SELECT path FROM semantic_files WHERE refresh_status != 'CACHED' ORDER BY path".to_string()
    };
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    let paths = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    Ok(paths
        .into_iter()
        .map(|path| workspace_root.join(path).to_string_lossy().into_owned())
        .collect())
}

fn native_graph_workspace_root(
    args: &AgentNativeGraphArgs,
) -> std::result::Result<PathBuf, AgentError> {
    args.runtime
        .workspace_root
        .clone()
        .map(Ok)
        .unwrap_or_else(std::env::current_dir)
        .and_then(std::fs::canonicalize)
        .map_err(|error| {
            agent_error(
                "AGENT_WORKSPACE_INVALID",
                format!("Cannot resolve the active workspace: {error}"),
            )
        })
}

fn native_graph_scope_connection(
    database: &Path,
) -> std::result::Result<rusqlite::Connection, AgentError> {
    let connection = rusqlite::Connection::open_with_flags(
        database,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY
            | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX
            | rusqlite::OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    crate::source_index_db::configure_read_connection(&connection)
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    Ok(connection)
}
