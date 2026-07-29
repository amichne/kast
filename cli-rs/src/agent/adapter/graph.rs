pub(crate) fn print_projected(command: AgentCommand) -> Result<i32> {
    print_projected_value(projected_value(command)?)
}

fn print_native_graph(
    workspace_root: PathBuf,
    operation: NativeGraphOperation,
    symbol: Option<String>,
    page: Option<KastGraphNodesPageToken>,
) -> Result<i32> {
    let workspace_fingerprint = graph_nodes_workspace_fingerprint(&workspace_root);
    if page
        .as_ref()
        .is_some_and(|page| page.workspace_fingerprint() != workspace_fingerprint)
    {
        return print_actionable_failure(
            "GRAPH_PAGE_TOKEN_MISMATCH",
            "The graph page belongs to a different workspace.",
            "kast graph nodes",
        );
    }
    let admission =
        match crate::repository_intelligence::semantic_graph_read_admission(&workspace_root) {
            Ok(admission) => admission,
            Err(error) => {
                return print_actionable_failure(
                    "GRAPH_EVIDENCE_UNAVAILABLE",
                    &error.message,
                    "kast refresh",
                );
            }
        };
    if admission.is_rejected() {
        return print_actionable_failure(
            "GRAPH_EVIDENCE_INCOMPLETE",
            "Persisted semantic graph evidence is incomplete.",
            "kast refresh",
        );
    }
    if page
        .as_ref()
        .is_some_and(|page| page.generation() != admission.generation())
    {
        return print_actionable_failure(
            "GRAPH_PAGE_EXPIRED",
            "The graph changed after this page was issued.",
            "kast graph nodes",
        );
    }
    let after_id = page.as_ref().map(KastGraphNodesPageToken::after_id);
    let envelope = projected_value(native_graph_command(
        workspace_root,
        operation,
        symbol,
        Vec::new(),
        Vec::new(),
        Some(admission.generation()),
        after_id,
    ))?;
    if envelope.get("ok") != Some(&Value::Bool(true)) {
        return print_projected_value(envelope);
    }
    let mut result = projected_result(&envelope)?.clone();
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
        let next_after_id = fields.get("nextAfterId").ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The native graph node page returned no continuation evidence.",
            )
        })?;
        if !next_after_id.is_null() {
            let next_after_id = next_after_id.as_u64().ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "The native graph node page returned an invalid continuation.",
                )
            })?;
            let next_page = KastGraphNodesPageToken::issue(
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
            fields.insert("nextPageToken".to_string(), json!(next_page.canonical()));
        }
    }
    print_direct(&sanitize_agent_result(result, true))
}

fn native_graph_command(
    workspace_root: PathBuf,
    operation: NativeGraphOperation,
    symbol: Option<String>,
    file_paths: Vec<String>,
    removed_file_paths: Vec<String>,
    generation: Option<u64>,
    after_id: Option<u64>,
) -> AgentCommand {
    AgentCommand::Graph(AgentNativeGraphArgs {
        runtime: agent_runtime(workspace_root),
        database: None,
        scope: None,
        operation,
        file_paths,
        removed_file_paths,
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

fn run_symbol_relation(
    workspace_root: PathBuf,
    symbol: String,
    command: impl FnOnce(AgentRuntimeArgs, AgentReusableSymbolSelectorArgs) -> AgentCommand,
) -> Result<i32> {
    let selector = match resolve_selector(&workspace_root, symbol)? {
        Ok(selector) => selector,
        Err(envelope) => return print_projected_value(envelope),
    };
    print_projected(command(
        agent_runtime(workspace_root),
        selector_args(selector),
    ))
}

fn resolve_selector(
    workspace_root: &Path,
    symbol: String,
) -> Result<std::result::Result<AgentSelectorHandle, Value>> {
    if symbol.starts_with("ksh1.") {
        return symbol
            .parse()
            .map(Ok)
            .map_err(|message| CliError::new("CLI_USAGE", message));
    }
    let envelope = projected_value(symbol_lookup(
        workspace_root.to_path_buf(),
        symbol,
        AgentSymbolMode::Exact,
    ))?;
    let selector = envelope
        .get("result")
        .and_then(|result| result.get("selectorHandle"))
        .and_then(Value::as_str);
    match selector {
        Some(selector) => selector
            .parse()
            .map(Ok)
            .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message)),
        None => Ok(Err(envelope)),
    }
}

fn symbol_lookup(workspace_root: PathBuf, query: String, mode: AgentSymbolMode) -> AgentCommand {
    AgentCommand::Symbol(AgentSymbolArgs {
        runtime: agent_runtime(workspace_root),
        query,
        mode,
        kind: None,
        file_hint: None,
        containing_type: None,
        limit: 10,
        view: AgentSymbolViewArgs::default(),
    })
}

fn selector_args(selector_handle: AgentSelectorHandle) -> AgentReusableSymbolSelectorArgs {
    AgentReusableSymbolSelectorArgs {
        symbol: None,
        declaration_file: None,
        declaration_start_offset: None,
        kind: None,
        containing_type: None,
        selector_handle: Some(selector_handle),
    }
}

fn normalize_planned_paths(runtime: &AgentRuntimeArgs, paths: &[String]) -> Result<Vec<String>> {
    agent::normalize_public_file_paths(runtime, paths).map_err(|error| {
        CliError::new(
            "KAST_REFRESH_PLAN_INVALID",
            format!("{}: {}", error.code, error.message),
        )
    })
}

fn graph_nodes_workspace_fingerprint(workspace_root: &Path) -> String {
    crate::manifest::sha256_bytes(workspace_root.as_os_str().as_encoded_bytes())[..24].to_string()
}
