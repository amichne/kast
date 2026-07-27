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
                    "SELECT NULL, {package_key} FROM semantic_files
                       WHERE package_name IS NOT NULL OR refresh_status != 'CACHED'
                       GROUP BY 2 ORDER BY 2"
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
                       WHERE (source_file.package_name IS NOT NULL OR source_file.refresh_status != 'CACHED')
                         AND (target_file.package_name IS NOT NULL OR target_file.refresh_status != 'CACHED')
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
           SELECT path, package_name, module_name, refresh_status
           FROM semantic_files overlay
           WHERE NOT EXISTS (
                   SELECT 1 FROM repository_overlay_tombstones tombstone
                   WHERE tombstone.path = overlay.path
               )
           UNION ALL
           SELECT base.path, base.package_name, base.module_name, base.refresh_status
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
           SELECT path, MAX(package_name) AS package_name, MAX(module_name) AS module_name,
                  MAX(refresh_status) AS refresh_status
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
