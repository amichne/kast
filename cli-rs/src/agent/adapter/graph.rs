fn print_derived_topology(
    workspace_root: PathBuf,
    args: crate::cli::KastDerivedTopologyArgs,
    output_format: OutputFormat,
) -> Result<i32> {
    if !args.experimental_derived_topology {
        return Err(CliError::new(
            "CLI_USAGE",
            "`kast graph derive` requires --experimental-derived-topology.",
        ));
    }
    let receipt =
        agent::write_reference_derived_topology(&workspace_root, &args.out, args.prior.as_deref())?;
    let fields = serde_json::to_value(receipt)?
        .as_object()
        .cloned()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The derived topology receipt was not an object.",
            )
        })?;
    print_protocol(
        agent::public_protocol::ProtocolEnvelope::projected(
            agent::public_protocol::OperationId::GraphDerive,
            agent::public_protocol::OperationStatus::Complete,
            fields,
        ),
        output_format,
    )
}

fn print_native_graph(
    workspace_root: PathBuf,
    operation: NativeGraphOperation,
    scope: Option<NativeGraphScope>,
    node_selector: Option<agent::public_protocol::UntrustedGraphNodeSelector>,
    page: Option<agent::public_protocol::GraphNodesPageToken>,
    output_format: OutputFormat,
) -> Result<i32> {
    let public_operation = public_graph_operation(operation);
    let workspace_fingerprint =
        agent::public_protocol::graph_workspace_fingerprint(&workspace_root);
    if page
        .as_ref()
        .is_some_and(|page| page.workspace_fingerprint() != workspace_fingerprint)
    {
        return print_actionable_failure(
            public_operation,
            "GRAPH_PAGE_TOKEN_MISMATCH",
            "The graph page belongs to a different workspace.",
            "kast graph nodes",
            output_format,
        );
    }
    let admission =
        match crate::repository_intelligence::semantic_graph_read_admission(&workspace_root) {
            Ok(admission) => admission,
            Err(error) => {
                return print_actionable_failure(
                    public_operation,
                    "GRAPH_EVIDENCE_UNAVAILABLE",
                    &error.message,
                    "kast workspace refresh",
                    output_format,
                );
            }
        };
    if admission.is_rejected() {
        return print_actionable_failure(
            public_operation,
            "GRAPH_EVIDENCE_INCOMPLETE",
            "Persisted semantic graph evidence is incomplete.",
            "kast workspace refresh",
            output_format,
        );
    }
    if page
        .as_ref()
        .is_some_and(|page| page.generation() != admission.generation())
    {
        return print_actionable_failure(
            public_operation,
            "GRAPH_PAGE_EXPIRED",
            "The graph changed after this page was issued.",
            "kast graph nodes",
            output_format,
        );
    }
    let selected_node = match node_selector
        .map(|selector| {
            agent::public_protocol::authenticate_graph_node_selector(
                &workspace_root,
                admission.generation(),
                selector,
            )
        })
        .transpose()
    {
        Ok(selector) => selector,
        Err(failure) => {
            return print_actionable_failure(
                public_operation,
                failure.code(),
                failure.message(),
                "kast graph nodes",
                output_format,
            );
        }
    };
    let after_id = page
        .as_ref()
        .map(agent::public_protocol::GraphNodesPageToken::after_id);
    let envelope = projected_value(native_graph_command(
        workspace_root.clone(),
        operation,
        scope,
        selected_node
            .as_ref()
            .map(|selector| selector.stable_key().to_string()),
        NativeGraphFileChanges::default(),
        Some(admission.generation()),
        after_id,
    ))?;
    let mut result = match backend_outcome(public_operation, envelope) {
        BackendOutcome::Complete(result) => result,
        BackendOutcome::Rejected(envelope) => return print_protocol(*envelope, output_format),
    };
    let fields = result.as_object_mut().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The native graph operation returned a non-object result.",
        )
    })?;
    fields.insert(
        "qualification".to_string(),
        json!(
            admission
                .qualification()
                .expect("non-rejected graph evidence has a qualification")
        ),
    );
    fields.insert(
        "coverage".to_string(),
        serde_json::to_value(admission.coverage())?,
    );
    if operation == NativeGraphOperation::Nodes {
        let generation = fields
            .get("generation")
            .and_then(Value::as_u64)
            .filter(|generation| *generation == admission.generation())
            .ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned the wrong generation.",
                )
            })?;
        if let Some(nodes) = fields.get_mut("nodes").and_then(Value::as_array_mut) {
            for node in nodes {
                replace_public_path(&workspace_root, node, "path", "path")?;
            }
        }
        *fields = agent::public_protocol::GraphNodesPageToken::project_page(
            &workspace_root,
            generation,
            page.as_ref(),
            std::mem::take(fields),
        )
        .map_err(|failure| CliError::new("KAST_INVALID_AGENT_RESULT", failure.message()))?
        .into_fields();
    }
    if let Some(selected_node) = selected_node
        && fields.get("key").and_then(Value::as_str) != Some(selected_node.stable_key())
    {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            format!(
                "Graph neighbor evidence did not match authenticated node {}.",
                selected_node.node_id()
            ),
        ));
    }
    let backend_type = fields
        .remove("type")
        .and_then(|value| value.as_str().map(str::to_string));
    if backend_type.as_deref() != Some(native_graph_result_type(operation)) {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The native graph operation returned the wrong result type.",
        ));
    }
    if fields
        .remove("schemaVersion")
        .and_then(|value| value.as_u64())
        != Some(u64::from(crate::SCHEMA_VERSION))
    {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The native graph operation returned the wrong schema version.",
        ));
    }
    let status = if fields.get("qualification").and_then(Value::as_str) == Some("QUALIFIED") {
        agent::public_protocol::OperationStatus::Qualified
    } else {
        agent::public_protocol::OperationStatus::Complete
    };
    BoundedGraphResponse::print(
        public_operation,
        status,
        operation,
        std::mem::take(fields),
        output_format,
    )
}

const MAX_PUBLIC_GRAPH_RESPONSE_BYTES: usize = 64 * 1_024;

struct BoundedGraphResponse(agent::public_protocol::ProtocolEnvelope);

impl BoundedGraphResponse {
    fn print(
        public_operation: agent::public_protocol::OperationId,
        status: agent::public_protocol::OperationStatus,
        operation: NativeGraphOperation,
        mut fields: serde_json::Map<String, Value>,
        output_format: OutputFormat,
    ) -> Result<i32> {
        summarize_graph_projection(operation, &mut fields)?;
        let envelope =
            agent::public_protocol::ProtocolEnvelope::projected(public_operation, status, fields);
        let bytes = output::render_structured_output(&envelope, output_format)?.len();
        if bytes > MAX_PUBLIC_GRAPH_RESPONSE_BYTES {
            return Err(CliError::new(
                "KAST_GRAPH_RESPONSE_BUDGET_EXCEEDED",
                format!(
                    "The graph response required {bytes} bytes; the public bound is {MAX_PUBLIC_GRAPH_RESPONSE_BYTES}."
                ),
            ));
        }
        let response = Self(envelope);
        print_protocol(response.0, output_format)
    }
}

fn summarize_graph_projection(
    operation: NativeGraphOperation,
    fields: &mut serde_json::Map<String, Value>,
) -> Result<()> {
    let summary = match operation {
        NativeGraphOperation::Summary | NativeGraphOperation::Nodes => return Ok(()),
        NativeGraphOperation::Neighbors => json!({
            "type": "bounded-summary",
            "outgoingCount": take_graph_array(fields, "outgoing")?.len(),
            "incomingCount": take_graph_array(fields, "incoming")?.len(),
        }),
        NativeGraphOperation::Topology => {
            let nodes = take_graph_array(fields, "nodes")?;
            let components = take_graph_array(fields, "components")?;
            let strongly_connected = take_graph_array(fields, "stronglyConnectedComponents")?;
            let topological = take_graph_array(fields, "condensationTopologicalOrder")?;
            if components.len() != nodes.len() || strongly_connected.len() != nodes.len() {
                return Err(invalid_graph_projection("topology cardinalities disagreed"));
            }
            let component_count = distinct_graph_ids(&components)?;
            let strongly_connected_count = distinct_graph_ids(&strongly_connected)?;
            if distinct_graph_ids(&topological)? != strongly_connected_count {
                return Err(invalid_graph_projection(
                    "topological components were incomplete",
                ));
            }
            json!({
                "type": "bounded-summary",
                "nodeCount": nodes.len(),
                "componentCount": component_count,
                "stronglyConnectedComponentCount": strongly_connected_count,
            })
        }
        NativeGraphOperation::Communities => {
            let nodes = take_graph_array(fields, "nodes")?;
            let mut communities = BTreeSet::new();
            for node in &nodes {
                let fields = node
                    .as_object()
                    .ok_or_else(|| invalid_graph_projection("community node was not an object"))?;
                if fields.get("key").and_then(Value::as_str).is_none() {
                    return Err(invalid_graph_projection("community node omitted its key"));
                }
                communities.insert(fields.get("community").and_then(Value::as_u64).ok_or_else(
                    || invalid_graph_projection("community node omitted its identity"),
                )?);
            }
            json!({
                "type": "bounded-summary",
                "nodeCount": nodes.len(),
                "communityCount": communities.len()
            })
        }
        NativeGraphOperation::Refresh => {
            return Err(invalid_graph_projection(
                "refresh reached the graph read projection",
            ));
        }
    };
    fields.insert("summary".to_string(), summary);
    Ok(())
}

fn take_graph_array(
    fields: &mut serde_json::Map<String, Value>,
    name: &'static str,
) -> Result<Vec<Value>> {
    fields
        .remove(name)
        .and_then(|value| value.as_array().cloned())
        .ok_or_else(|| invalid_graph_projection("graph summary omitted a required collection"))
}

fn distinct_graph_ids(values: &[Value]) -> Result<usize> {
    values
        .iter()
        .map(|value| {
            value.as_u64().ok_or_else(|| {
                invalid_graph_projection("graph summary contained a non-numeric identity")
            })
        })
        .collect::<Result<BTreeSet<_>>>()
        .map(|ids| ids.len())
}

fn invalid_graph_projection(message: &'static str) -> CliError {
    CliError::new("KAST_INVALID_AGENT_RESULT", message)
}

fn public_graph_operation(operation: NativeGraphOperation) -> agent::public_protocol::OperationId {
    match operation {
        NativeGraphOperation::Summary => agent::public_protocol::OperationId::GraphSummary,
        NativeGraphOperation::Nodes => agent::public_protocol::OperationId::GraphNodes,
        NativeGraphOperation::Neighbors => agent::public_protocol::OperationId::GraphNeighbors,
        NativeGraphOperation::Topology => agent::public_protocol::OperationId::GraphTopology,
        NativeGraphOperation::Communities => agent::public_protocol::OperationId::GraphCommunities,
        NativeGraphOperation::Refresh => agent::public_protocol::OperationId::WorkspaceRefresh,
    }
}

fn native_graph_result_type(operation: NativeGraphOperation) -> &'static str {
    match operation {
        NativeGraphOperation::Summary => "KAST_NATIVE_GRAPH_SUMMARY",
        NativeGraphOperation::Nodes => "KAST_NATIVE_GRAPH_NODES",
        NativeGraphOperation::Neighbors => "KAST_NATIVE_GRAPH_NEIGHBORS",
        NativeGraphOperation::Topology => "KAST_NATIVE_GRAPH_TOPOLOGY",
        NativeGraphOperation::Communities => "KAST_NATIVE_GRAPH_COMMUNITIES",
        NativeGraphOperation::Refresh => "KAST_NATIVE_GRAPH_REFRESH",
    }
}

fn native_graph_command(
    workspace_root: PathBuf,
    operation: NativeGraphOperation,
    scope: Option<NativeGraphScope>,
    symbol: Option<String>,
    file_changes: NativeGraphFileChanges,
    generation: Option<u64>,
    after_id: Option<u64>,
) -> AgentCommand {
    AgentCommand::Graph(AgentNativeGraphArgs {
        runtime: agent_runtime(workspace_root),
        database: None,
        scope,
        operation,
        file_paths: file_changes.file_paths,
        removed_file_paths: file_changes.removed_file_paths,
        modules: Vec::new(),
        source_sets: Vec::new(),
        exclusive: false,
        symbol,
        generation,
        after_id,
        limit: (operation == NativeGraphOperation::Nodes)
            .then_some(agent::public_protocol::GraphNodesPageToken::public_limit()),
        resolution: None,
    })
}

#[derive(Default)]
struct NativeGraphFileChanges {
    file_paths: Vec<String>,
    removed_file_paths: Vec<String>,
}

fn normalize_planned_paths(runtime: &AgentRuntimeArgs, paths: &[String]) -> Result<Vec<String>> {
    agent::normalize_public_file_paths(runtime, paths).map_err(|error| {
        CliError::new(
            "KAST_REFRESH_PLAN_INVALID",
            format!("{}: {}", error.code, error.message),
        )
    })
}
