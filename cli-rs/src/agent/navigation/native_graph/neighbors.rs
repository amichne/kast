fn native_graph_neighbors(
    connection: &rusqlite::Connection,
    generation: u64,
    scope: NativeGraphScope,
    key: &str,
    has_repository_base: bool,
) -> std::result::Result<Value, AgentError> {
    connection
        .execute_batch("BEGIN")
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let result = (|| {
        if native_graph_generation(connection)? != generation {
            return Err(agent_error(
                "NATIVE_GRAPH_GENERATION_CHANGED",
                "Source-index generation changed before native graph neighbors were queried.",
            ));
        }
        let (outgoing, incoming) = match scope {
            NativeGraphScope::Symbol => {
                native_graph_symbol_neighbors(connection, key, has_repository_base)?
            }
            NativeGraphScope::File | NativeGraphScope::Package | NativeGraphScope::Module => {
                native_graph_container_neighbors(connection, scope, key, has_repository_base)?
            }
        };
        if native_graph_generation(connection)? != generation {
            return Err(agent_error(
                "NATIVE_GRAPH_GENERATION_CHANGED",
                "Source-index generation changed while native graph neighbors were queried.",
            ));
        }
        Ok(json!({
            "type": "KAST_NATIVE_GRAPH_NEIGHBORS",
            "scope": scope,
            "generation": generation,
            "key": key,
            "outgoing": outgoing,
            "incoming": incoming,
            "schemaVersion": SCHEMA_VERSION
        }))
    })();
    let _ = connection.execute_batch(if result.is_ok() { "COMMIT" } else { "ROLLBACK" });
    result
}

fn native_graph_container_neighbors(
    connection: &rusqlite::Connection,
    scope: NativeGraphScope,
    key: &str,
    has_repository_base: bool,
) -> std::result::Result<(Vec<Value>, Vec<Value>), AgentError> {
    let (exists_sql, outgoing_sql, incoming_sql) = if has_repository_base {
        native_graph_overlay_container_neighbor_sql(scope)
    } else {
        native_graph_base_container_neighbor_sql(scope, key)
    };
    native_graph_require_neighbor_node(connection, &exists_sql, key)?;
    Ok((
        native_graph_neighbor_rows(connection, &outgoing_sql, key, "target")?,
        native_graph_neighbor_rows(connection, &incoming_sql, key, "source")?,
    ))
}

fn native_graph_base_container_neighbor_sql(
    scope: NativeGraphScope,
    key: &str,
) -> (String, String, String) {
    let (exists_sql, source_key, target_key, source_filter, target_filter) = match scope {
        NativeGraphScope::File => (
            "SELECT EXISTS(SELECT 1 FROM semantic_files WHERE path = ?1)".to_string(),
            "source_file.path".to_string(),
            "target_file.path".to_string(),
            "source_file.path = ?1".to_string(),
            String::new(),
        ),
        NativeGraphScope::Package => {
            let (exists_filter, source_filter) = if key == NATIVE_GRAPH_ROOT_PACKAGE_KEY {
                (
                    "package_name IS NULL AND refresh_status != 'CACHED' AND ?1 = '<root>'",
                    "source_file.package_name IS NULL
                     AND source_file.refresh_status != 'CACHED'
                     AND ?1 = '<root>'",
                )
            } else {
                ("package_name = ?1", "source_file.package_name = ?1")
            };
            (
                format!(
                    "SELECT EXISTS(
                        SELECT 1 FROM semantic_files
                        WHERE {exists_filter}
                    )"
                ),
                native_graph_package_key_sql("source_file.package_name"),
                native_graph_package_key_sql("target_file.package_name"),
                source_filter.to_string(),
                "AND (target_file.package_name IS NOT NULL OR target_file.refresh_status != 'CACHED')"
                    .to_string(),
            )
        }
        NativeGraphScope::Module => (
            "SELECT EXISTS(
                SELECT 1 FROM semantic_files WHERE module_name = ?1
            )"
            .to_string(),
            "source_file.module_name".to_string(),
            "target_file.module_name".to_string(),
            "source_file.module_name = ?1".to_string(),
            "AND target_file.module_name IS NOT NULL".to_string(),
        ),
        NativeGraphScope::Symbol => unreachable!("symbol neighbors use symbol SQL"),
    };
    let outgoing_sql = format!(
        "SELECT {target_key}, edges.kind, edges.context, COUNT(*)
         FROM semantic_files source_file
         JOIN semantic_symbols source ON source.file_id = source_file.id
         JOIN semantic_edge_occurrences AS edges
              INDEXED BY idx_semantic_edges_source_kind_target
           ON edges.source_id = source.id
         JOIN semantic_symbols target ON target.id = edges.target_id
         JOIN semantic_files target_file ON target_file.id = target.file_id
         WHERE {source_filter} {target_filter}
         GROUP BY {target_key}, edges.kind, edges.context
         ORDER BY {target_key}, edges.kind, edges.context"
    );
    let incoming_source_filter = source_filter.replace("source_file", "target_file");
    let incoming_target_filter = target_filter.replace("target_file", "source_file");
    let incoming_sql = format!(
        "SELECT {source_key}, edges.kind, edges.context, COUNT(*)
         FROM semantic_files target_file
         JOIN semantic_symbols target ON target.file_id = target_file.id
         JOIN semantic_edge_occurrences AS edges
              INDEXED BY idx_semantic_edges_target_kind_source
           ON edges.target_id = target.id
         JOIN semantic_symbols source ON source.id = edges.source_id
         JOIN semantic_files source_file ON source_file.id = source.file_id
         WHERE {incoming_source_filter} {incoming_target_filter}
         GROUP BY {source_key}, edges.kind, edges.context
         ORDER BY {source_key}, edges.kind, edges.context"
    );
    (exists_sql, outgoing_sql, incoming_sql)
}

fn native_graph_overlay_container_neighbor_sql(
    scope: NativeGraphScope,
) -> (String, String, String) {
    let cte = native_graph_overlay_cte();
    let (exists_filter, source_key, target_key, source_filter, target_filter, container_joins) =
        match scope {
            NativeGraphScope::File => (
                "path = ?1".to_string(),
                "source.file_path".to_string(),
                "target.file_path".to_string(),
                "source.file_path = ?1".to_string(),
                String::new(),
                String::new(),
            ),
            NativeGraphScope::Package => (
                format!(
                    "{} = ?1 AND (package_name IS NOT NULL OR refresh_status != 'CACHED')",
                    native_graph_package_key_sql("package_name"),
                ),
                native_graph_package_key_sql("source_file.package_name"),
                native_graph_package_key_sql("target_file.package_name"),
                format!(
                    "{} = ?1
                     AND (source_file.package_name IS NOT NULL OR source_file.refresh_status != 'CACHED')",
                    native_graph_package_key_sql("source_file.package_name"),
                ),
                "AND (target_file.package_name IS NOT NULL OR target_file.refresh_status != 'CACHED')"
                    .to_string(),
                "JOIN effective_files source_file ON source_file.path = source.file_path
                 JOIN effective_files target_file ON target_file.path = target.file_path"
                    .to_string(),
            ),
            NativeGraphScope::Module => (
                "module_name = ?1".to_string(),
                "source_file.module_name".to_string(),
                "target_file.module_name".to_string(),
                "source_file.module_name = ?1".to_string(),
                "AND target_file.module_name IS NOT NULL".to_string(),
                "JOIN effective_files source_file ON source_file.path = source.file_path
                 JOIN effective_files target_file ON target_file.path = target.file_path"
                    .to_string(),
            ),
            NativeGraphScope::Symbol => unreachable!("symbol neighbors use symbol SQL"),
        };
    let exists_sql = format!(
        "{cte} SELECT EXISTS(
            SELECT 1 FROM effective_files WHERE {exists_filter}
        )"
    );
    let outgoing_sql = format!(
        "{cte} SELECT {target_key}, edges.kind, edges.context, COUNT(*)
         FROM raw_edge_occurrences edges
         JOIN effective_symbols source ON source.stable_key = edges.source_key
         JOIN effective_symbols target ON target.stable_key = edges.target_key
         {container_joins}
         WHERE {source_filter} {target_filter}
         GROUP BY {target_key}, edges.kind, edges.context
         ORDER BY {target_key}, edges.kind, edges.context"
    );
    let incoming_source_filter = source_filter.replace("source_file", "target_file");
    let incoming_target_filter = target_filter.replace("target_file", "source_file");
    let incoming_sql = format!(
        "{cte} SELECT {source_key}, edges.kind, edges.context, COUNT(*)
         FROM raw_edge_occurrences edges
         JOIN effective_symbols source ON source.stable_key = edges.source_key
         JOIN effective_symbols target ON target.stable_key = edges.target_key
         {container_joins}
         WHERE {incoming_source_filter} {incoming_target_filter}
         GROUP BY {source_key}, edges.kind, edges.context
         ORDER BY {source_key}, edges.kind, edges.context"
    );
    (exists_sql, outgoing_sql, incoming_sql)
}

fn native_graph_symbol_neighbors(
    connection: &rusqlite::Connection,
    key: &str,
    has_repository_base: bool,
) -> std::result::Result<(Vec<Value>, Vec<Value>), AgentError> {
    let (exists_sql, outgoing_sql, incoming_sql) = if has_repository_base {
        (
            format!(
                "{} SELECT EXISTS(
                    SELECT 1 FROM effective_symbols WHERE stable_key = ?1
                )",
                native_graph_overlay_cte(),
            ),
            format!(
                "{} SELECT target.stable_key, edges.kind, edges.context, 1.0
                    FROM raw_edge_occurrences edges
                    JOIN effective_symbols source ON source.stable_key = edges.source_key
                    JOIN effective_symbols target ON target.stable_key = edges.target_key
                    WHERE source.stable_key = ?1
                    ORDER BY target.stable_key, edges.kind, edges.context",
                native_graph_overlay_cte(),
            ),
            format!(
                "{} SELECT source.stable_key, edges.kind, edges.context, 1.0
                    FROM raw_edge_occurrences edges
                    JOIN effective_symbols source ON source.stable_key = edges.source_key
                    JOIN effective_symbols target ON target.stable_key = edges.target_key
                    WHERE target.stable_key = ?1
                    ORDER BY source.stable_key, edges.kind, edges.context",
                native_graph_overlay_cte(),
            ),
        )
    } else {
        (
            "SELECT EXISTS(
                SELECT 1 FROM semantic_symbols WHERE stable_key = ?1
            )"
            .to_string(),
            "SELECT target.stable_key, edges.kind, edges.context, 1.0
                FROM semantic_symbols source
                JOIN semantic_edge_occurrences edges ON edges.source_id = source.id
                JOIN semantic_symbols target ON target.id = edges.target_id
                WHERE source.stable_key = ?1
                ORDER BY edges.id"
                .to_string(),
            "SELECT source.stable_key, edges.kind, edges.context, 1.0
                FROM semantic_symbols target
                JOIN semantic_edge_occurrences edges ON edges.target_id = target.id
                JOIN semantic_symbols source ON source.id = edges.source_id
                WHERE target.stable_key = ?1
                ORDER BY edges.id"
                .to_string(),
        )
    };
    native_graph_require_neighbor_node(connection, &exists_sql, key)?;
    Ok((
        native_graph_neighbor_rows(connection, &outgoing_sql, key, "target")?,
        native_graph_neighbor_rows(connection, &incoming_sql, key, "source")?,
    ))
}

fn native_graph_require_neighbor_node(
    connection: &rusqlite::Connection,
    sql: &str,
    key: &str,
) -> std::result::Result<(), AgentError> {
    let exists = connection
        .query_row(sql, [key], |row| row.get::<_, bool>(0))
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    if exists {
        Ok(())
    } else {
        Err(agent_error(
            "NATIVE_GRAPH_SYMBOL_NOT_FOUND",
            format!("Graph node not found: {key}"),
        ))
    }
}

fn native_graph_neighbor_rows(
    connection: &rusqlite::Connection,
    sql: &str,
    key: &str,
    adjacent_field: &str,
) -> std::result::Result<Vec<Value>, AgentError> {
    let mut statement = connection
        .prepare(sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    statement
        .query_map([key], |row| {
            Ok(json!({
                adjacent_field: row.get::<_, String>(0)?,
                "kind": row.get::<_, String>(1)?,
                "context": row.get::<_, String>(2)?,
                "weight": row.get::<_, f64>(3)?
            }))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))
}
