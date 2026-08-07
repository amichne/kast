#[derive(Debug, Serialize)]
struct ProjectedError {
    error: String,
    message: String,
    next: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpResult {
    root: String,
    ready: bool,
    runtime: &'static str,
    backend: String,
    reference_index_ready: bool,
    source_module_count: usize,
    next: Vec<&'static str>,
}

pub(crate) fn run_up() -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let mut args = crate::default_runtime_args();
    args.workspace_root = Some(workspace_root.clone());
    args.accept_indexing = Some(false);
    let deadline = Instant::now() + Duration::from_millis(args.wait_timeout_ms);
    let ensured = runtime::workspace_ensure(args.clone())?;
    if let Some(result) = ready_result(&workspace_root, ensured.selected.runtime_status.as_ref()) {
        return print_direct(&result);
    }

    let mut last_status = ensured.selected.runtime_status;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        std::thread::sleep(remaining.min(Duration::from_millis(250)));
        let status = runtime::workspace_status(args.clone())?;
        last_status = status
            .selected
            .and_then(|candidate| candidate.runtime_status);
        if let Some(result) = ready_result(&workspace_root, last_status.as_ref()) {
            return print_direct(&result);
        }
    }

    let state = last_status
        .as_ref()
        .map(|status| runtime_state_name(&status.state))
        .unwrap_or("UNREACHABLE");
    let reference_index_ready = last_status
        .as_ref()
        .is_some_and(|status| status.reference_index_ready);
    let source_module_count = last_status
        .as_ref()
        .map_or(0, |status| status.source_module_names.len());
    Err(CliError::new(
        "SEMANTIC_EVIDENCE_NOT_READY",
        format!(
            "The exact workspace reached {state}, but semantic evidence did not become ready within {} ms (referenceIndexReady={reference_index_ready}, sourceModuleCount={source_module_count}). Let the indexer finish, then run `kast up` again.",
            args.wait_timeout_ms
        ),
    ))
}

pub(crate) fn run_workspace(args: KastWorkspaceArgs) -> Result<i32> {
    match args.command {
        KastWorkspaceCommand::Ensure => run_up(),
        KastWorkspaceCommand::Refresh { files } => run_refresh(files),
        KastWorkspaceCommand::Externalize { failure_ids } => {
            let workspace_root = config::resolve_workspace_root(None)?;
            run_external_refresh(workspace_root, failure_ids)
        }
    }
}

pub(crate) fn run_file(args: KastFileArgs) -> Result<i32> {
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
    print_projected(AgentCommand::WorkspaceFiles(args))
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
        KastGraphCommand::Nodes { continuation } => print_native_graph(
            workspace_root,
            NativeGraphOperation::Nodes,
            None,
            None,
            continuation
                .map(|value| value.parse::<KastGraphNodesPageToken>())
                .transpose()
                .map_err(|message| CliError::new("CLI_USAGE", message))?,
            output_format,
        ),
        KastGraphCommand::Neighbors { node_selector } => {
            let node_selector = match agent::public_protocol::UntrustedGraphNodeSelector::parse(
                node_selector,
            ) {
                Ok(selector) => selector,
                Err(failure) => {
                    return print_actionable_failure(
                        failure.code(),
                        failure.message(),
                        "kast graph nodes",
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
