#[derive(Debug, Default)]
struct NativeGraphSourceCandidate {
    projects: BTreeSet<(String, String)>,
    source_sets: BTreeSet<(String, String, String)>,
}

fn native_graph_source_scope_paths(
    args: &AgentNativeGraphArgs,
) -> std::result::Result<Vec<String>, AgentError> {
    let workspace_root = native_graph_workspace_root(args)?;
    let database = native_graph_database_path(args)?;
    let connection = native_graph_scope_connection(&database)?;
    native_graph_generation(&connection)?;
    let pending: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM pending_updates WHERE applied = 0",
            [],
            |row| row.get(0),
        )
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    let (modules, incomplete): (i64, i64) = connection
        .query_row(
            "SELECT COUNT(*), COALESCE(SUM(CASE
                 WHEN relationship_index_status = 'COMPLETE' AND indexed_file_count = total_file_count THEN 0
                 ELSE 1 END), 0)
             FROM module_index_progress",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|error| native_graph_sql_error("GRAPH_SOURCE_SCOPE_UNAVAILABLE", error))?;
    if pending != 0 || modules == 0 || incomplete != 0 {
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_INCOMPLETE",
            "Module/source-set graph selection requires a complete source-index snapshot.",
        ));
    }
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
                    source_sets.build_root, source_sets.project_path, source_sets.source_set_name
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
        if let (Some(build_root), Some(project_path)) = (project_build_root, project_path) {
            candidate.projects.insert((build_root, project_path));
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
        if let (Some(build_root), Some(project_path), Some(source_set)) =
            (source_build_root, source_project_path, source_set)
        {
            candidate
                .source_sets
                .insert((build_root, project_path, source_set));
        }
    }
    let selected = candidates
        .into_iter()
        .filter(|(_, candidate)| native_graph_source_candidate_matches(candidate, args))
        .map(|(relative, _)| workspace_root.join(relative).to_string_lossy().into_owned())
        .collect::<Vec<_>>();
    if selected.is_empty() {
        return Err(agent_error(
            "GRAPH_SOURCE_SCOPE_EMPTY",
            "The requested Gradle module/source-set scope matched no indexed Kotlin files.",
        ));
    }
    Ok(selected)
}

fn native_graph_source_candidate_matches(
    candidate: &NativeGraphSourceCandidate,
    args: &AgentNativeGraphArgs,
) -> bool {
    let module_matches = args.modules.is_empty() || args.modules.iter().any(|selector| {
        let WorkspaceModuleSelector::Gradle {
            build_root,
            project_path,
        } = selector
        else {
            return false;
        };
        candidate.projects.iter().any(|(actual_root, actual_path)| {
            actual_root == build_root.as_str() && actual_path == project_path.as_str()
        })
    });
    let source_set_matches = args.source_sets.is_empty()
        || args.source_sets.iter().any(|expected| {
            candidate
                .source_sets
                .iter()
                .any(|(actual_root, actual_path, actual)| {
                    actual == expected.as_str()
                        && (args.modules.is_empty()
                            || args.modules.iter().any(|selector| {
                                matches!(
                                    selector,
                                    WorkspaceModuleSelector::Gradle {
                                        build_root,
                                        project_path,
                                    } if actual_root == build_root.as_str()
                                        && actual_path == project_path.as_str()
                                )
                            }))
                })
        });
    module_matches && source_set_matches
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
    args: &AgentNativeGraphArgs,
) -> std::result::Result<Vec<String>, AgentError> {
    let workspace_root = native_graph_workspace_root(args)?;
    let database = native_graph_database_path(args)?;
    if !database.is_file() {
        return Ok(Vec::new());
    }
    let connection = native_graph_scope_connection(&database)?;
    let has_repository_base = native_graph_attach_repository_base(&connection, &database)?;
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
