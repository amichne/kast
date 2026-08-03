fn compare_diagnostic_snapshots(
    pre: &CompilerDiagnosticSnapshot,
    post: &CompilerDiagnosticSnapshot,
) -> Result<CompilerDiagnosticDeltas> {
    pre.validate()?;
    post.validate()?;
    let pre_counts = pre
        .identity_counts
        .iter()
        .map(|entry| (entry.identity.clone(), entry.count))
        .collect::<BTreeMap<_, _>>();
    let post_counts = post
        .identity_counts
        .iter()
        .map(|entry| (entry.identity.clone(), entry.count))
        .collect::<BTreeMap<_, _>>();
    let identities = pre_counts
        .keys()
        .chain(post_counts.keys())
        .cloned()
        .collect::<BTreeSet<_>>();
    let mut warnings = Vec::new();
    let mut infos = Vec::new();
    for identity in identities {
        let pre_count = pre_counts.get(&identity).copied().unwrap_or(0);
        let post_count = post_counts.get(&identity).copied().unwrap_or(0);
        if identity.severity == CompilerDiagnosticSeverity::Error && post_count > pre_count {
            return Err(CliError::new(
                "KAST_NEW_COMPILER_ERROR",
                "The mutation introduced a positive ERROR diagnostic multiset delta.",
            ));
        }
        if pre_count == post_count {
            continue;
        }
        let delta = CompilerDiagnosticIdentityDelta {
            identity: identity.clone(),
            pre_count,
            post_count,
        };
        match identity.severity {
            CompilerDiagnosticSeverity::Warning => warnings.push(delta),
            CompilerDiagnosticSeverity::Info => infos.push(delta),
            CompilerDiagnosticSeverity::Error => {}
        }
    }
    Ok(CompilerDiagnosticDeltas { warnings, infos })
}

#[allow(clippy::too_many_arguments)]
fn validate_diagnostics_page(
    page: ProtocolDiagnosticsEvidence,
    expected_files: &[CompilerDiagnosticFileHash],
    expected_counts: &mut Option<CompilerDiagnosticSeverityCounts>,
    expected_total: &mut Option<usize>,
    diagnostics: &mut Vec<CompilerDiagnosticEvidence>,
    next_page: &mut Option<String>,
    seen_tokens: &mut BTreeSet<String>,
) -> Result<()> {
    let expected_paths = expected_files
        .iter()
        .map(|file| file.file_path.as_str())
        .collect::<Vec<_>>();
    let status_paths = page
        .file_statuses
        .iter()
        .map(|status| status.file_path.as_str())
        .collect::<Vec<_>>();
    let hashes = page
        .file_hashes
        .iter()
        .map(|file| CompilerDiagnosticFileHash {
            file_path: file.file_path.clone(),
            sha256: file.hash.clone(),
        })
        .collect::<Vec<_>>();
    if page.semantic_outcome != ProtocolAnalysisOutcome::Complete
        || page.requested_file_count != expected_files.len()
        || page.analyzed_file_count != expected_files.len()
        || page.skipped_file_count != 0
        || status_paths != expected_paths
        || page
            .file_statuses
            .iter()
            .any(|status| status.state != ProtocolFileAnalysisState::Analyzed)
        || hashes != expected_files
        || !page.severity_counts.is_exact()
        || page.severity_counts.total != page.cardinality.total_count()
        || expected_counts.is_some_and(|counts| counts != page.severity_counts)
        || expected_total.is_some_and(|total| total != page.cardinality.total_count())
    {
        return Err(compiler_verification_error(
            "Compiler diagnostics were incomplete or disagreed with exact file images.",
        ));
    }
    *expected_counts = Some(page.severity_counts);
    *expected_total = Some(page.cardinality.total_count());
    for diagnostic in page.diagnostics {
        diagnostics.push(normalize_compiler_diagnostic(diagnostic, &expected_paths)?);
    }
    *next_page = match page.page {
        None => None,
        Some(ProtocolDiagnosticsPage {
            truncated: false,
            next_page_token: None,
        }) => None,
        Some(ProtocolDiagnosticsPage {
            truncated: true,
            next_page_token: Some(token),
        }) if !token.trim().is_empty() && seen_tokens.insert(token.clone()) => Some(token),
        _ => {
            return Err(compiler_verification_error(
                "Compiler diagnostic pagination was truncated, malformed, or cyclic.",
            ));
        }
    };
    Ok(())
}
