#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum PublicDemoAvailability {
    Full,
    IndexOnly,
    BackendOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum DemoCandidateKind {
    ImpactHub,
    CallChainHub,
    SemanticAmbiguity,
    SelectedSymbol,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum DemoChapter {
    Identity,
    SemanticDifference,
    Relationships,
    Impact,
    Safety,
    Recap,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoCandidate {
    kind: DemoCandidateKind,
    fq_name: String,
    symbol_kind: Option<String>,
    declaration_offset: Option<i64>,
    title: String,
    evidence_count: i64,
    file: Option<String>,
    module: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoChapterAvailability {
    chapter: DemoChapter,
    available: bool,
    basis: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoBackendSummary {
    name: String,
    version: String,
    reference_index_ready: bool,
}

#[derive(Debug, Clone)]
struct DemoBackendConnection {
    summary: DemoBackendSummary,
    socket_path: PathBuf,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PublicDemoSnapshot {
    #[serde(rename = "type")]
    response_type: &'static str,
    ok: bool,
    availability: PublicDemoAvailability,
    workspace_root: String,
    mutates: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    backend: Option<DemoBackendSummary>,
    candidates: Vec<DemoCandidate>,
    #[serde(skip_serializing_if = "Option::is_none")]
    selected_story: Option<DemoSelectedStory>,
    chapters: Vec<DemoChapterAvailability>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    warnings: Vec<String>,
    help: Vec<String>,
    schema_version: u32,
}

pub fn run_public(args: PublicDemoArgs, output_format: OutputFormat) -> Result<i32> {
    let request = DemoRequest::from_public_args(args)?;
    if !request.database.is_file() {
        return run_public_without_index(request, output_format);
    }
    let db = DemoDatabase::open(request)?;
    let interactive = should_run_public_demo_tui(
        output_format,
        io::stdin().is_terminal(),
        io::stdout().is_terminal(),
    );
    let (snapshot, connection) = public_demo_snapshot(&db, !interactive)?;
    if interactive {
        return run_public_demo_tui(Some(db), snapshot, connection);
    }
    output::print_structured(&snapshot, output_format)?;
    Ok(0)
}

fn run_public_without_index(request: DemoRequest, output_format: OutputFormat) -> Result<i32> {
    let (connection, mut warnings) = detect_demo_backend(&request);
    let Some(connection) = connection else {
        return Err(public_missing_index_error(&request));
    };
    let symbol = request.symbol.as_deref().ok_or_else(|| {
        CliError::new(
            "DEMO_SYMBOL_REQUIRED",
            "A ready compiler backend is available, but source-index ranking is not. Choose a Kotlin symbol with `kast demo --symbol <name> --workspace-root <repo>`.",
        )
    })?;
    let candidate = DemoCandidate {
        kind: DemoCandidateKind::SelectedSymbol,
        fq_name: symbol.to_string(),
        symbol_kind: None,
        declaration_offset: None,
        title: format!("Inspect compiler evidence for {symbol}"),
        evidence_count: 0,
        file: None,
        module: None,
    };
    let selected_story = load_selected_demo_story(&connection, &candidate, || false)?;
    warnings.push(
        "Source-index ranking and impact evidence are unavailable; this story uses the ready compiler backend."
            .to_string(),
    );
    let snapshot = PublicDemoSnapshot {
        response_type: "KAST_DEMO",
        ok: true,
        availability: PublicDemoAvailability::BackendOnly,
        workspace_root: request.workspace_root.display().to_string(),
        mutates: false,
        backend: Some(connection.summary.clone()),
        candidates: vec![candidate],
        selected_story: Some(selected_story),
        chapters: backend_only_chapters(),
        warnings,
        help: vec![
            format!("kast agent symbol --query {symbol} --workspace-root <repo>"),
            "Build the source index to unlock ranked impact and semantic-difference stories."
                .to_string(),
        ],
        schema_version: SCHEMA_VERSION,
    };
    if should_run_public_demo_tui(
        output_format,
        io::stdin().is_terminal(),
        io::stdout().is_terminal(),
    ) {
        return run_public_demo_tui(None, snapshot, Some(connection));
    }
    output::print_structured(&snapshot, output_format)?;
    Ok(0)
}

fn should_run_public_demo_tui(
    output_format: OutputFormat,
    stdin_terminal: bool,
    stdout_terminal: bool,
) -> bool {
    output_format == OutputFormat::Human && stdin_terminal && stdout_terminal
}

fn public_missing_index_error(request: &DemoRequest) -> CliError {
    let remedy = "Start the installed headless runtime, wait for indexing, then rerun `kast demo`.";
    CliError::new(
        "DEMO_SOURCE_INDEX_MISSING",
        format!(
            "No source-index database exists at {}. {remedy}",
            request.database.display()
        ),
    )
}

impl DemoRequest {
    fn from_public_args(args: PublicDemoArgs) -> Result<Self> {
        let workspace_root = config::resolve_workspace_root(args.runtime.workspace_root)?;
        let database = config::workspace_database_path(&workspace_root)?;
        Ok(Self {
            workspace_root,
            database,
            symbol: args.symbol,
            limit: 30,
            backend_name: args.runtime.backend_name,
        })
    }
}

fn public_demo_snapshot(
    db: &DemoDatabase,
    load_compiler_evidence: bool,
) -> Result<(PublicDemoSnapshot, Option<DemoBackendConnection>)> {
    let candidates = ranked_demo_candidates(db)?;
    let (connection, mut warnings) = detect_demo_backend(&db.request);
    let availability = if connection.is_some() {
        PublicDemoAvailability::Full
    } else {
        PublicDemoAvailability::IndexOnly
    };
    let help = candidates
        .first()
        .map(|candidate| {
            vec![
                demo_relationship_command(candidate, "impact"),
                demo_relationship_command(candidate, "references"),
            ]
        })
        .unwrap_or_else(|| {
            vec!["kast demo --symbol <name> --workspace-root <repo>".to_string()]
        });
    let selected_story = load_compiler_evidence.then(|| {
        candidates.first().map(|candidate| {
            selected_demo_story(candidate, connection.as_ref(), &mut warnings)
        })
    }).flatten();
    let snapshot = PublicDemoSnapshot {
        response_type: "KAST_DEMO",
        ok: true,
        availability,
        workspace_root: db.request.workspace_root.display().to_string(),
        mutates: false,
        backend: connection
            .as_ref()
            .map(|connection| connection.summary.clone()),
        selected_story,
        candidates,
        chapters: match availability {
            PublicDemoAvailability::Full => full_chapters(),
            PublicDemoAvailability::IndexOnly => index_only_chapters(),
            PublicDemoAvailability::BackendOnly => {
                unreachable!("indexed snapshots cannot be backend-only")
            }
        },
        warnings,
        help,
        schema_version: SCHEMA_VERSION,
    };
    Ok((snapshot, connection))
}

fn detect_demo_backend(request: &DemoRequest) -> (Option<DemoBackendConnection>, Vec<String>) {
    let status = match runtime::workspace_status(demo_runtime_args(request)) {
        Ok(status) => status,
        Err(error) => return (None, vec![error.message]),
    };
    let Some(selected) = status
        .selected
        .filter(|candidate| candidate.ready && candidate.reachable)
    else {
        return (None, Vec::new());
    };
    let reference_index_ready = selected
        .runtime_status
        .as_ref()
        .is_some_and(|status| status.reference_index_ready);
    (
        Some(DemoBackendConnection {
            summary: DemoBackendSummary {
                name: selected.descriptor.backend_name,
                version: selected.descriptor.backend_version,
                reference_index_ready,
            },
            socket_path: PathBuf::from(selected.descriptor.socket_path),
        }),
        Vec::new(),
    )
}

include!("candidates.rs");
