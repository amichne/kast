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
            format!(
                "kast agent symbol --query {symbol} --references --workspace-root <repo>"
            ),
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
    #[cfg(target_os = "macos")]
    let remedy = "To continue, open this repository in IntelliJ IDEA or Android Studio with the Kast plugin enabled, wait for indexing, then rerun `kast demo`.";
    #[cfg(not(target_os = "macos"))]
    let remedy = "Run `kast setup --workspace-root <repo>`, start the headless backend, then rerun `kast demo`.";
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
                format!(
                    "kast agent impact --symbol {} --workspace-root <repo>",
                    candidate.fq_name
                ),
                format!(
                    "kast agent symbol --query {} --references --workspace-root <repo>",
                    candidate.fq_name
                ),
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

fn demo_runtime_args(request: &DemoRequest) -> RuntimeArgs {
    RuntimeArgs {
        workspace_root: Some(request.workspace_root.clone()),
        backend_name: request.backend_name,
        idea_home: None,
        wait_timeout_ms: 60_000,
        accept_indexing: Some(false),
        no_auto_start: Some(true),
        socket_path: None,
        module_name: None,
        source_roots: None,
        classpath: None,
        request_timeout_ms: None,
        max_results: None,
        max_concurrent_requests: None,
        profile: false,
        profile_modes: None,
        profile_duration: None,
        profile_otlp_endpoint: None,
    }
}

fn full_chapters() -> Vec<DemoChapterAvailability> {
    vec![
        chapter(DemoChapter::Identity, true, "compiler-resolved declaration"),
        chapter(
            DemoChapter::SemanticDifference,
            true,
            "compiler and source-index evidence",
        ),
        chapter(
            DemoChapter::Relationships,
            true,
            "compiler references and callers",
        ),
        chapter(DemoChapter::Impact, true, "source-index impact graph"),
        chapter(
            DemoChapter::Safety,
            true,
            "compiler diagnostics and plan-first rename",
        ),
        chapter(DemoChapter::Recap, true, "public command handoff"),
    ]
}

fn backend_only_chapters() -> Vec<DemoChapterAvailability> {
    vec![
        chapter(DemoChapter::Identity, true, "compiler-resolved declaration"),
        chapter(
            DemoChapter::SemanticDifference,
            false,
            "source index unavailable",
        ),
        chapter(
            DemoChapter::Relationships,
            true,
            "compiler references and callers",
        ),
        chapter(DemoChapter::Impact, false, "source index unavailable"),
        chapter(
            DemoChapter::Safety,
            true,
            "compiler diagnostics and plan-first rename",
        ),
        chapter(DemoChapter::Recap, true, "public command handoff"),
    ]
}

fn ranked_demo_candidates(db: &DemoDatabase) -> Result<Vec<DemoCandidate>> {
    if let Some(symbol) = db.request.symbol.as_deref() {
        let hit = db.search(symbol, 1)?.into_iter().next().ok_or_else(|| {
            CliError::new(
                "DEMO_SYMBOL_NOT_FOUND",
                format!("No indexed Kotlin symbol matches `{symbol}` in this workspace."),
            )
        })?;
        let evidence_count = hit.incoming_references + hit.outgoing_references;
        return Ok(vec![demo_candidate(
            DemoCandidateKind::ImpactHub,
            hit,
            evidence_count,
        )]);
    }

    let hits = db.search("", 30)?;
    let mut candidates = Vec::new();
    let mut selected_symbols = BTreeSet::new();
    if let Some((hit, score)) = highest_impact_hit(&hits) {
        selected_symbols.insert(hit.fq_name.clone());
        candidates.push(demo_candidate(DemoCandidateKind::ImpactHub, hit, score));
    }
    if let Some((hit, score)) = highest_call_chain_hit(db, &hits, &selected_symbols)? {
        selected_symbols.insert(hit.fq_name.clone());
        candidates.push(demo_candidate(
            DemoCandidateKind::CallChainHub,
            hit,
            score,
        ));
    }
    if let Some((hit, score)) = highest_ambiguity_hit(db, &hits, &selected_symbols)? {
        candidates.push(demo_candidate(
            DemoCandidateKind::SemanticAmbiguity,
            hit,
            score,
        ));
    }
    Ok(candidates)
}

fn highest_impact_hit(hits: &[SymbolHit]) -> Option<(SymbolHit, i64)> {
    best_scored_hit(hits, |hit| hit.incoming_references)
}

fn highest_call_chain_hit(
    db: &DemoDatabase,
    hits: &[SymbolHit],
    excluded: &BTreeSet<String>,
) -> Result<Option<(SymbolHit, i64)>> {
    let mut scored = Vec::new();
    for hit in hits {
        let incoming = db.incoming_relations(&hit.fq_name, 100)?;
        let outgoing = db.outgoing_relations(&hit.fq_name, 100)?;
        let score = incoming
            .iter()
            .chain(&outgoing)
            .filter(|relation| relation.edge_kind == "CALL")
            .map(|relation| relation.references)
            .sum();
        if score > 0 {
            scored.push((hit.clone(), score));
        }
    }
    Ok(best_ranked_candidate_excluding(scored, excluded))
}

fn highest_ambiguity_hit(
    db: &DemoDatabase,
    hits: &[SymbolHit],
    excluded: &BTreeSet<String>,
) -> Result<Option<(SymbolHit, i64)>> {
    let mut scored = Vec::new();
    for hit in hits {
        let lexical = db.lexical_compare_rows(&hit.simple_name, 30)?;
        let semantic = db.semantic_compare_rows(&hit.simple_name, 30)?;
        let buckets = build_compare_diff_buckets(&lexical, &semantic, &semantic);
        let score = (buckets.lexical_only.len() + buckets.semantic_only.len()) as i64;
        if score > 0 {
            scored.push((hit.clone(), score));
        }
    }
    Ok(best_ranked_candidate_excluding(scored, excluded))
}

fn best_scored_hit(
    hits: &[SymbolHit],
    score: impl Fn(&SymbolHit) -> i64,
) -> Option<(SymbolHit, i64)> {
    best_ranked_candidate(
        hits.iter()
            .map(|hit| (hit.clone(), score(hit)))
            .filter(|(_, score)| *score > 0)
            .collect(),
    )
}

fn best_ranked_candidate(scored: Vec<(SymbolHit, i64)>) -> Option<(SymbolHit, i64)> {
    best_ranked_candidate_excluding(scored, &BTreeSet::new())
}

fn best_ranked_candidate_excluding(
    mut scored: Vec<(SymbolHit, i64)>,
    excluded: &BTreeSet<String>,
) -> Option<(SymbolHit, i64)> {
    scored.retain(|(hit, _)| !excluded.contains(&hit.fq_name));
    scored.sort_by(|(left_hit, left_score), (right_hit, right_score)| {
        right_score
            .cmp(left_score)
            .then_with(|| left_hit.fq_name.cmp(&right_hit.fq_name))
    });
    scored.into_iter().next()
}

fn demo_candidate(kind: DemoCandidateKind, hit: SymbolHit, evidence_count: i64) -> DemoCandidate {
    let title = match kind {
        DemoCandidateKind::ImpactHub => format!("Trace the impact of {}", hit.simple_name),
        DemoCandidateKind::CallChainHub => format!("Walk the call chain around {}", hit.simple_name),
        DemoCandidateKind::SemanticAmbiguity => {
            format!("Separate text matches from {}", hit.simple_name)
        }
        DemoCandidateKind::SelectedSymbol => {
            format!("Inspect compiler evidence for {}", hit.simple_name)
        }
    };
    DemoCandidate {
        kind,
        title,
        fq_name: hit.fq_name,
        evidence_count,
        file: hit.path,
        module: hit.module_path,
    }
}

fn index_only_chapters() -> Vec<DemoChapterAvailability> {
    vec![
        chapter(DemoChapter::Identity, false, "compiler backend unavailable"),
        chapter(
            DemoChapter::SemanticDifference,
            true,
            "source-index symbol and lexical evidence",
        ),
        chapter(
            DemoChapter::Relationships,
            true,
            "source-index reference graph",
        ),
        chapter(DemoChapter::Impact, true, "source-index impact graph"),
        chapter(DemoChapter::Safety, false, "compiler backend unavailable"),
        chapter(DemoChapter::Recap, true, "public command handoff"),
    ]
}

fn chapter(
    chapter: DemoChapter,
    available: bool,
    basis: &'static str,
) -> DemoChapterAvailability {
    DemoChapterAvailability {
        chapter,
        available,
        basis,
    }
}
