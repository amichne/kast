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
