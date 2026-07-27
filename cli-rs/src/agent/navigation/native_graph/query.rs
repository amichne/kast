fn native_graph_result(args: &AgentNativeGraphArgs) -> std::result::Result<Value, AgentError> {
    if !args.file_paths.is_empty() || !args.removed_file_paths.is_empty() {
        return Err(agent_error(
            "AGENT_USAGE",
            "--file-path and --removed-file-path require --operation refresh.",
        ));
    }
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
        NativeGraphOperation::Refresh => {
            unreachable!("refresh returned before native graph database access")
        }
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
