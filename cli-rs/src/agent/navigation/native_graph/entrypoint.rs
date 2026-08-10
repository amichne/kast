#[derive(Debug, Clone)]
pub(crate) struct NativeGraphNode {
    pub(crate) database_id: Option<u64>,
    pub(crate) key: String,
}

#[derive(Debug, Clone)]
pub(crate) struct NativeGraphEdge {
    pub(crate) source: usize,
    pub(crate) target: usize,
    pub(crate) occurrence_count: usize,
    pub(crate) weight: f64,
}

#[derive(Debug, Clone)]
pub(crate) struct NativeGraph {
    pub(crate) nodes: Vec<NativeGraphNode>,
    edges: Vec<NativeGraphEdge>,
    offsets: Vec<usize>,
    targets: Vec<usize>,
}

const NATIVE_GRAPH_ROOT_PACKAGE_KEY: &str = "<root>";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NativeGraphOverlayDescriptor {
    base_database: Option<PathBuf>,
}

fn execute_agent_native_graph(args: AgentNativeGraphArgs) -> AgentEnvelope {
    if let Err(error) = validate_native_graph_operation_args(&args) {
        return error_envelope("agent/graph".to_string(), None, error);
    }
    if args.operation == NativeGraphOperation::Refresh {
        return execute_agent_native_graph_refresh(args);
    }
    let semantic_read = if args.database.is_none() {
        match runtime::semantic_graph_workspace_read(args.runtime.workspace_root.clone()) {
            Ok(read) => Some(read),
            Err(error) => {
                return error_envelope(
                    "agent/graph".to_string(),
                    None,
                    AgentError::from_cli_error(error),
                );
            }
        }
    } else {
        None
    };
    match native_graph_result(
        &args,
        semantic_read
            .as_ref()
            .map(runtime::SemanticWorkspaceRead::published_graph),
    ) {
        Ok(result) => {
            let result = match &semantic_read {
                Some(read) => match read.revalidate() {
                    Ok(proof) => proof.finish(result),
                    Err(error) => {
                        return error_envelope(
                            "agent/graph".to_string(),
                            None,
                            AgentError::from_cli_error(error),
                        );
                    }
                },
                None => result,
            };
            result_envelope("agent/graph".to_string(), result)
        }
        Err(error) => error_envelope("agent/graph".to_string(), None, error),
    }
}

fn validate_native_graph_operation_args(
    args: &AgentNativeGraphArgs,
) -> std::result::Result<(), AgentError> {
    let irrelevant = match args.operation {
        NativeGraphOperation::Refresh => [
            args.database.as_ref().map(|_| "--database"),
            args.scope.map(|_| "--scope"),
            args.symbol.as_ref().map(|_| "--symbol"),
            args.generation.map(|_| "--generation"),
            args.after_id.map(|_| "--after-id"),
            args.limit.map(|_| "--limit"),
            args.resolution.map(|_| "--resolution"),
        ]
        .into_iter()
        .flatten()
        .next(),
        NativeGraphOperation::Summary => native_graph_refresh_flag(args)
            .or_else(|| args.symbol.as_ref().map(|_| "--symbol"))
            .or_else(|| args.after_id.map(|_| "--after-id"))
            .or_else(|| args.limit.map(|_| "--limit")),
        NativeGraphOperation::Nodes => native_graph_refresh_flag(args)
            .or_else(|| args.symbol.as_ref().map(|_| "--symbol"))
            .or_else(|| args.resolution.map(|_| "--resolution")),
        NativeGraphOperation::Neighbors => native_graph_refresh_flag(args)
            .or_else(|| args.after_id.map(|_| "--after-id"))
            .or_else(|| args.limit.map(|_| "--limit"))
            .or_else(|| args.resolution.map(|_| "--resolution")),
        NativeGraphOperation::Topology => native_graph_refresh_flag(args)
            .or_else(|| args.symbol.as_ref().map(|_| "--symbol"))
            .or_else(|| args.after_id.map(|_| "--after-id"))
            .or_else(|| args.limit.map(|_| "--limit"))
            .or_else(|| args.resolution.map(|_| "--resolution")),
        NativeGraphOperation::Communities => native_graph_refresh_flag(args)
            .or_else(|| args.symbol.as_ref().map(|_| "--symbol"))
            .or_else(|| args.after_id.map(|_| "--after-id"))
            .or_else(|| args.limit.map(|_| "--limit")),
    };
    match irrelevant {
        Some(flag) => Err(agent_error(
            "AGENT_USAGE",
            format!("{flag} cannot be used with the selected graph operation."),
        )),
        None => Ok(()),
    }
}

fn native_graph_refresh_flag(args: &AgentNativeGraphArgs) -> Option<&'static str> {
    (!args.file_paths.is_empty())
        .then_some("--file-path")
        .or_else(|| (!args.removed_file_paths.is_empty()).then_some("--removed-file-path"))
        .or_else(|| (!args.modules.is_empty()).then_some("--module"))
        .or_else(|| (!args.source_sets.is_empty()).then_some("--source-set"))
        .or_else(|| args.exclusive.then_some("--exclusive"))
}

fn execute_agent_native_graph_refresh(args: AgentNativeGraphArgs) -> AgentEnvelope {
    if args.file_paths.is_empty()
        && args.removed_file_paths.is_empty()
        && args.modules.is_empty()
        && args.source_sets.is_empty()
    {
        return error_envelope(
            "agent/graph".to_string(),
            None,
            agent_error(
                "AGENT_USAGE",
                "--operation refresh requires --file-path, --removed-file-path, --module, or --source-set.",
            ),
        );
    }
    let normalizer = match AgentFilePathNormalizer::from_runtime(&args.runtime) {
        Ok(normalizer) => normalizer,
        Err(error) => return error_envelope("agent/graph".to_string(), None, error),
    };
    let mut file_paths = match normalizer.normalize_all(&args.file_paths) {
        Ok(file_paths) => file_paths,
        Err(error) => return error_envelope("agent/graph".to_string(), None, error),
    };
    let scope_snapshot =
        if !args.modules.is_empty() || !args.source_sets.is_empty() || args.exclusive {
            match native_graph_refresh_scope_snapshot(&args) {
                Ok(snapshot) => Some(snapshot),
                Err(error) => return error_envelope("agent/graph".to_string(), None, error),
            }
        } else {
            None
    };
    if !args.modules.is_empty() || !args.source_sets.is_empty() {
        let Some(snapshot) = scope_snapshot.as_ref() else {
            return error_envelope(
                "agent/graph".to_string(),
                None,
                agent_error(
                    "NATIVE_GRAPH_REFRESH_FAILED",
                    "Scoped graph refresh planning returned no source-index snapshot.",
                ),
            );
        };
        let selected = match normalizer.normalize_all(&snapshot.selected) {
            Ok(selected) => selected,
            Err(error) => return error_envelope("agent/graph".to_string(), None, error),
        };
        file_paths.extend(selected);
        if !args.exclusive {
            let persisted = match normalizer.normalize_all(&snapshot.persisted) {
                Ok(persisted) => persisted,
                Err(error) => return error_envelope("agent/graph".to_string(), None, error),
            };
            file_paths.extend(persisted);
        }
    }
    let mut removed_file_paths = match normalizer.normalize_all(&args.removed_file_paths) {
        Ok(file_paths) => file_paths,
        Err(error) => return error_envelope("agent/graph".to_string(), None, error),
    };
    file_paths.sort();
    file_paths.dedup();
    if args.exclusive {
        let Some(snapshot) = scope_snapshot.as_ref() else {
            return error_envelope(
                "agent/graph".to_string(),
                None,
                agent_error(
                    "NATIVE_GRAPH_REFRESH_FAILED",
                    "Exclusive graph refresh planning returned no source-index snapshot.",
                ),
            );
        };
        let persisted = match normalizer.normalize_all(&snapshot.persisted) {
            Ok(persisted) => persisted,
            Err(error) => return error_envelope("agent/graph".to_string(), None, error),
        };
        removed_file_paths.extend(
            persisted
                .into_iter()
                .filter(|path| file_paths.binary_search(path).is_err()),
        );
    }
    removed_file_paths.sort();
    removed_file_paths.dedup();
    if file_paths
        .iter()
        .any(|path| removed_file_paths.binary_search(path).is_ok())
    {
        return error_envelope(
            "agent/graph".to_string(),
            None,
            agent_error(
                "AGENT_USAGE",
                "A Kotlin path cannot be both selected and removed.",
            ),
        );
    }
    let mut params = json!({
        "filePaths": file_paths,
        "removedFilePaths": removed_file_paths,
    });
    if let Some(snapshot) = &scope_snapshot {
        params["expectedGeneration"] = json!(snapshot.generation);
    }
    let request = json_rpc_request("raw/semantic-graph", params);
    let session = match runtime::raw_rpc_session(args.runtime.workspace_root.clone()) {
        Ok(session) => session,
        Err(error) => {
            return error_envelope(
                "agent/graph".to_string(),
                Some(request),
                AgentError::from_cli_error(error),
            );
        }
    };
    let response = execute_request_with_session(
        AgentRequest {
            method: "raw/semantic-graph".to_string(),
            request: request.clone(),
            runtime: args.runtime,
            full_response: true,
            operation: AgentOperation::ReadOnly,
        },
        Some(&session),
    );
    if !response.ok {
        return error_envelope(
            "agent/graph".to_string(),
            Some(request),
            response.error.unwrap_or_else(|| {
                agent_error(
                    "NATIVE_GRAPH_REFRESH_FAILED",
                    "Compiler-backed semantic graph refresh failed without a typed error.",
                )
            }),
        );
    }
    let Some(Value::Object(mut result)) = response.result else {
        return invalid_projection_envelope(
            "agent/graph".to_string(),
            "Compiler-backed semantic graph refresh returned no object result.",
        );
    };
    result.insert(
        "type".to_string(),
        json!("KAST_AGENT_GRAPH_REFRESH"),
    );
    result.insert("operation".to_string(), json!(NativeGraphOperation::Refresh));
    result.insert("schemaVersion".to_string(), json!(SCHEMA_VERSION));
    result_envelope("agent/graph".to_string(), Value::Object(result))
}
