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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EmptyCheckResult {
    changed_file_count: usize,
    diagnostic_count: usize,
    message: &'static str,
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

pub(crate) fn run_check(args: KastPathsArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let file_paths = if args.paths.is_empty() {
        match changed_kotlin_files(&workspace_root)? {
            Ok(file_paths) => file_paths,
            Err(envelope) => return print_projected_value(envelope),
        }
    } else {
        args.paths
            .into_iter()
            .map(|path| path.display().to_string())
            .collect()
    };
    if file_paths.is_empty() {
        return print_direct(&EmptyCheckResult {
            changed_file_count: 0,
            diagnostic_count: 0,
            message: "No changed Kotlin files were found.",
        });
    }
    let current = projected_value(check_diagnostics_command(
        &workspace_root,
        &file_paths,
        CheckDiagnosticsRead::CurrentPublication,
    ))?;
    match CurrentCheckAttempt::derive(current) {
        CurrentCheckAttempt::Covered(envelope) => print_projected_value(envelope),
        CurrentCheckAttempt::RefreshRequired(_) => print_projected(check_diagnostics_command(
            &workspace_root,
            &file_paths,
            CheckDiagnosticsRead::ReconciledPublication,
        )),
        CurrentCheckAttempt::Rejected(envelope) => print_projected_value(envelope),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CheckDiagnosticsRead {
    CurrentPublication,
    ReconciledPublication,
}

/// Boundary transition:
/// `(Path, [String], CheckDiagnosticsRead) -> AgentCommand::Diagnostics`.
///
/// Converts the typed public-check read policy to the legacy `skip_refresh`
/// CLI flag only at command construction. The decision itself never travels
/// through a Boolean inside the check workflow.
fn check_diagnostics_command(
    workspace_root: &Path,
    file_paths: &[String],
    read: CheckDiagnosticsRead,
) -> AgentCommand {
    let skip_refresh = match read {
        CheckDiagnosticsRead::CurrentPublication => true,
        CheckDiagnosticsRead::ReconciledPublication => false,
    };
    AgentCommand::Diagnostics(AgentDiagnosticsArgs {
        runtime: agent_runtime(workspace_root.to_path_buf()),
        file_paths: file_paths.to_vec(),
        skip_refresh,
        limit: 500,
        page_token: None,
        view: AgentDiagnosticsViewArgs::default(),
    })
}

#[derive(Debug, PartialEq)]
enum CurrentCheckAttempt {
    Covered(Value),
    RefreshRequired(WorkspaceStaleness),
    Rejected(Value),
}

impl CurrentCheckAttempt {
    /// Proof transition: `JSON AgentEnvelope -> CurrentCheckAttempt`.
    ///
    /// A successful projected diagnostic result already carries exact hashes
    /// for every analyzed file and therefore covers the current publication.
    /// Only closed workspace-movement evidence authorizes a refresh retry;
    /// malformed and unrelated failures remain rejected with their original
    /// envelope intact.
    fn derive(envelope: Value) -> Self {
        if envelope.get("ok").and_then(Value::as_bool) == Some(true) {
            return Self::Covered(envelope);
        }
        match WorkspaceStalenessEvidence::derive(&envelope) {
            WorkspaceStalenessEvidence::Proven(staleness) => {
                Self::RefreshRequired(staleness)
            }
            WorkspaceStalenessEvidence::Absent => Self::Rejected(envelope),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WorkspaceStaleness {
    SemanticAdmissionMoved,
    PublishedGenerationMoved,
    RuntimeIndexing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WorkspaceStalenessEvidence {
    Proven(WorkspaceStaleness),
    Absent,
}

impl WorkspaceStalenessEvidence {
    /// Proof transition: `JSON AgentEnvelope -> WorkspaceStalenessEvidence`.
    ///
    /// Refines only explicit runtime, publication, or semantic-admission
    /// movement into retry authority. A generic conflict without typed
    /// workspace-state details is deliberately not refreshable.
    fn derive(envelope: &Value) -> Self {
        let error = envelope
            .get("error")
            .or_else(|| envelope.pointer("/result/steps/0/error"));
        let Some(error) = error else {
            return Self::Absent;
        };
        match error.get("code").and_then(Value::as_str) {
            Some("RUNTIME_NOT_READY") => Self::Proven(WorkspaceStaleness::RuntimeIndexing),
            Some(
                "PUBLISHED_WORKSPACE_MOVED"
                | "PUBLISHED_WORKSPACE_MISMATCH"
                | "PUBLISHED_WORKSPACE_UNAVAILABLE",
            ) => Self::Proven(WorkspaceStaleness::PublishedGenerationMoved),
            Some("CONFLICT")
                if error
                    .pointer("/details/rpcError/data/details/workspaceState")
                    .is_some() =>
            {
                Self::Proven(WorkspaceStaleness::SemanticAdmissionMoved)
            }
            _ => Self::Absent,
        }
    }
}
