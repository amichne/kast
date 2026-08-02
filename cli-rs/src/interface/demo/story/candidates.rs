fn demo_runtime_args(request: &DemoRequest) -> RuntimeArgs {
    RuntimeArgs {
        workspace_root: Some(request.workspace_root.clone()),
        idea_home: None,
        wait_timeout_ms: crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
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
        symbol_kind: hit.kind,
        declaration_offset: hit.declaration_offset,
        evidence_count,
        file: hit.path,
        module: hit.module_path,
    }
}

fn demo_relationship_command(candidate: &DemoCandidate, command: &str) -> String {
    match (
        candidate.file.as_deref(),
        candidate.declaration_offset,
        candidate.symbol_kind.as_deref(),
    ) {
        (Some(file), Some(offset), Some(kind)) if offset >= 0 => format!(
            "kast agent {command} --symbol {} --declaration-file {} --declaration-start-offset {offset} --kind {} --workspace-root <repo>",
            candidate.fq_name,
            file,
            kind.to_ascii_lowercase(),
        ),
        _ => format!(
            "kast agent symbol --query {} --workspace-root <repo>",
            candidate.fq_name
        ),
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
