#[derive(Debug, Clone)]
pub(crate) struct NativeGraphNode {
    pub(crate) database_id: Option<u64>,
    pub(crate) key: String,
}

#[derive(Debug, Clone)]
pub(crate) struct NativeGraphEdge {
    pub(crate) source: usize,
    pub(crate) target: usize,
    pub(crate) kind: String,
    pub(crate) context: String,
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
    if args.operation == NativeGraphOperation::Refresh {
        return execute_agent_native_graph_refresh(args);
    }
    match native_graph_result(&args) {
        Ok(result) => result_envelope("agent/graph".to_string(), result),
        Err(error) => error_envelope("agent/graph".to_string(), None, error),
    }
}

fn execute_agent_native_graph_refresh(args: AgentNativeGraphArgs) -> AgentEnvelope {
    if args.file_paths.is_empty() && args.removed_file_paths.is_empty() {
        return error_envelope(
            "agent/graph".to_string(),
            None,
            agent_error(
                "AGENT_USAGE",
                "--operation refresh requires --file-path or --removed-file-path.",
            ),
        );
    }
    if args.database.is_some() {
        return error_envelope(
            "agent/graph".to_string(),
            None,
            agent_error(
                "AGENT_USAGE",
                "--database cannot be used with --operation refresh.",
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
    let mut removed_file_paths = match normalizer.normalize_all(&args.removed_file_paths) {
        Ok(file_paths) => file_paths,
        Err(error) => return error_envelope("agent/graph".to_string(), None, error),
    };
    file_paths.sort();
    file_paths.dedup();
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
    let request = json_rpc_request(
        "raw/semantic-graph",
        json!({
            "filePaths": file_paths,
            "removedFilePaths": removed_file_paths,
        }),
    );
    let response = execute_request(AgentRequest {
        method: "raw/semantic-graph".to_string(),
        request: request.clone(),
        runtime: args.runtime,
        full_response: true,
        operation: AgentOperation::ReadOnly,
    });
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
