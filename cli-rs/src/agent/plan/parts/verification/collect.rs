fn collect_complete_diagnostics(
    workspace_root: &Path,
    expected_files: &[CompilerDiagnosticFileHash],
    lease_id: AgentWorkspaceLeaseId,
) -> Result<CompilerDiagnosticSnapshot> {
    if expected_files.is_empty() {
        return Ok(CompilerDiagnosticSnapshot::empty());
    }
    if expected_files
        .windows(2)
        .any(|window| window[0].file_path >= window[1].file_path)
        || expected_files.iter().any(|file| {
            !is_normalized_absolute_session_path(&file.file_path)
                || !is_lowercase_session_sha256(&file.sha256)
        })
    {
        return Err(compiler_verification_error(
            "Compiler diagnostic inputs were not sorted exact file images.",
        ));
    }
    let requested_paths = expected_files
        .iter()
        .map(|file| file.file_path.clone())
        .collect::<Vec<_>>();
    let mut page_token = None;
    let mut seen_tokens = BTreeSet::new();
    let mut expected_counts = None;
    let mut expected_total = None;
    let mut diagnostics = Vec::new();
    let mut page_count = 0usize;

    loop {
        page_count = page_count.checked_add(1).ok_or_else(|| {
            compiler_verification_error("Compiler diagnostic page count overflowed.")
        })?;
        if page_count > 1_024 {
            return Err(compiler_verification_error(
                "Compiler diagnostics exceeded the bounded page count.",
            ));
        }
        let mut params = json!({
            "filePaths": &requested_paths,
            "maxResults": 500,
        });
        if let Some(token) = page_token.as_ref() {
            params["pageToken"] = json!(token);
        }
        let raw = execute_leased_raw_value(
            workspace_root,
            lease_id.clone(),
            "raw/diagnostics",
            params,
            LeasedRawOperation::ReadOnly,
        )?;
        let page: ProtocolDiagnosticsEvidence = serde_json::from_value(raw).map_err(|error| {
            compiler_verification_error(format!(
                "Compiler diagnostic evidence was malformed: {error}"
            ))
        })?;
        validate_diagnostics_page(
            page,
            expected_files,
            &mut expected_counts,
            &mut expected_total,
            &mut diagnostics,
            &mut page_token,
            &mut seen_tokens,
        )?;
        if page_token.is_none() {
            break;
        }
    }

    let severity_counts = expected_counts.ok_or_else(|| {
        compiler_verification_error("Compiler diagnostics returned no severity evidence.")
    })?;
    let total_count = expected_total.ok_or_else(|| {
        compiler_verification_error("Compiler diagnostics returned no cardinality evidence.")
    })?;
    let mut identity_counts = BTreeMap::new();
    for diagnostic in &diagnostics {
        *identity_counts
            .entry(diagnostic.identity.clone())
            .or_insert(0usize) += 1;
    }
    let snapshot = CompilerDiagnosticSnapshot {
        outcome: CompleteCompilerAnalysis::Complete,
        file_hashes: expected_files.to_vec(),
        cardinality: CompilerDiagnosticCardinality::Exact { total_count },
        severity_counts,
        diagnostics,
        identity_counts: identity_counts
            .into_iter()
            .map(|(identity, count)| CompilerDiagnosticIdentityCount { identity, count })
            .collect(),
        page_count,
    };
    snapshot.validate_for_files(expected_files)?;
    Ok(snapshot)
}
