#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpResult {
    root: String,
    ready: bool,
    runtime: &'static str,
    backend: String,
    reference_index_ready: bool,
    source_revision: u64,
    evidence_freshness: &'static str,
    source_module_count: usize,
    next: Vec<&'static str>,
}

pub(crate) fn run_up(output_format: OutputFormat) -> Result<i32> {
    let ready = runtime::demand_reference_ready_runtime(None)?;
    let result = UpResult {
        root: ready.workspace_root().display().to_string(),
        ready: true,
        runtime: "READY",
        backend: ready.backend_name().to_string(),
        reference_index_ready: true,
        source_revision: ready.source_revision(),
        evidence_freshness: match ready.freshness() {
            runtime::lifecycle_typestate::PublishedCapabilityFreshness::Current => "CURRENT",
            runtime::lifecycle_typestate::PublishedCapabilityFreshness::Previous => "PREVIOUS",
        },
        source_module_count: ready.source_module_count(),
        next: vec![
            "kast workspace refresh",
            "kast file list",
            "kast symbol search --query <query>",
        ],
    };
    print_public_value(
        agent::public_protocol::OperationId::WorkspaceUp,
        agent::public_protocol::OperationStatus::Complete,
        &result,
        output_format,
    )
}

pub(crate) fn run_workspace(args: KastWorkspaceArgs, output_format: OutputFormat) -> Result<i32> {
    match args.command {
        KastWorkspaceCommand::Refresh { files } => run_refresh(files, output_format),
        KastWorkspaceCommand::Externalize { failure_ids } => {
            let failure_ids = match failure_ids
                .into_iter()
                .map(agent::public_protocol::ExternalFailureId::parse)
                .collect::<std::result::Result<Vec<_>, _>>()
            {
                Ok(failure_ids) => failure_ids,
                Err(message) => {
                    return print_actionable_failure(
                        agent::public_protocol::OperationId::WorkspaceExternalize,
                        "EXTERNAL_FAILURE_ID_MALFORMED",
                        message,
                        "Use a failure ID returned by `kast workspace refresh`.",
                        output_format,
                    );
                }
            };
            let workspace_root = config::resolve_workspace_root(None)?;
            run_external_refresh(workspace_root, failure_ids, output_format)
        }
    }
}

pub(crate) fn run_file(args: KastFileArgs, output_format: OutputFormat) -> Result<i32> {
    let KastFileCommand::List {
        pattern,
        continuation,
    } = args.command;
    let workspace_root = config::resolve_workspace_root(None)?;
    let mut args = workspace_files_args(workspace_root);
    args.page_token = continuation;
    args.glob = pattern
        .map(|value| {
            value
                .parse::<WorkspaceRelativeGlob>()
                .map_err(|message| CliError::new("CLI_USAGE", message))
        })
        .transpose()?;
    print_file_list(
        projected_value(AgentCommand::WorkspaceFiles(args))?,
        output_format,
    )
}

pub(crate) fn run_symbol(args: KastSymbolArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    match args.command {
        KastSymbolCommand::Search { query } => print_protocol(
            agent::public_protocol::symbol_search(workspace_root, query),
            output_format,
        ),
        KastSymbolCommand::Resolve { query } => print_protocol(
            agent::public_protocol::symbol_resolve(workspace_root, query),
            output_format,
        ),
        KastSymbolCommand::Show { selector } => print_protocol(
            agent::public_protocol::symbol_show(workspace_root, selector),
            output_format,
        ),
    }
}

pub(crate) fn run_relation(args: KastRelationArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    match args.command {
        KastRelationCommand::References(args) => print_protocol(
            agent::public_protocol::relation_references(
                workspace_root,
                args.selector,
                args.continuation,
            ),
            output_format,
        ),
        KastRelationCommand::Calls(args) => match args.command {
            KastRelationCallsCommand::Incoming(args) => print_protocol(
                agent::public_protocol::relation_calls_incoming(
                    workspace_root,
                    args.selector,
                    args.continuation,
                ),
                output_format,
            ),
            KastRelationCallsCommand::Outgoing(args) => print_protocol(
                agent::public_protocol::relation_calls_outgoing(
                    workspace_root,
                    args.selector,
                    args.continuation,
                ),
                output_format,
            ),
        },
        KastRelationCommand::Implementations(args) => print_protocol(
            agent::public_protocol::relation_implementations(
                workspace_root,
                args.selector,
                args.continuation,
            ),
            output_format,
        ),
        KastRelationCommand::Hierarchy(args) => match args.command {
            KastRelationHierarchyCommand::Supertypes(args) => print_protocol(
                agent::public_protocol::relation_hierarchy_supertypes(
                    workspace_root,
                    args.selector,
                    args.continuation,
                ),
                output_format,
            ),
            KastRelationHierarchyCommand::Subtypes(args) => print_protocol(
                agent::public_protocol::relation_hierarchy_subtypes(
                    workspace_root,
                    args.selector,
                    args.continuation,
                ),
                output_format,
            ),
        },
    }
}

pub(crate) fn run_graph(args: KastGraphArgs, output_format: OutputFormat) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    match args
        .command
        .unwrap_or(KastGraphCommand::Summary(KastGraphProjectionArgs {
            scope: KastGraphScope::Symbol,
        })) {
        KastGraphCommand::Summary(projection) => print_native_graph(
            workspace_root,
            NativeGraphOperation::Summary,
            Some(projection.scope.into()),
            None,
            None,
            output_format,
        ),
        KastGraphCommand::Nodes { continuation } => {
            let page = match continuation
                .map(agent::public_protocol::GraphNodesPageToken::parse)
                .transpose()
            {
                Ok(page) => page,
                Err(failure) => {
                    return print_actionable_failure(
                        agent::public_protocol::OperationId::GraphNodes,
                        failure.code(),
                        failure.message(),
                        "kast graph nodes",
                        output_format,
                    );
                }
            };
            print_native_graph(
                workspace_root,
                NativeGraphOperation::Nodes,
                None,
                None,
                page,
                output_format,
            )
        }
        KastGraphCommand::Neighbors { node_selector } => {
            let node_selector = match agent::public_protocol::UntrustedGraphNodeSelector::parse(
                node_selector,
            ) {
                Ok(selector) => selector,
                Err(failure) => {
                    return print_actionable_failure(
                        agent::public_protocol::OperationId::GraphNeighbors,
                        failure.code(),
                        failure.message(),
                        "kast graph nodes",
                        output_format,
                    );
                }
            };
            print_native_graph(
                workspace_root,
                NativeGraphOperation::Neighbors,
                None,
                Some(node_selector),
                None,
                output_format,
            )
        }
        KastGraphCommand::Topology(projection) => print_native_graph(
            workspace_root,
            NativeGraphOperation::Topology,
            Some(projection.scope.into()),
            None,
            None,
            output_format,
        ),
        KastGraphCommand::Communities(projection) => print_native_graph(
            workspace_root,
            NativeGraphOperation::Communities,
            Some(projection.scope.into()),
            None,
            None,
            output_format,
        ),
        KastGraphCommand::Derive(args) => {
            print_derived_topology(workspace_root, args, output_format)
        }
        KastGraphCommand::Impact(args) => print_protocol(
            agent::public_protocol::graph_impact(
                workspace_root,
                args.selector,
                args.continuation,
            ),
            output_format,
        ),
    }
}
