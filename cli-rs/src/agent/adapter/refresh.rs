pub(crate) fn run_refresh(args: KastRefreshArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    if let Some(KastRefreshCommand::External { failure_ids }) = args.command {
        return run_external_refresh(workspace_root, failure_ids);
    }

    let inferred_scope = args.paths.is_empty();
    let mut requested_paths = if inferred_scope {
        let plan = crate::repository_intelligence::semantic_graph_refresh_plan(&workspace_root)
            .map_err(|error| {
                CliError::new(
                    "GRAPH_EVIDENCE_UNAVAILABLE",
                    format!("Cannot plan semantic graph refresh: {}", error.message),
                )
            })?;
        let mut file_paths = plan.file_paths;
        file_paths.extend(plan.removed_file_paths);
        if file_paths.is_empty() {
            file_paths = match changed_kotlin_files(&workspace_root)? {
                Ok(file_paths) => file_paths,
                Err(envelope) => return print_projected_value(envelope),
            };
        }
        file_paths
    } else {
        args.paths
            .into_iter()
            .map(|path| path.display().to_string())
            .collect()
    };
    requested_paths.sort();
    requested_paths.dedup();
    if requested_paths.is_empty() {
        return print_refresh_noop(&workspace_root);
    }
    let runtime_args = agent_runtime(workspace_root.clone());
    let file_paths = match agent::normalize_public_file_paths(&runtime_args, &requested_paths) {
        Ok(file_paths) => file_paths,
        Err(error) => return print_failure(&error.code, &error.message),
    };
    let refresh_response = raw_workspace_refresh(&workspace_root, &file_paths, &[])?;
    if let Some((code, message)) = rpc_failure(&refresh_response) {
        return print_failure(code, message);
    }
    let refresh_result = projected_result(&refresh_response)?;
    let refreshed_paths = string_array_field(refresh_result, "refreshedFiles")?;
    let removed_paths = string_array_field(refresh_result, "removedFiles")?;
    let externalizable_failures = refresh_relationship_failures(refresh_result, &refreshed_paths)?;

    let diagnostics = if refreshed_paths.is_empty() {
        json!({
            "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
            "cardinality": {"totalCount": 0, "returnedCount": 0, "truncated": false},
            "diagnostics": [],
        })
    } else {
        let envelope = projected_value(AgentCommand::Diagnostics(AgentDiagnosticsArgs {
            runtime: runtime_args.clone(),
            file_paths: refreshed_paths.clone(),
            skip_refresh: true,
            limit: 500,
            page_token: None,
            view: AgentDiagnosticsViewArgs::default(),
        }))?;
        if envelope.get("ok") != Some(&Value::Bool(true)) {
            let limitation = envelope
                .get("error")
                .and_then(|error| error.get("code"))
                .and_then(Value::as_str)
                .unwrap_or("DIAGNOSTICS_UNAVAILABLE");
            json!({
                "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
                "cardinality": {"totalCount": 0, "returnedCount": 0, "truncated": false},
                "diagnostics": [],
                "limitation": limitation,
            })
        } else {
            let result = projected_result(&envelope)?;
            json!({
                "severityCounts": required_field(result, "severityCounts")?,
                "cardinality": diagnostic_cardinality(result)?,
                "diagnostics": required_field(result, "diagnostics")?,
            })
        }
    };

    let mut graph_paths = refreshed_paths.clone();
    let mut graph_removed_paths = removed_paths.clone();
    if inferred_scope {
        match crate::repository_intelligence::semantic_graph_refresh_plan(&workspace_root) {
            Ok(plan) => {
                graph_paths.extend(normalize_planned_paths(&runtime_args, &plan.file_paths)?);
                graph_removed_paths.extend(normalize_planned_paths(
                    &runtime_args,
                    &plan.removed_file_paths,
                )?);
            }
            Err(error) => {
                return print_actionable_failure(
                    "GRAPH_EVIDENCE_UNAVAILABLE",
                    &error.message,
                    "kast refresh",
                );
            }
        }
    }
    let failed_paths = externalizable_failures
        .iter()
        .filter_map(|failure| failure.get("path").and_then(Value::as_str))
        .collect::<BTreeSet<_>>();
    graph_paths.retain(|path| !failed_paths.contains(path.as_str()));
    graph_paths.sort();
    graph_paths.dedup();
    graph_removed_paths.sort();
    graph_removed_paths.dedup();
    let graph_summary = if graph_paths.is_empty() && graph_removed_paths.is_empty() {
        json!({"updated": false})
    } else {
        let graph = projected_value(native_graph_command(
            workspace_root.clone(),
            NativeGraphOperation::Refresh,
            None,
            None,
            NativeGraphFileChanges {
                file_paths: graph_paths,
                removed_file_paths: graph_removed_paths,
            },
            None,
            None,
        ))?;
        if graph.get("ok") != Some(&Value::Bool(true)) {
            return print_projected_value(graph);
        }
        let graph_result = projected_result(&graph)?;
        json!({
            "updated": true,
            "generation": required_field(graph_result, "generation")?,
            "symbolCount": required_field(graph_result, "symbolCount")?,
            "edgeOccurrenceCount": required_field(graph_result, "edgeOccurrenceCount")?,
            "coverage": required_field(graph_result, "coverage")?,
        })
    };
    let next = externalizable_failures
        .iter()
        .map(|failure| {
            format!(
                "kast refresh external {}",
                failure["failureId"]
                    .as_str()
                    .expect("validated relationship failure id")
            )
        })
        .collect::<Vec<_>>();

    print_direct(&json!({
        "fileCount": file_paths.len(),
        "files": refreshed_paths,
        "removedFiles": removed_paths,
        "diagnostics": diagnostics,
        "graph": graph_summary,
        "externalizableFailures": externalizable_failures,
        "next": next,
    }))
}
