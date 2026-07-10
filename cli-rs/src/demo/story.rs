#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum PublicDemoAvailability {
    IndexOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum DemoCandidateKind {
    ImpactHub,
    CallChainHub,
    SemanticAmbiguity,
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

#[derive(Debug, Clone, Serialize)]
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
struct PublicDemoSnapshot {
    #[serde(rename = "type")]
    response_type: &'static str,
    ok: bool,
    availability: PublicDemoAvailability,
    workspace_root: String,
    mutates: bool,
    candidates: Vec<DemoCandidate>,
    chapters: Vec<DemoChapterAvailability>,
    help: Vec<String>,
    schema_version: u32,
}

pub fn run_public(args: PublicDemoArgs, output_format: OutputFormat) -> Result<i32> {
    let request = DemoRequest::from_public_args(args)?;
    if !request.database.is_file() {
        return Err(public_missing_index_error(&request));
    }
    let db = DemoDatabase::open(request)?;
    let snapshot = public_demo_snapshot(&db)?;
    output::print_structured(&snapshot, output_format)?;
    Ok(0)
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
            query: None,
            limit: 30,
            json: false,
        })
    }
}

fn public_demo_snapshot(db: &DemoDatabase) -> Result<PublicDemoSnapshot> {
    let candidates = ranked_demo_candidates(db)?;
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
    Ok(PublicDemoSnapshot {
        response_type: "KAST_DEMO",
        ok: true,
        availability: PublicDemoAvailability::IndexOnly,
        workspace_root: db.request.workspace_root.display().to_string(),
        mutates: false,
        candidates,
        chapters: index_only_chapters(),
        help,
        schema_version: SCHEMA_VERSION,
    })
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
    if let Some((hit, score)) = highest_impact_hit(&hits) {
        candidates.push(demo_candidate(DemoCandidateKind::ImpactHub, hit, score));
    }
    if let Some((hit, score)) = highest_call_chain_hit(db, &hits)? {
        candidates.push(demo_candidate(
            DemoCandidateKind::CallChainHub,
            hit,
            score,
        ));
    }
    if let Some((hit, score)) = highest_ambiguity_hit(db, &hits)? {
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
    Ok(best_ranked_candidate(scored))
}

fn highest_ambiguity_hit(
    db: &DemoDatabase,
    hits: &[SymbolHit],
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
    Ok(best_ranked_candidate(scored))
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

fn best_ranked_candidate(mut scored: Vec<(SymbolHit, i64)>) -> Option<(SymbolHit, i64)> {
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
