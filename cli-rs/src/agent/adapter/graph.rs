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
        let expected_after_id = page
            .as_ref()
            .map_or(0, agent::public_protocol::GraphNodesPageToken::after_id);
        fields
            .remove("afterId")
            .and_then(|value| value.as_u64())
            .filter(|after_id| *after_id == expected_after_id)
            .ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned the wrong prior node identity.",
                )
            })?;
        let previously_returned = fields
            .remove("previouslyReturned")
            .and_then(|value| value.as_u64())
            .ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned no prior cardinality evidence.",
                )
            })?;
        let nodes = fields
            .get_mut("nodes")
            .and_then(Value::as_array_mut)
            .ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned no node collection.",
                )
            })?;
        for node in nodes.iter_mut() {
            replace_public_path(&workspace_root, node, "path", "path")?;
            let node_fields = node.as_object_mut().ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned a non-object node.",
                )
            })?;
            let node_id = node_fields.get("id").and_then(Value::as_u64).ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned a node without a numeric identity.",
                )
            })?;
            let stable_key = node_fields
                .get("stableKey")
                .and_then(Value::as_str)
                .ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "The native graph node page returned a node without a stable key.",
                    )
                })?;
            let selector = agent::public_protocol::issue_graph_node_selector(
                &workspace_root,
                generation,
                node_id,
                stable_key,
            )
            .map_err(|failure| CliError::new(failure.code(), failure.message()))?;
            node_fields.insert(
                "nodeSelector".to_string(),
                Value::String(selector.as_str().to_string()),
            );
        }
        let returned = u64::try_from(nodes.len()).map_err(|_| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The graph node page exceeded public cardinality.",
            )
        })?;
        let cumulative_returned = previously_returned.checked_add(returned).ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The graph node continuation exceeded public cardinality.",
            )
        })?;
        let next_after_id = fields.remove("nextAfterId").ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The native graph node page returned no continuation evidence.",
            )
        })?;
        let truncated = !next_after_id.is_null();
        let mut continuation = None;
        if truncated {
            let next_after_id = next_after_id.as_u64().ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned an invalid continuation.",
                )
            })?;
            let next_page = agent::public_protocol::GraphNodesPageToken::issue(
                workspace_fingerprint,
                admission.generation(),
                next_after_id,
            )
            .ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned a zero continuation.",
                )
            })?;
            continuation = Some(next_page.canonical());
        }
        let cardinality = if truncated {
            let known_minimum = cumulative_returned.checked_add(1).ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The graph node page exceeded public cardinality.",
                )
            })?;
            json!({"type": "known-minimum", "count": known_minimum})
        } else {
            json!({"type": "exact", "count": cumulative_returned})
        };
        let mut page = json!({
            "cardinality": cardinality,
            "returned": returned,
        });
        if let Some(continuation) = continuation {
            page["continuation"] = Value::String(continuation);
        }
        fields.insert("page".to_string(), page);
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
    let backend_type = fields.remove("type").and_then(|value| value.as_str().map(str::to_string));
    if backend_type.as_deref() != Some(native_graph_result_type(operation)) {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The native graph operation returned the wrong result type.",
        ));
    }
    if fields.remove("schemaVersion").and_then(|value| value.as_u64())
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
    print_protocol(
        agent::public_protocol::ProtocolEnvelope::projected(
            public_operation,
            status,
            fields.clone(),
        ),
        output_format,
    )
}

fn public_graph_operation(
    operation: NativeGraphOperation,
) -> agent::public_protocol::OperationId {
    match operation {
        NativeGraphOperation::Summary => agent::public_protocol::OperationId::GraphSummary,
        NativeGraphOperation::Nodes => agent::public_protocol::OperationId::GraphNodes,
        NativeGraphOperation::Neighbors => agent::public_protocol::OperationId::GraphNeighbors,
        NativeGraphOperation::Topology => agent::public_protocol::OperationId::GraphTopology,
        NativeGraphOperation::Communities => {
            agent::public_protocol::OperationId::GraphCommunities
        }
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
        limit: (operation == NativeGraphOperation::Nodes).then_some(500),
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
