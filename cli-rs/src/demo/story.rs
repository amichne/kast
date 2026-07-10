#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum PublicDemoAvailability {
    IndexOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum DemoCandidateKind {
    ImpactHub,
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
    let requested = db
        .request
        .symbol
        .as_deref()
        .map(|symbol| db.search(symbol, 1))
        .transpose()?
        .and_then(|hits| hits.into_iter().next());
    let hit = match requested {
        Some(hit) => Some(hit),
        None => db.search("", 1)?.into_iter().next(),
    };
    Ok(hit
        .map(|hit| {
            let evidence_count = hit.incoming_references + hit.outgoing_references;
            DemoCandidate {
                kind: DemoCandidateKind::ImpactHub,
                title: format!("Trace the impact of {}", hit.simple_name),
                fq_name: hit.fq_name,
                evidence_count,
                file: hit.path,
                module: hit.module_path,
            }
        })
        .into_iter()
        .collect())
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
