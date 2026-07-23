#[derive(Debug, Clone)]
struct NativeGraphNode {
    database_id: Option<u64>,
    key: String,
}

#[derive(Debug, Clone)]
struct NativeGraphEdge {
    source: usize,
    target: usize,
    kind: String,
    context: String,
    weight: f64,
}

#[derive(Debug, Clone)]
struct NativeGraph {
    nodes: Vec<NativeGraphNode>,
    edges: Vec<NativeGraphEdge>,
    offsets: Vec<usize>,
    targets: Vec<usize>,
    weights: Vec<f64>,
}

const NATIVE_GRAPH_ROOT_PACKAGE_KEY: &str = "<root>";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NativeGraphOverlayDescriptor {
    base_database: Option<PathBuf>,
}

fn execute_agent_native_graph(args: AgentNativeGraphArgs) -> AgentEnvelope {
    match native_graph_result(&args) {
        Ok(result) => result_envelope("agent/graph".to_string(), result),
        Err(error) => error_envelope("agent/graph".to_string(), None, error),
    }
}

fn native_graph_result(args: &AgentNativeGraphArgs) -> std::result::Result<Value, AgentError> {
    if !args.resolution.is_finite() || args.resolution <= 0.0 {
        return Err(agent_error(
            "AGENT_USAGE",
            "--resolution must be a finite number greater than zero.",
        ));
    }
    if args.operation == NativeGraphOperation::Nodes
        && args.after_id > 0
        && args.generation.is_none()
    {
        return Err(agent_error(
            "AGENT_USAGE",
            "--generation is required when resuming nodes with --after-id.",
        ));
    }
    let database = native_graph_database_path(args)?;
    let connection = rusqlite::Connection::open_with_flags(
        &database,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY
            | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX
            | rusqlite::OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_DATABASE_UNAVAILABLE", error))?;
    crate::source_index_db::configure_read_connection(&connection)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_DATABASE_UNAVAILABLE", error))?;
    let has_repository_base = native_graph_attach_repository_base(&connection, &database)?;
    crate::source_index_db::enable_query_only(&connection)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_DATABASE_UNAVAILABLE", error))?;
    let generation = native_graph_generation(&connection)?;
    if let Some(expected) = args.generation
        && expected != generation
    {
        return Err(agent_error(
            "NATIVE_GRAPH_GENERATION_CHANGED",
            format!("Requested generation {expected}, but the source index is at {generation}."),
        ));
    }
    if args.operation == NativeGraphOperation::Nodes {
        if args.scope != NativeGraphScope::Symbol {
            return Err(agent_error(
                "AGENT_USAGE",
                "Generation-pinned nodes enumeration is available only for --scope symbol.",
            ));
        }
        return native_graph_symbol_page(
            &connection,
            generation,
            args.after_id,
            usize::from(args.limit),
            has_repository_base,
        );
    }

    let load_started = std::time::Instant::now();
    let graph = load_native_graph(&connection, args.scope, has_repository_base)?;
    let load_nanos = load_started.elapsed().as_nanos();
    if args.operation == NativeGraphOperation::Neighbors {
        let symbol = args.symbol.as_deref().ok_or_else(|| {
            agent_error("AGENT_USAGE", "--symbol is required for --operation neighbors.")
        })?;
        let body = native_graph_neighbors(&graph, generation, args.scope, symbol)?;
        if native_graph_generation(&connection)? != generation {
            return Err(agent_error(
                "NATIVE_GRAPH_GENERATION_CHANGED",
                "Source-index generation changed while native graph neighbors were being computed.",
            ));
        }
        return Ok(body);
    }

    let body = match args.operation {
        NativeGraphOperation::Summary => {
            let compute_started = std::time::Instant::now();
            let components = native_connected_components(&graph);
            let strongly_connected = native_tarjan_scc(&graph);
            let communities = native_weighted_leiden(&graph, args.resolution);
            let compute_nanos = compute_started.elapsed().as_nanos();
            let measurements =
                native_graph_measurements(&connection, &database, load_nanos, compute_nanos)?;
            json!({
                "type": "KAST_NATIVE_GRAPH_SUMMARY",
                "scope": args.scope,
                "generation": generation,
                "nodeCount": graph.nodes.len(),
                "edgeOccurrenceCount": graph.edges.len(),
                "weightedEdgeCount": graph.edges.iter().map(|edge| edge.weight).sum::<f64>(),
                "componentCount": components.iter().copied().max().map_or(0, |value| value + 1),
                "stronglyConnectedComponentCount": strongly_connected.iter().copied().max().map_or(0, |value| value + 1),
                "communityCount": communities.iter().copied().max().map_or(0, |value| value + 1),
                "measurements": measurements,
                "schemaVersion": SCHEMA_VERSION
            })
        }
        NativeGraphOperation::Neighbors => unreachable!("neighbors returned before graph analytics"),
        NativeGraphOperation::Topology => {
            let components = native_connected_components(&graph);
            let strongly_connected = native_tarjan_scc(&graph);
            let topological_components =
                native_condensation_topological_order(&graph, &strongly_connected);
            json!({
                "type": "KAST_NATIVE_GRAPH_TOPOLOGY",
                "scope": args.scope,
                "generation": generation,
                "nodes": graph.nodes.iter().map(|node| &node.key).collect::<Vec<_>>(),
                "components": components,
                "stronglyConnectedComponents": strongly_connected,
                "condensationTopologicalOrder": topological_components,
                "schemaVersion": SCHEMA_VERSION
            })
        }
        NativeGraphOperation::Communities => {
            let communities = native_weighted_leiden(&graph, args.resolution);
            json!({
                "type": "KAST_NATIVE_GRAPH_COMMUNITIES",
                "scope": args.scope,
                "generation": generation,
                "resolution": args.resolution,
                "nodes": graph.nodes.iter().zip(communities).map(|(node, community)| {
                    json!({"key": node.key, "community": community})
                }).collect::<Vec<_>>(),
                "schemaVersion": SCHEMA_VERSION
            })
        }
        NativeGraphOperation::Nodes => unreachable!("nodes returned before graph materialization"),
    };
    if native_graph_generation(&connection)? != generation {
        return Err(agent_error(
            "NATIVE_GRAPH_GENERATION_CHANGED",
            "Source-index generation changed while the native graph was being computed.",
        ));
    }
    Ok(body)
}

fn native_graph_attach_repository_base(
    connection: &rusqlite::Connection,
    database: &Path,
) -> std::result::Result<bool, AgentError> {
    let descriptor_path = database.with_file_name("repository-overlay.json");
    if !descriptor_path.is_file() {
        return Ok(false);
    }
    let descriptor: NativeGraphOverlayDescriptor = serde_json::from_slice(
        &std::fs::read(&descriptor_path).map_err(|error| {
            agent_error(
                "NATIVE_GRAPH_OVERLAY_UNAVAILABLE",
                format!("Cannot read {}: {error}", descriptor_path.display()),
            )
        })?,
    )
    .map_err(|error| {
        agent_error(
            "NATIVE_GRAPH_OVERLAY_UNAVAILABLE",
            format!("Cannot decode {}: {error}", descriptor_path.display()),
        )
    })?;
    let Some(base) = descriptor.base_database else {
        return Ok(false);
    };
    if !base.is_absolute() || !base.is_file() {
        return Err(agent_error(
            "NATIVE_GRAPH_OVERLAY_UNAVAILABLE",
            format!("Repository base is unavailable: {}", base.display()),
        ));
    }
    connection
        .execute(
            "ATTACH DATABASE ?1 AS repository_base",
            [base.to_string_lossy().as_ref()],
        )
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_OVERLAY_UNAVAILABLE", error))?;
    let base_version: i64 = connection
        .query_row(
            "SELECT version FROM repository_base.schema_version LIMIT 1",
            [],
            |row| row.get(0),
        )
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_OVERLAY_UNAVAILABLE", error))?;
    if base_version != crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION {
        return Err(agent_error(
            "NATIVE_GRAPH_SCHEMA_MISMATCH",
            format!("Repository base uses source-index schema {base_version}."),
        ));
    }
    Ok(true)
}

fn native_graph_database_path(
    args: &AgentNativeGraphArgs,
) -> std::result::Result<PathBuf, AgentError> {
    if let Some(database) = &args.database {
        return Ok(database.clone());
    }
    let workspace_root = args
        .runtime
        .workspace_root
        .clone()
        .map(Ok)
        .unwrap_or_else(std::env::current_dir)
        .map_err(|error| {
            agent_error(
                "NATIVE_GRAPH_DATABASE_UNAVAILABLE",
                format!("Cannot resolve the active workspace: {error}"),
            )
        })?;
    crate::config::workspace_database_path(&workspace_root).map_err(|error| {
        agent_error(
            "NATIVE_GRAPH_DATABASE_UNAVAILABLE",
            format!("Cannot resolve source-index.db: {error}"),
        )
    })
}

fn native_graph_generation(
    connection: &rusqlite::Connection,
) -> std::result::Result<u64, AgentError> {
    let (version, generation): (i64, i64) = connection
        .query_row(
            "SELECT version, generation FROM schema_version LIMIT 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_SCHEMA_UNAVAILABLE", error))?;
    if version != crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION {
        return Err(agent_error(
            "NATIVE_GRAPH_SCHEMA_MISMATCH",
            format!(
                "source-index.db uses schema {version}; native graph requires {}.",
                crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION
            ),
        ));
    }
    Ok(generation as u64)
}

fn native_graph_symbol_page(
    connection: &rusqlite::Connection,
    generation: u64,
    after_id: u64,
    limit: usize,
    has_repository_base: bool,
) -> std::result::Result<Value, AgentError> {
    connection
        .execute_batch("BEGIN")
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let result = (|| {
        if native_graph_generation(connection)? != generation {
            return Err(agent_error(
                "NATIVE_GRAPH_GENERATION_CHANGED",
                "Source-index generation changed before keyset enumeration.",
            ));
        }
        let sql = if has_repository_base {
            format!(
                "{} SELECT encoded_id, stable_key, kind, name, file_path
                    FROM effective_symbol_rows
                    WHERE encoded_id > ?
                    ORDER BY encoded_id
                    LIMIT ?",
                native_graph_overlay_cte(),
            )
        } else {
            "SELECT symbols.id, symbols.stable_key, symbols.kind, symbols.name, files.path
                   FROM semantic_symbols symbols
                   JOIN semantic_files files ON files.id = symbols.file_id
                   WHERE symbols.id > ?
                   ORDER BY symbols.id
                   LIMIT ?"
                .to_string()
        };
        let mut statement = connection
            .prepare(&sql)
            .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
        let rows = statement
            .query_map(
                rusqlite::params![
                    i64::try_from(after_id).unwrap_or(i64::MAX),
                    i64::try_from(limit.saturating_add(1)).unwrap_or(i64::MAX)
                ],
                |row| {
                    Ok(json!({
                        "id": row.get::<_, i64>(0)? as u64,
                        "stableKey": row.get::<_, String>(1)?,
                        "kind": row.get::<_, String>(2)?,
                        "name": row.get::<_, String>(3)?,
                        "path": row.get::<_, String>(4)?
                    }))
                },
            )
            .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
        let has_more = rows.len() > limit;
        let page = rows.into_iter().take(limit).collect::<Vec<_>>();
        let next_after_id = has_more
            .then(|| page.last().and_then(|row| row["id"].as_u64()))
            .flatten();
        if native_graph_generation(connection)? != generation {
            return Err(agent_error(
                "NATIVE_GRAPH_GENERATION_CHANGED",
                "Source-index generation changed during keyset enumeration.",
            ));
        }
        Ok(json!({
            "type": "KAST_NATIVE_GRAPH_NODES",
            "generation": generation,
            "afterId": after_id,
            "nodes": page,
            "nextAfterId": next_after_id,
            "schemaVersion": SCHEMA_VERSION
        }))
    })();
    let _ = connection.execute_batch(if result.is_ok() { "COMMIT" } else { "ROLLBACK" });
    result
}

fn load_native_graph(
    connection: &rusqlite::Connection,
    scope: NativeGraphScope,
    has_repository_base: bool,
) -> std::result::Result<NativeGraph, AgentError> {
    if has_repository_base {
        return load_native_overlay_graph(connection, scope);
    }
    let nodes = match scope {
        NativeGraphScope::Symbol => native_graph_nodes(
            connection,
            "SELECT id, stable_key FROM semantic_symbols ORDER BY id",
            true,
        )?,
        NativeGraphScope::File => native_graph_nodes(
            connection,
            "SELECT id, path FROM semantic_files ORDER BY id",
            true,
        )?,
        NativeGraphScope::Package => {
            let package_key = native_graph_package_key_sql("package_name");
            native_graph_nodes(
                connection,
                &format!(
                    "SELECT NULL, {package_key} FROM semantic_files GROUP BY 2 ORDER BY 2"
                ),
                false,
            )?
        }
        NativeGraphScope::Module => native_graph_nodes(
            connection,
            "SELECT NULL, module_name FROM semantic_files
               WHERE module_name IS NOT NULL GROUP BY module_name ORDER BY module_name",
            false,
        )?,
    };
    let positions = nodes
        .iter()
        .enumerate()
        .map(|(index, node)| (node.key.clone(), index))
        .collect::<BTreeMap<_, _>>();
    let numeric_positions = nodes
        .iter()
        .enumerate()
        .filter_map(|(index, node)| node.database_id.map(|id| (id, index)))
        .collect::<BTreeMap<_, _>>();
    let edges = match scope {
        NativeGraphScope::Symbol => {
            let mut statement = connection
                .prepare(
                    "SELECT source_id, target_id, kind, context
                       FROM semantic_edge_occurrences ORDER BY id",
                )
                .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
            statement
                .query_map([], |row| {
                    Ok((
                        row.get::<_, i64>(0)? as u64,
                        row.get::<_, i64>(1)? as u64,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        1.0,
                    ))
                })
                .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
                .collect::<rusqlite::Result<Vec<_>>>()
                .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
                .into_iter()
                .filter_map(|(source, target, kind, context, weight)| {
                    Some(NativeGraphEdge {
                        source: *numeric_positions.get(&source)?,
                        target: *numeric_positions.get(&target)?,
                        kind,
                        context,
                        weight,
                    })
                })
                .collect()
        }
        NativeGraphScope::File => native_graph_numeric_quotient_edges(
            connection,
            "semantic_file_quotient",
            &numeric_positions,
        )?,
        NativeGraphScope::Package => {
            let source_package = native_graph_package_key_sql("source_file.package_name");
            let target_package = native_graph_package_key_sql("target_file.package_name");
            native_graph_text_edges(
                connection,
                &format!(
                    "SELECT {source_package}, {target_package}, edges.kind, edges.context, COUNT(*)
                       FROM semantic_edge_occurrences edges
                       JOIN semantic_symbols source ON source.id = edges.source_id
                       JOIN semantic_files source_file ON source_file.id = source.file_id
                       JOIN semantic_symbols target ON target.id = edges.target_id
                       JOIN semantic_files target_file ON target_file.id = target.file_id
                       GROUP BY 1, 2, edges.kind, edges.context
                       ORDER BY 1, 2, 3, 4"
                ),
                &positions,
            )?
        }
        NativeGraphScope::Module => native_graph_text_edges(
            connection,
            "SELECT source_container, target_container, kind, context, weight
               FROM semantic_module_quotient
               ORDER BY source_container, target_container, kind, context",
            &positions,
        )?,
    };
    Ok(native_graph_to_csr(nodes, edges))
}

fn native_graph_overlay_cte() -> &'static str {
    r#"WITH
       effective_file_rows AS (
           SELECT path, package_name, module_name
           FROM semantic_files overlay
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = overlay.path
               )
           UNION ALL
           SELECT base.path, base.package_name, base.module_name
           FROM repository_base.semantic_files base
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = base.path
               )
             AND NOT EXISTS (
                   SELECT 1 FROM semantic_files overlay
                   WHERE overlay.path = base.path AND overlay.refresh_status != 'CACHED'
               )
       ),
       effective_files AS (
           SELECT path, MAX(package_name) AS package_name, MAX(module_name) AS module_name
           FROM effective_file_rows
           GROUP BY path
       ),
       effective_symbol_rows AS (
           SELECT symbols.id * 2 + 1 AS encoded_id,
                  symbols.stable_key, symbols.kind, symbols.name, files.path AS file_path
           FROM semantic_symbols symbols
           JOIN semantic_files files ON files.id = symbols.file_id
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = files.path
               )
           UNION ALL
           SELECT symbols.id * 2 AS encoded_id,
                  symbols.stable_key, symbols.kind, symbols.name, files.path AS file_path
           FROM repository_base.semantic_symbols symbols
           JOIN repository_base.semantic_files files ON files.id = symbols.file_id
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = files.path
               )
             AND NOT EXISTS (
                   SELECT 1 FROM semantic_files overlay
                   WHERE overlay.path = files.path AND overlay.refresh_status != 'CACHED'
               )
             AND NOT EXISTS (
                   SELECT 1 FROM semantic_symbols overlay
                   WHERE overlay.stable_key = symbols.stable_key
               )
       ),
       effective_symbols AS (
           SELECT stable_key, file_path FROM effective_symbol_rows
       ),
       raw_edge_occurrences AS (
           SELECT source.stable_key AS source_key, target.stable_key AS target_key,
                  edges.kind, edges.context
           FROM semantic_edge_occurrences edges
           JOIN semantic_symbols source ON source.id = edges.source_id
           JOIN semantic_symbols target ON target.id = edges.target_id
           JOIN semantic_files source_file ON source_file.id = edges.source_file_id
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = source_file.path
               )
           UNION ALL
           SELECT source.stable_key AS source_key, target.stable_key AS target_key,
                  edges.kind, edges.context
           FROM repository_base.semantic_edge_occurrences edges
           JOIN repository_base.semantic_symbols source ON source.id = edges.source_id
           JOIN repository_base.semantic_symbols target ON target.id = edges.target_id
           JOIN repository_base.semantic_files source_file ON source_file.id = edges.source_file_id
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = source_file.path
               )
             AND NOT EXISTS (
                   SELECT 1 FROM semantic_files overlay
                   WHERE overlay.path = source_file.path AND overlay.refresh_status != 'CACHED'
               )
       )"#
}

fn load_native_overlay_graph(
    connection: &rusqlite::Connection,
    scope: NativeGraphScope,
) -> std::result::Result<NativeGraph, AgentError> {
    let node_sql = match scope {
        NativeGraphScope::Symbol => format!(
            "{} SELECT encoded_id, stable_key FROM effective_symbol_rows ORDER BY stable_key",
            native_graph_overlay_cte(),
        ),
        NativeGraphScope::File => format!(
            "{} SELECT NULL, path FROM effective_files ORDER BY path",
            native_graph_overlay_cte(),
        ),
        NativeGraphScope::Package => {
            let package_key = native_graph_package_key_sql("package_name");
            format!(
                "{} SELECT NULL, {package_key} FROM effective_files GROUP BY 2 ORDER BY 2",
                native_graph_overlay_cte(),
            )
        }
        NativeGraphScope::Module => format!(
            "{} SELECT NULL, module_name FROM effective_files
                WHERE module_name IS NOT NULL GROUP BY module_name ORDER BY module_name",
            native_graph_overlay_cte(),
        ),
    };
    let nodes = native_graph_nodes(
        connection,
        &node_sql,
        scope == NativeGraphScope::Symbol,
    )?;
    let positions = nodes
        .iter()
        .enumerate()
        .map(|(index, node)| (node.key.clone(), index))
        .collect::<BTreeMap<_, _>>();
    let edge_projection = match scope {
        NativeGraphScope::Symbol => {
            "source.stable_key, target.stable_key, edges.kind, edges.context, 1.0".to_string()
        }
        NativeGraphScope::File => {
            "source.file_path, target.file_path, edges.kind, edges.context, COUNT(*)".to_string()
        }
        NativeGraphScope::Package => format!(
            "{}, {}, edges.kind, edges.context, COUNT(*)",
            native_graph_package_key_sql("source_file.package_name"),
            native_graph_package_key_sql("target_file.package_name"),
        ),
        NativeGraphScope::Module => {
            "source_file.module_name, target_file.module_name, edges.kind, edges.context, COUNT(*)"
                .to_string()
        }
    };
    let container_joins = match scope {
        NativeGraphScope::Package | NativeGraphScope::Module => {
            "JOIN effective_files source_file ON source_file.path = source.file_path
             JOIN effective_files target_file ON target_file.path = target.file_path"
        }
        NativeGraphScope::Symbol | NativeGraphScope::File => "",
    };
    let non_null_filter = match scope {
        NativeGraphScope::Package => "",
        NativeGraphScope::Module => {
            "AND source_file.module_name IS NOT NULL AND target_file.module_name IS NOT NULL"
        }
        NativeGraphScope::Symbol | NativeGraphScope::File => "",
    };
    let grouping = if scope == NativeGraphScope::Symbol {
        ""
    } else {
        "GROUP BY 1, 2, edges.kind, edges.context"
    };
    let edge_sql = format!(
        "{} SELECT {}
            FROM raw_edge_occurrences edges
            JOIN effective_symbols source ON source.stable_key = edges.source_key
            JOIN effective_symbols target ON target.stable_key = edges.target_key
            {}
            WHERE 1 = 1 {}
            {}
            ORDER BY 1, 2, 3, 4",
        native_graph_overlay_cte(),
        edge_projection,
        container_joins,
        non_null_filter,
        grouping,
    );
    let mut statement = connection
        .prepare(&edge_sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, f64>(4)?,
            ))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let edges = rows
        .into_iter()
        .filter_map(|(source, target, kind, context, weight)| {
            Some(NativeGraphEdge {
                source: *positions.get(&source)?,
                target: *positions.get(&target)?,
                kind,
                context,
                weight,
            })
        })
        .collect();
    Ok(native_graph_to_csr(nodes, edges))
}

fn native_graph_nodes(
    connection: &rusqlite::Connection,
    sql: &str,
    numeric: bool,
) -> std::result::Result<Vec<NativeGraphNode>, AgentError> {
    let mut statement = connection
        .prepare(sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    statement
        .query_map([], |row| {
            Ok(NativeGraphNode {
                database_id: numeric
                    .then(|| row.get::<_, i64>(0).map(|value| value as u64))
                    .transpose()?,
                key: row.get(1)?,
            })
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))
}

fn native_graph_numeric_quotient_edges(
    connection: &rusqlite::Connection,
    view: &str,
    positions: &BTreeMap<u64, usize>,
) -> std::result::Result<Vec<NativeGraphEdge>, AgentError> {
    let sql = format!(
        "SELECT source_container_id, target_container_id, kind, context, weight FROM {view} \
         ORDER BY source_container_id, target_container_id, kind, context"
    );
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, i64>(0)? as u64,
                row.get::<_, i64>(1)? as u64,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, f64>(4)?,
            ))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    Ok(rows
        .into_iter()
        .filter_map(|(source, target, kind, context, weight)| {
            Some(NativeGraphEdge {
                source: *positions.get(&source)?,
                target: *positions.get(&target)?,
                kind,
                context,
                weight,
            })
        })
        .collect())
}

fn native_graph_package_key_sql(column: &str) -> String {
    format!("COALESCE({column}, '{NATIVE_GRAPH_ROOT_PACKAGE_KEY}')")
}

fn native_graph_text_edges(
    connection: &rusqlite::Connection,
    sql: &str,
    positions: &BTreeMap<String, usize>,
) -> std::result::Result<Vec<NativeGraphEdge>, AgentError> {
    let mut statement = connection
        .prepare(sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, f64>(4)?,
            ))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    Ok(rows
        .into_iter()
        .filter_map(|(source, target, kind, context, weight)| {
            Some(NativeGraphEdge {
                source: *positions.get(&source)?,
                target: *positions.get(&target)?,
                kind,
                context,
                weight,
            })
        })
        .collect())
}

fn native_graph_to_csr(nodes: Vec<NativeGraphNode>, edges: Vec<NativeGraphEdge>) -> NativeGraph {
    let mut rows = vec![BTreeMap::<usize, f64>::new(); nodes.len()];
    for edge in &edges {
        *rows[edge.source].entry(edge.target).or_default() += edge.weight;
    }
    let mut offsets = Vec::with_capacity(nodes.len() + 1);
    let mut targets = Vec::new();
    let mut weights = Vec::new();
    offsets.push(0);
    for row in rows {
        for (target, weight) in row {
            targets.push(target);
            weights.push(weight);
        }
        offsets.push(targets.len());
    }
    NativeGraph {
        nodes,
        edges,
        offsets,
        targets,
        weights,
    }
}

fn native_connected_components(graph: &NativeGraph) -> Vec<usize> {
    let mut undirected = vec![Vec::new(); graph.nodes.len()];
    for edge in &graph.edges {
        undirected[edge.source].push(edge.target);
        undirected[edge.target].push(edge.source);
    }
    for row in &mut undirected {
        row.sort_unstable();
        row.dedup();
    }
    let mut component = vec![usize::MAX; graph.nodes.len()];
    let mut next_component = 0;
    for root in 0..graph.nodes.len() {
        if component[root] != usize::MAX {
            continue;
        }
        component[root] = next_component;
        let mut queue = std::collections::VecDeque::from([root]);
        while let Some(node) = queue.pop_front() {
            for &target in &undirected[node] {
                if component[target] == usize::MAX {
                    component[target] = next_component;
                    queue.push_back(target);
                }
            }
        }
        next_component += 1;
    }
    component
}

fn native_tarjan_scc(graph: &NativeGraph) -> Vec<usize> {
    #[derive(Clone, Copy)]
    struct VisitFrame {
        node: usize,
        next_edge: usize,
    }

    let count = graph.nodes.len();
    let mut next_index = 0;
    let mut indices = vec![usize::MAX; count];
    let mut lowlink = vec![0; count];
    let mut stack = Vec::new();
    let mut on_stack = vec![false; count];
    let mut components = Vec::new();

    for root in 0..count {
        if indices[root] != usize::MAX {
            continue;
        }
        indices[root] = next_index;
        lowlink[root] = next_index;
        next_index += 1;
        stack.push(root);
        on_stack[root] = true;
        let mut visits = vec![VisitFrame {
            node: root,
            next_edge: graph.offsets[root],
        }];

        while let Some(frame) = visits.last_mut() {
            let node = frame.node;
            if frame.next_edge < graph.offsets[node + 1] {
                let target = graph.targets[frame.next_edge];
                frame.next_edge += 1;
                if indices[target] == usize::MAX {
                    indices[target] = next_index;
                    lowlink[target] = next_index;
                    next_index += 1;
                    stack.push(target);
                    on_stack[target] = true;
                    visits.push(VisitFrame {
                        node: target,
                        next_edge: graph.offsets[target],
                    });
                } else if on_stack[target] {
                    lowlink[node] = lowlink[node].min(indices[target]);
                }
                continue;
            }

            visits.pop();
            if lowlink[node] == indices[node] {
                let mut component = Vec::new();
                while let Some(member) = stack.pop() {
                    on_stack[member] = false;
                    component.push(member);
                    if member == node {
                        break;
                    }
                }
                component.sort_unstable();
                components.push(component);
            }
            if let Some(parent) = visits.last() {
                lowlink[parent.node] = lowlink[parent.node].min(lowlink[node]);
            }
        }
    }
    components.sort_by_key(|component| component[0]);
    let mut membership = vec![0; count];
    for (component_id, component) in components.iter().enumerate() {
        for &node in component {
            membership[node] = component_id;
        }
    }
    membership
}

fn native_condensation_topological_order(
    graph: &NativeGraph,
    membership: &[usize],
) -> Vec<usize> {
    let component_count = membership.iter().copied().max().map_or(0, |value| value + 1);
    let mut outgoing = vec![BTreeSet::new(); component_count];
    let mut incoming = vec![0usize; component_count];
    for edge in &graph.edges {
        let source = membership[edge.source];
        let target = membership[edge.target];
        if source != target && outgoing[source].insert(target) {
            incoming[target] += 1;
        }
    }
    let mut ready = (0..component_count)
        .filter(|&component| incoming[component] == 0)
        .collect::<BTreeSet<_>>();
    let mut order = Vec::with_capacity(component_count);
    while let Some(component) = ready.pop_first() {
        order.push(component);
        for &target in &outgoing[component] {
            incoming[target] -= 1;
            if incoming[target] == 0 {
                ready.insert(target);
            }
        }
    }
    order
}

fn native_weighted_leiden(graph: &NativeGraph, resolution: f64) -> Vec<usize> {
    let mut adjacency = native_undirected_adjacency(graph);
    let mut original_to_current = (0..graph.nodes.len()).collect::<Vec<_>>();
    loop {
        let moved = native_leiden_local_move(&adjacency, resolution);
        let refined = native_leiden_refine(&adjacency, &moved);
        let (partition, community_count) = native_compress_partition(&refined);
        for current in &mut original_to_current {
            *current = partition[*current];
        }
        if community_count == adjacency.len() || community_count <= 1 {
            break;
        }
        adjacency = native_leiden_aggregate(&adjacency, &partition, community_count);
    }
    native_compress_partition(&original_to_current).0
}

fn native_undirected_adjacency(graph: &NativeGraph) -> Vec<BTreeMap<usize, f64>> {
    let mut adjacency = vec![BTreeMap::new(); graph.nodes.len()];
    for edge in &graph.edges {
        *adjacency[edge.source].entry(edge.target).or_default() += edge.weight;
        if edge.source != edge.target {
            *adjacency[edge.target].entry(edge.source).or_default() += edge.weight;
        }
    }
    adjacency
}

fn native_leiden_local_move(
    adjacency: &[BTreeMap<usize, f64>],
    resolution: f64,
) -> Vec<usize> {
    let count = adjacency.len();
    let degree = adjacency
        .iter()
        .map(|row| row.values().sum::<f64>())
        .collect::<Vec<_>>();
    let total_weight = degree.iter().sum::<f64>().max(f64::EPSILON);
    let mut membership = (0..count).collect::<Vec<_>>();
    let mut community_weight = degree.clone();
    for _ in 0..100 {
        let mut changed = false;
        for node in 0..count {
            let current = membership[node];
            community_weight[current] -= degree[node];
            let mut by_community = BTreeMap::<usize, f64>::new();
            for (&target, &weight) in &adjacency[node] {
                *by_community.entry(membership[target]).or_default() += weight;
            }
            by_community.entry(current).or_default();
            let mut best = current;
            let mut best_score = by_community.get(&current).copied().unwrap_or_default()
                - resolution * degree[node] * community_weight[current] / total_weight;
            for (candidate, internal_weight) in by_community {
                let score = internal_weight
                    - resolution * degree[node] * community_weight[candidate] / total_weight;
                if score > best_score + 1e-12
                    || ((score - best_score).abs() <= 1e-12 && candidate < best)
                {
                    best = candidate;
                    best_score = score;
                }
            }
            membership[node] = best;
            community_weight[best] += degree[node];
            changed |= best != current;
        }
        if !changed {
            break;
        }
    }
    membership
}

fn native_leiden_refine(
    adjacency: &[BTreeMap<usize, f64>],
    membership: &[usize],
) -> Vec<usize> {
    let mut refined = vec![usize::MAX; membership.len()];
    let mut next = 0;
    for root in 0..membership.len() {
        if refined[root] != usize::MAX {
            continue;
        }
        refined[root] = next;
        let community = membership[root];
        let mut queue = std::collections::VecDeque::from([root]);
        while let Some(node) = queue.pop_front() {
            for &target in adjacency[node].keys() {
                if membership[target] == community && refined[target] == usize::MAX {
                    refined[target] = next;
                    queue.push_back(target);
                }
            }
        }
        next += 1;
    }
    refined
}

fn native_compress_partition(partition: &[usize]) -> (Vec<usize>, usize) {
    let mut ids = BTreeMap::new();
    let mut next = 0;
    let compressed = partition
        .iter()
        .map(|community| {
            *ids.entry(*community).or_insert_with(|| {
                let value = next;
                next += 1;
                value
            })
        })
        .collect();
    (compressed, next)
}

fn native_leiden_aggregate(
    adjacency: &[BTreeMap<usize, f64>],
    partition: &[usize],
    community_count: usize,
) -> Vec<BTreeMap<usize, f64>> {
    let mut aggregated = vec![BTreeMap::new(); community_count];
    for (source, row) in adjacency.iter().enumerate() {
        for (&target, &weight) in row {
            *aggregated[partition[source]]
                .entry(partition[target])
                .or_default() += weight;
        }
    }
    aggregated
}

fn native_graph_neighbors(
    graph: &NativeGraph,
    generation: u64,
    scope: NativeGraphScope,
    key: &str,
) -> std::result::Result<Value, AgentError> {
    let index = graph
        .nodes
        .iter()
        .position(|node| node.key == key)
        .ok_or_else(|| agent_error("NATIVE_GRAPH_SYMBOL_NOT_FOUND", format!("Graph node not found: {key}")))?;
    let outgoing = graph
        .edges
        .iter()
        .filter(|edge| edge.source == index)
        .map(|edge| {
            json!({
                "target": graph.nodes[edge.target].key,
                "kind": edge.kind,
                "context": edge.context,
                "weight": edge.weight
            })
        })
        .collect::<Vec<_>>();
    let incoming = graph
        .edges
        .iter()
        .filter(|edge| edge.target == index)
        .map(|edge| {
            json!({
                "source": graph.nodes[edge.source].key,
                "kind": edge.kind,
                "context": edge.context,
                "weight": edge.weight
            })
        })
        .collect::<Vec<_>>();
    Ok(json!({
        "type": "KAST_NATIVE_GRAPH_NEIGHBORS",
        "scope": scope,
        "generation": generation,
        "key": key,
        "outgoing": outgoing,
        "incoming": incoming,
        "schemaVersion": SCHEMA_VERSION
    }))
}

fn native_graph_measurements(
    connection: &rusqlite::Connection,
    database: &Path,
    load_nanos: u128,
    compute_nanos: u128,
) -> std::result::Result<Value, AgentError> {
    let mut samples = Vec::with_capacity(21);
    for _ in 0..21 {
        let started = std::time::Instant::now();
        connection
            .prepare("SELECT id FROM semantic_symbols WHERE id > ? ORDER BY id LIMIT 100")
            .and_then(|mut statement| {
                statement
                    .query_map([0_i64], |row| row.get::<_, i64>(0))?
                    .collect::<rusqlite::Result<Vec<_>>>()
                    .map(|_| ())
            })
            .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
        samples.push(started.elapsed().as_micros());
    }
    samples.sort_unstable();
    let p95 = samples[(samples.len() * 95).div_ceil(100).saturating_sub(1)];
    Ok(json!({
        "loadNanos": load_nanos,
        "computeNanos": compute_nanos,
        "databaseBytes": std::fs::metadata(database).map(|metadata| metadata.len()).unwrap_or(0),
        "peakRssBytes": native_graph_peak_rss_bytes(),
        "queryP95Micros": p95
    }))
}

#[cfg(unix)]
fn native_graph_peak_rss_bytes() -> u64 {
    let mut usage = std::mem::MaybeUninit::<libc::rusage>::zeroed();
    if unsafe { libc::getrusage(libc::RUSAGE_SELF, usage.as_mut_ptr()) } != 0 {
        return 0;
    }
    let maximum = unsafe { usage.assume_init() }.ru_maxrss.max(0) as u64;
    if cfg!(target_os = "macos") {
        maximum
    } else {
        maximum.saturating_mul(1024)
    }
}

#[cfg(not(unix))]
fn native_graph_peak_rss_bytes() -> u64 {
    0
}

fn native_graph_sql_error(code: &str, error: rusqlite::Error) -> AgentError {
    agent_error(code, format!("Native graph SQLite query failed: {error}"))
}

#[cfg(test)]
mod native_graph_tests {
    use super::*;

    fn fixture(node_count: usize, edges: &[(usize, usize, f64)]) -> NativeGraph {
        native_graph_to_csr(
            (0..node_count)
                .map(|node| NativeGraphNode {
                    database_id: Some(node as u64 + 1),
                    key: format!("n{node}"),
                })
                .collect(),
            edges
                .iter()
                .enumerate()
                .map(|(index, &(source, target, weight))| NativeGraphEdge {
                    source,
                    target,
                    kind: format!("K{index}"),
                    context: "NONE".to_string(),
                    weight,
                })
                .collect(),
        )
    }

    #[test]
    fn native_graph_resumed_nodes_require_generation_before_database_access() {
        let temp = tempfile::tempdir().unwrap();
        let args = AgentNativeGraphArgs {
            runtime: AgentRuntimeArgs::default(),
            database: Some(temp.path().join("missing.db")),
            scope: NativeGraphScope::Symbol,
            operation: NativeGraphOperation::Nodes,
            symbol: None,
            generation: None,
            after_id: 1,
            limit: 100,
            resolution: 1.0,
        };

        let error = native_graph_result(&args).unwrap_err();

        assert_eq!(error.code, "AGENT_USAGE");
        assert!(error.message.contains("--generation"));
    }

    #[test]
    fn native_graph_ignores_legacy_overlay_descriptor_without_repository_base() {
        let temp = tempfile::tempdir().unwrap();
        let database = temp.path().join("source-index.db");
        let connection = rusqlite::Connection::open(&database).unwrap();
        std::fs::write(temp.path().join("repository-overlay.json"), "{}").unwrap();

        assert!(!native_graph_attach_repository_base(&connection, &database).unwrap());
    }

    #[test]
    fn native_graph_tarjan_condensation_and_components_are_deterministic() {
        let graph = fixture(
            6,
            &[
                (0, 1, 1.0),
                (1, 0, 1.0),
                (1, 2, 1.0),
                (2, 3, 1.0),
                (3, 2, 1.0),
                (4, 5, 1.0),
            ],
        );
        assert_eq!(native_connected_components(&graph), vec![0, 0, 0, 0, 1, 1]);
        let first = native_tarjan_scc(&graph);
        assert_eq!(first, native_tarjan_scc(&graph));
        assert_eq!(
            native_condensation_topological_order(&graph, &first),
            native_condensation_topological_order(&graph, &first)
        );
    }

    #[test]
    fn native_graph_tarjan_handles_deep_acyclic_chain_without_process_stack_growth() {
        const CHILD_ENV: &str = "KAST_NATIVE_GRAPH_DEEP_TARJAN_CHILD";
        if std::env::var_os(CHILD_ENV).is_some() {
            let node_count = 50_000;
            let edges = (0..node_count - 1)
                .map(|node| (node, node + 1, 1.0))
                .collect::<Vec<_>>();
            let membership = native_tarjan_scc(&fixture(node_count, &edges));
            assert_eq!(membership.len(), node_count);
            return;
        }

        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "agent::native_graph_tests::native_graph_tarjan_handles_deep_acyclic_chain_without_process_stack_growth",
            ])
            .env(CHILD_ENV, "1")
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "deep Tarjan child failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    #[test]
    fn native_graph_weighted_leiden_is_deterministic_and_keeps_refined_communities_connected() {
        let graph = fixture(
            6,
            &[
                (0, 1, 10.0),
                (1, 2, 10.0),
                (2, 0, 10.0),
                (3, 4, 10.0),
                (4, 5, 10.0),
                (5, 3, 10.0),
                (2, 3, 0.1),
            ],
        );
        let first = native_weighted_leiden(&graph, 1.0);
        assert_eq!(first, native_weighted_leiden(&graph, 1.0));
        assert_eq!(first[0], first[1]);
        assert_eq!(first[1], first[2]);
        assert_eq!(first[3], first[4]);
        assert_eq!(first[4], first[5]);
        assert_ne!(first[2], first[3]);
    }

    #[test]
    fn native_graph_csr_preserves_parallel_typed_edge_occurrence_weight() {
        let graph = fixture(2, &[(0, 1, 2.0), (0, 1, 3.0)]);
        assert_eq!(graph.edges.len(), 2);
        assert_eq!(graph.offsets, vec![0, 1, 1]);
        assert_eq!(graph.targets, vec![1]);
        assert_eq!(graph.weights, vec![5.0]);
    }

    #[test]
    fn native_graph_package_scope_includes_root_package_files() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        connection
            .execute_batch(
                "ATTACH DATABASE ':memory:' AS repository_base;
                 CREATE TABLE semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                     refresh_status TEXT
                 );
                 CREATE TABLE semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
                 );
                 CREATE TABLE semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                     source_file_id INTEGER, kind TEXT, context TEXT
                 );
                 CREATE VIEW semantic_package_quotient AS
                     SELECT source_file.package_name AS source_container,
                            target_file.package_name AS target_container,
                            edges.kind, edges.context, COUNT(*) AS weight
                     FROM semantic_edge_occurrences edges
                     JOIN semantic_symbols source ON source.id = edges.source_id
                     JOIN semantic_symbols target ON target.id = edges.target_id
                     JOIN semantic_files source_file ON source_file.id = source.file_id
                     JOIN semantic_files target_file ON target_file.id = target.file_id
                     WHERE source_file.package_name IS NOT NULL
                       AND target_file.package_name IS NOT NULL
                     GROUP BY 1, 2, edges.kind, edges.context;
                 CREATE TABLE repository_overlay_tombstones(path TEXT PRIMARY KEY) WITHOUT ROWID;
                 CREATE TABLE repository_base.semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                     refresh_status TEXT
                 );
                 CREATE TABLE repository_base.semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
                 );
                 CREATE TABLE repository_base.semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                     source_file_id INTEGER, kind TEXT, context TEXT
                 );
                 INSERT INTO semantic_files VALUES
                     (1, 'Root.kt', NULL, 'main', 'REFRESHED'),
                     (2, 'Named.kt', 'demo', 'main', 'REFRESHED');
                 INSERT INTO semantic_symbols VALUES
                     (1, 'root', 'CLASS', 'Root', 1),
                     (2, 'named', 'CLASS', 'Named', 2);
                 INSERT INTO semantic_edge_occurrences VALUES
                     (1, 1, 2, 1, 'REFERENCES', 'NONE'),
                     (2, 2, 1, 2, 'REFERENCES', 'NONE');",
            )
            .unwrap();

        for graph in [
            load_native_graph(&connection, NativeGraphScope::Package, false).unwrap(),
            load_native_overlay_graph(&connection, NativeGraphScope::Package).unwrap(),
        ] {
            assert_eq!(
                graph
                    .nodes
                    .iter()
                    .map(|node| node.key.as_str())
                    .collect::<Vec<_>>(),
                vec!["<root>", "demo"]
            );
            assert_eq!(
                graph
                    .edges
                    .iter()
                    .map(|edge| (
                        graph.nodes[edge.source].key.as_str(),
                        graph.nodes[edge.target].key.as_str(),
                        edge.weight,
                    ))
                    .collect::<Vec<_>>(),
                vec![("<root>", "demo", 1.0), ("demo", "<root>", 1.0)]
            );
        }
    }

    #[test]
    fn native_graph_base_plus_overlay_equals_clean_rebuild() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        connection.execute_batch(
            "ATTACH DATABASE ':memory:' AS repository_base;
             CREATE TABLE semantic_files(
                 id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                 refresh_status TEXT
             );
             CREATE TABLE semantic_symbols(
                 id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
             );
             CREATE TABLE semantic_edge_occurrences(
                 id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                 source_file_id INTEGER, kind TEXT, context TEXT
             );
             CREATE TABLE repository_overlay_tombstones(path TEXT PRIMARY KEY) WITHOUT ROWID;
             CREATE TABLE repository_base.semantic_files(
                 id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                 refresh_status TEXT
             );
             CREATE TABLE repository_base.semantic_symbols(
                 id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
             );
             CREATE TABLE repository_base.semantic_edge_occurrences(
                 id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                 source_file_id INTEGER, kind TEXT, context TEXT
             );
             INSERT INTO repository_base.semantic_files VALUES
                 (1, 'A.kt', 'demo', 'main', 'REFRESHED'),
                 (2, 'B.kt', 'demo', 'main', 'REFRESHED');
             INSERT INTO repository_base.semantic_symbols VALUES
                 (1, 'old', 'CLASS', 'Old', 1),
                 (2, 'b', 'CLASS', 'B', 2);
             INSERT INTO repository_base.semantic_edge_occurrences VALUES
                 (1, 1, 2, 1, 'REFERENCES', 'NONE');
             INSERT INTO repository_overlay_tombstones VALUES ('A.kt');
             INSERT INTO semantic_files VALUES
                 (1, 'A.kt', 'demo', 'main', 'REFRESHED');
             INSERT INTO semantic_symbols VALUES
                 (1, 'new', 'CLASS', 'New', 1),
                 (2, 'b', 'CLASS', 'B', 2);
             INSERT INTO semantic_files VALUES
                 (2, 'B.kt', NULL, NULL, 'CACHED');
             INSERT INTO semantic_edge_occurrences VALUES
                 (1, 1, 2, 1, 'REFERENCES', 'NONE');",
        )
        .unwrap();

        let tombstoned = load_native_overlay_graph(&connection, NativeGraphScope::Symbol).unwrap();
        assert_eq!(
            tombstoned
                .nodes
                .iter()
                .map(|node| node.key.as_str())
                .collect::<Vec<_>>(),
            vec!["b"]
        );
        assert!(tombstoned.edges.is_empty());
        connection
            .execute(
                "DELETE FROM repository_overlay_tombstones WHERE path = 'A.kt'",
                [],
            )
            .unwrap();

        let overlay = load_native_overlay_graph(&connection, NativeGraphScope::Symbol).unwrap();
        let clean = native_graph_to_csr(
            vec![
                NativeGraphNode {
                    database_id: None,
                    key: "b".to_string(),
                },
                NativeGraphNode {
                    database_id: None,
                    key: "new".to_string(),
                },
            ],
            vec![NativeGraphEdge {
                source: 1,
                target: 0,
                kind: "REFERENCES".to_string(),
                context: "NONE".to_string(),
                weight: 1.0,
            }],
        );

        assert_eq!(
            overlay
                .nodes
                .iter()
                .map(|node| &node.key)
                .collect::<Vec<_>>(),
            clean
                .nodes
                .iter()
                .map(|node| &node.key)
                .collect::<Vec<_>>()
        );
        assert_eq!(overlay.offsets, clean.offsets);
        assert_eq!(overlay.targets, clean.targets);
        assert_eq!(overlay.weights, clean.weights);
    }

    #[cfg(unix)]
    #[test]
    fn native_graph_reports_process_peak_rss() {
        assert!(native_graph_peak_rss_bytes() > 0);
    }
}
