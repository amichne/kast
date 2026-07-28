
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
                "{} SELECT NULL, {package_key} FROM effective_files
                    WHERE package_name IS NOT NULL OR refresh_status != 'CACHED'
                    GROUP BY 2 ORDER BY 2",
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
            "source.stable_key AS source_container,
             target.stable_key AS target_container,
             edges.kind, edges.context, 1.0 AS weight"
                .to_string()
        }
        NativeGraphScope::File => {
            "source.file_path AS source_container,
             target.file_path AS target_container,
             edges.kind, edges.context, COUNT(*) AS weight"
                .to_string()
        }
        NativeGraphScope::Package => format!(
            "{} AS source_container,
             {} AS target_container,
             edges.kind, edges.context, COUNT(*) AS weight",
            native_graph_package_key_sql("source_file.package_name"),
            native_graph_package_key_sql("target_file.package_name"),
        ),
        NativeGraphScope::Module => {
            "source_file.module_name AS source_container,
             target_file.module_name AS target_container,
             edges.kind, edges.context, COUNT(*) AS weight"
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
        NativeGraphScope::Package => {
            "AND (source_file.package_name IS NOT NULL OR source_file.refresh_status != 'CACHED')
             AND (target_file.package_name IS NOT NULL OR target_file.refresh_status != 'CACHED')"
        }
        NativeGraphScope::Module => {
            "AND source_file.module_name IS NOT NULL AND target_file.module_name IS NOT NULL"
        }
        NativeGraphScope::Symbol | NativeGraphScope::File => "",
    };
    let edge_sql = format!(
        "{},
         typed_edges AS (
             SELECT {}
             FROM raw_edge_occurrences edges
             JOIN effective_symbols source ON source.stable_key = edges.source_key
             JOIN effective_symbols target ON target.stable_key = edges.target_key
             {}
             WHERE 1 = 1 {}
             {}
         )
         SELECT source_container, target_container, COUNT(*), SUM(weight)
         FROM typed_edges
         GROUP BY source_container, target_container
         ORDER BY source_container, target_container",
        native_graph_overlay_cte(),
        edge_projection,
        container_joins,
        non_null_filter,
        if scope == NativeGraphScope::Symbol {
            ""
        } else {
            "GROUP BY 1, 2, edges.kind, edges.context"
        },
    );
    let mut statement = connection
        .prepare(&edge_sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)? as usize,
                row.get::<_, f64>(3)?,
            ))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let edges = rows
        .into_iter()
        .filter_map(|(source, target, occurrence_count, weight)| {
            Some(NativeGraphEdge {
                source: *positions.get(&source)?,
                target: *positions.get(&target)?,
                occurrence_count,
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
        "SELECT source_container_id, target_container_id, COUNT(*), SUM(weight) FROM {view} \
         GROUP BY source_container_id, target_container_id \
         ORDER BY source_container_id, target_container_id"
    );
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, i64>(0)? as u64,
                row.get::<_, i64>(1)? as u64,
                row.get::<_, i64>(2)? as usize,
                row.get::<_, f64>(3)?,
            ))
        })
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| native_graph_sql_error("NATIVE_GRAPH_QUERY_FAILED", error))?;
    Ok(rows
        .into_iter()
        .filter_map(|(source, target, occurrence_count, weight)| {
            Some(NativeGraphEdge {
                source: *positions.get(&source)?,
                target: *positions.get(&target)?,
                occurrence_count,
                weight,
            })
        })
        .collect())
}

fn native_graph_package_key_sql(column: &str) -> String {
    format!("COALESCE({column}, '{NATIVE_GRAPH_ROOT_PACKAGE_KEY}')")
}
