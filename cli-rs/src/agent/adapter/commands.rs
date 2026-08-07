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

pub(crate) fn run_files(
    pattern: Option<String>,
    page: Option<WorkspaceFilesPublicPageToken>,
) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let mut args = workspace_files_args(workspace_root);
    args.page_token = page;
    args.glob = pattern
        .map(|value| {
            value
                .parse::<WorkspaceRelativeGlob>()
                .map_err(|message| CliError::new("CLI_USAGE", message))
        })
        .transpose()?;
    print_projected(AgentCommand::WorkspaceFiles(args))
}

pub(crate) fn run_symbol(args: KastSymbolArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    match args.command {
        KastSymbolCommand::Find { query } => print_projected(symbol_lookup(
            workspace_root,
            query,
            AgentSymbolMode::Discovery,
        )),
        KastSymbolCommand::Show { symbol } => print_projected(symbol_lookup(
            workspace_root,
            symbol,
            AgentSymbolMode::Exact,
        )),
        KastSymbolCommand::Refs { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::References(AgentReferencesArgs {
                    runtime,
                    selector,
                    include_declaration: false,
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Callers { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Callers(AgentCallsArgs {
                    runtime,
                    selector,
                    depth: Default::default(),
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Callees { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Callees(AgentCallsArgs {
                    runtime,
                    selector,
                    depth: Default::default(),
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Implementations { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Implementations(AgentImplementationsArgs {
                    runtime,
                    selector,
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Supertypes { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Hierarchy(AgentHierarchyArgs {
                    runtime,
                    selector,
                    direction: AgentHierarchyDirection::Supertypes,
                    depth: maximum_relation_depth(),
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Subtypes { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Hierarchy(AgentHierarchyArgs {
                    runtime,
                    selector,
                    direction: AgentHierarchyDirection::Subtypes,
                    depth: maximum_relation_depth(),
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
    }
}

pub(crate) fn run_graph(args: KastGraphArgs) -> Result<i32> {
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
        ),
        KastGraphCommand::Nodes { page } => print_native_graph(
            workspace_root,
            NativeGraphOperation::Nodes,
            None,
            None,
            page,
        ),
        KastGraphCommand::Neighbors { symbol } => print_native_graph(
            workspace_root,
            NativeGraphOperation::Neighbors,
            None,
            Some(symbol),
            None,
        ),
        KastGraphCommand::Topology(projection) => print_native_graph(
            workspace_root,
            NativeGraphOperation::Topology,
            Some(projection.scope.into()),
            None,
            None,
        ),
        KastGraphCommand::Communities(projection) => print_native_graph(
            workspace_root,
            NativeGraphOperation::Communities,
            Some(projection.scope.into()),
            None,
            None,
        ),
        KastGraphCommand::Derive(args) => print_derived_topology(workspace_root, args),
        KastGraphCommand::Impact { symbol, page } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Impact(AgentImpactArgs {
                    runtime,
                    selector,
                    depth: Default::default(),
                    limit: maximum_relation_limit(),
                    page_token: page,
                    view: AgentImpactViewArgs::default(),
                })
            })
        }
    }
}
