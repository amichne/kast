#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerVerificationEvidence {
    pre_diagnostics: CompilerDiagnosticSnapshot,
    refresh: CompilerRefreshEvidence,
    analysis: CompilerAnalysisEvidence,
    semantic_postcondition: MutationPostconditionResult,
}

impl CompilerVerificationEvidence {
    fn validate_for(
        &self,
        operation: &StoredOperation,
        transitions: &[ExactMutationTransition],
    ) -> Result<()> {
        let expected_pre_files = transitions
            .iter()
            .filter_map(|transition| match &transition.preimage {
                ExactMutationPreimage::Absent => None,
                ExactMutationPreimage::Present { image } => Some(CompilerDiagnosticFileHash {
                    file_path: transition.absolute_path.clone(),
                    sha256: image.sha256().to_string(),
                }),
            })
            .collect::<Vec<_>>();
        let expected_post_files = transitions
            .iter()
            .map(|transition| CompilerDiagnosticFileHash {
                file_path: transition.absolute_path.clone(),
                sha256: transition.postimage.sha256().to_string(),
            })
            .collect::<Vec<_>>();
        self.pre_diagnostics
            .validate_for_files(&expected_pre_files)?;
        self.analysis
            .post_diagnostics
            .validate_for_files(&expected_post_files)?;
        let expected_paths = transitions
            .iter()
            .map(|transition| transition.absolute_path.clone())
            .collect::<Vec<_>>();
        let expected_deltas =
            compare_diagnostic_snapshots(&self.pre_diagnostics, &self.analysis.post_diagnostics)?;
        if self.analysis.outcome != CompleteCompilerAnalysis::Complete
            || self.analysis.deltas != expected_deltas
            || self.refresh.outcome != CompleteCompilerAnalysis::Complete
            || self.refresh.file_paths != expected_paths
            || self.refresh.requested_file_count != transitions.len()
            || self.refresh.analyzed_file_count != transitions.len()
            || self.refresh.skipped_file_count != 0
            || self.refresh.attempt_count == 0
            || self.refresh.schema_version != crate::SCHEMA_VERSION
        {
            return Err(compiler_verification_error(
                "Stored compiler verification evidence does not bind every exact transition.",
            ));
        }
        self.semantic_postcondition
            .validate_for(operation, transitions)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticSnapshot {
    outcome: CompleteCompilerAnalysis,
    file_hashes: Vec<CompilerDiagnosticFileHash>,
    cardinality: CompilerDiagnosticCardinality,
    severity_counts: CompilerDiagnosticSeverityCounts,
    diagnostics: Vec<CompilerDiagnosticEvidence>,
    identity_counts: Vec<CompilerDiagnosticIdentityCount>,
    page_count: usize,
}

impl CompilerDiagnosticSnapshot {
    fn empty() -> Self {
        Self {
            outcome: CompleteCompilerAnalysis::Complete,
            file_hashes: Vec::new(),
            cardinality: CompilerDiagnosticCardinality::Exact { total_count: 0 },
            severity_counts: CompilerDiagnosticSeverityCounts::default(),
            diagnostics: Vec::new(),
            identity_counts: Vec::new(),
            page_count: 0,
        }
    }

    fn validate(&self) -> Result<()> {
        if self.outcome != CompleteCompilerAnalysis::Complete
            || !self.severity_counts.is_exact()
            || self.cardinality.total_count() != self.diagnostics.len()
            || self.severity_counts.total != self.diagnostics.len()
            || self
                .file_hashes
                .windows(2)
                .any(|window| window[0].file_path >= window[1].file_path)
            || self.file_hashes.iter().any(|file| {
                !is_normalized_absolute_session_path(&file.file_path)
                    || !is_lowercase_session_sha256(&file.sha256)
            })
            || self.page_count > 1_024
            || self
                .identity_counts
                .windows(2)
                .any(|window| window[0].identity >= window[1].identity)
            || self.identity_counts.iter().any(|entry| entry.count == 0)
        {
            return Err(compiler_verification_error(
                "Compiler diagnostics did not retain one complete exact snapshot.",
            ));
        }
        let mut observed_severity = CompilerDiagnosticSeverityCounts::default();
        let mut observed_identities = BTreeMap::new();
        for diagnostic in &self.diagnostics {
            observed_severity.observe(diagnostic.identity.severity)?;
            *observed_identities
                .entry(diagnostic.identity.clone())
                .or_insert(0usize) += 1;
        }
        let expected_identities = self
            .identity_counts
            .iter()
            .map(|entry| (entry.identity.clone(), entry.count))
            .collect::<BTreeMap<_, _>>();
        if observed_severity != self.severity_counts
            || expected_identities.len() != self.identity_counts.len()
            || observed_identities != expected_identities
        {
            return Err(compiler_verification_error(
                "Compiler diagnostic records disagreed with their exact multisets.",
            ));
        }
        Ok(())
    }

    fn validate_for_files(&self, expected_files: &[CompilerDiagnosticFileHash]) -> Result<()> {
        self.validate()?;
        let expected_paths = expected_files
            .iter()
            .map(|file| file.file_path.as_str())
            .collect::<BTreeSet<_>>();
        if self.file_hashes != expected_files
            || (expected_files.is_empty() && self.page_count != 0)
            || (!expected_files.is_empty() && self.page_count == 0)
            || self.diagnostics.iter().any(|diagnostic| {
                let normalized_message = diagnostic
                    .full_message
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .join(" ");
                diagnostic.identity.canonical_path != diagnostic.location.file_path
                    || !expected_paths.contains(diagnostic.location.file_path.as_str())
                    || !is_normalized_absolute_session_path(&diagnostic.location.file_path)
                    || diagnostic.location.start_offset > diagnostic.location.end_offset
                    || diagnostic.location.start_line == 0
                    || diagnostic.location.start_column == 0
                    || normalized_message.is_empty()
                    || normalized_message != diagnostic.identity.message
                    || diagnostic.identity.code.as_ref().is_some_and(|code| {
                        code.is_empty() || code.trim() != code
                    })
            })
        {
            return Err(compiler_verification_error(
                "Compiler diagnostics do not bind the exact requested file images.",
            ));
        }
        Ok(())
    }

    fn verified_diagnostics(&self) -> VerifiedMutationDiagnostics {
        VerifiedMutationDiagnostics {
            error: self.severity_counts.error,
            warning: self.severity_counts.warning,
            info: self.severity_counts.info,
            total: self.severity_counts.total,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticFileHash {
    file_path: String,
    sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerRefreshEvidence {
    outcome: CompleteCompilerAnalysis,
    file_paths: Vec<String>,
    requested_file_count: usize,
    analyzed_file_count: usize,
    skipped_file_count: usize,
    attempt_count: usize,
    schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerAnalysisEvidence {
    outcome: CompleteCompilerAnalysis,
    post_diagnostics: CompilerDiagnosticSnapshot,
    deltas: CompilerDiagnosticDeltas,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CompleteCompilerAnalysis {
    Complete,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum CompilerDiagnosticCardinality {
    Exact { total_count: usize },
}

impl CompilerDiagnosticCardinality {
    fn total_count(self) -> usize {
        match self {
            Self::Exact { total_count } => total_count,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticSeverityCounts {
    error: usize,
    warning: usize,
    info: usize,
    total: usize,
}

impl CompilerDiagnosticSeverityCounts {
    fn is_exact(self) -> bool {
        self.error
            .checked_add(self.warning)
            .and_then(|count| count.checked_add(self.info))
            == Some(self.total)
    }

    fn observe(&mut self, severity: CompilerDiagnosticSeverity) -> Result<()> {
        let count = match severity {
            CompilerDiagnosticSeverity::Error => &mut self.error,
            CompilerDiagnosticSeverity::Warning => &mut self.warning,
            CompilerDiagnosticSeverity::Info => &mut self.info,
        };
        *count = count.checked_add(1).ok_or_else(|| {
            compiler_verification_error("Compiler diagnostic severity cardinality overflowed.")
        })?;
        self.total = self.total.checked_add(1).ok_or_else(|| {
            compiler_verification_error("Compiler diagnostic cardinality overflowed.")
        })?;
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticEvidence {
    identity: CompilerDiagnosticIdentity,
    full_message: String,
    location: CompilerDiagnosticLocationEvidence,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticIdentity {
    severity: CompilerDiagnosticSeverity,
    code: Option<String>,
    canonical_path: String,
    message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticIdentityCount {
    identity: CompilerDiagnosticIdentity,
    count: usize,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CompilerDiagnosticSeverity {
    Error,
    Warning,
    Info,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticLocationEvidence {
    file_path: String,
    start_offset: usize,
    end_offset: usize,
    start_line: usize,
    start_column: usize,
    preview: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticDeltas {
    warnings: Vec<CompilerDiagnosticIdentityDelta>,
    infos: Vec<CompilerDiagnosticIdentityDelta>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompilerDiagnosticIdentityDelta {
    identity: CompilerDiagnosticIdentity,
    pre_count: usize,
    post_count: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolRefreshEvidence {
    refreshed_files: Vec<String>,
    removed_files: Vec<String>,
    full_refresh: bool,
    file_statuses: Vec<ProtocolRefreshFileStatus>,
    #[serde(default)]
    external_failure_outcomes: Vec<Value>,
    #[serde(default)]
    relationship_failures: Vec<Value>,
    semantic_outcome: ProtocolAnalysisOutcome,
    requested_file_count: usize,
    analyzed_file_count: usize,
    skipped_file_count: usize,
    removed_file_count: usize,
    attempt_count: usize,
    elapsed_millis: u64,
    schema_version: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolRefreshFileStatus {
    file_path: String,
    file_system_discovery: String,
    source_module_ownership: String,
    index_admission: String,
    analysis_availability: String,
    analysis_status: Option<ProtocolFileStatus>,
}

impl ProtocolRefreshFileStatus {
    fn is_analyzed(&self) -> bool {
        self.file_system_discovery == "DISCOVERED"
            && self.source_module_ownership == "OWNED"
            && self.index_admission == "ADMITTED"
            && self.analysis_availability == "AVAILABLE"
            && self.analysis_status.as_ref().is_some_and(|status| {
                status.state == ProtocolFileAnalysisState::Analyzed
                    && status.file_path == self.file_path
            })
    }

    fn is_removed(&self) -> bool {
        self.file_system_discovery == "REMOVED"
            && self.source_module_ownership == "NOT_APPLICABLE"
            && self.index_admission == "NOT_APPLICABLE"
            && self.analysis_availability == "NOT_APPLICABLE"
            && self.analysis_status.is_none()
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolDiagnosticsEvidence {
    diagnostics: Vec<ProtocolDiagnostic>,
    file_statuses: Vec<ProtocolFileStatus>,
    file_hashes: Vec<ProtocolFileHash>,
    severity_counts: CompilerDiagnosticSeverityCounts,
    cardinality: CompilerDiagnosticCardinality,
    page: Option<ProtocolDiagnosticsPage>,
    semantic_outcome: ProtocolAnalysisOutcome,
    requested_file_count: usize,
    analyzed_file_count: usize,
    skipped_file_count: usize,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ProtocolAnalysisOutcome {
    Complete,
    Incomplete,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolFileStatus {
    file_path: String,
    state: ProtocolFileAnalysisState,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ProtocolFileAnalysisState {
    Analyzed,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolFileHash {
    file_path: String,
    hash: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolDiagnosticsPage {
    truncated: bool,
    next_page_token: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolDiagnostic {
    location: ProtocolDiagnosticLocation,
    severity: CompilerDiagnosticSeverity,
    message: String,
    code: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProtocolDiagnosticLocation {
    file_path: String,
    start_offset: usize,
    end_offset: usize,
    start_line: usize,
    start_column: usize,
    preview: String,
}

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

fn refresh_exact_transitions(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
    lease_id: AgentWorkspaceLeaseId,
) -> Result<CompilerRefreshEvidence> {
    let paths = transitions
        .iter()
        .map(|transition| transition.absolute_path.clone())
        .collect::<Vec<_>>();
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/workspace-refresh",
        json!({"filePaths": &paths}),
        LeasedRawOperation::ReadOnly,
    )?;
    let refresh: ProtocolRefreshEvidence = serde_json::from_value(raw).map_err(|error| {
        compiler_verification_error(format!("Compiler refresh evidence was malformed: {error}"))
    })?;
    let status_paths = refresh
        .file_statuses
        .iter()
        .map(|status| status.file_path.clone())
        .collect::<Vec<_>>();
    if refresh.semantic_outcome != ProtocolAnalysisOutcome::Complete
        || refresh.full_refresh
        || refresh.refreshed_files != paths
        || status_paths != paths
        || refresh
            .file_statuses
            .iter()
            .any(|status| !status.is_analyzed())
        || !refresh.removed_files.is_empty()
        || !refresh.external_failure_outcomes.is_empty()
        || !refresh.relationship_failures.is_empty()
        || refresh.removed_file_count != 0
        || refresh.requested_file_count != paths.len()
        || refresh.analyzed_file_count != paths.len()
        || refresh.skipped_file_count != 0
        || refresh.attempt_count == 0
        || refresh.schema_version != crate::SCHEMA_VERSION
    {
        return Err(compiler_verification_error(
            "Compiler refresh was incomplete or did not cover every exact transition.",
        ));
    }
    let _ = refresh.elapsed_millis;
    Ok(CompilerRefreshEvidence {
        outcome: CompleteCompilerAnalysis::Complete,
        file_paths: paths,
        requested_file_count: refresh.requested_file_count,
        analyzed_file_count: refresh.analyzed_file_count,
        skipped_file_count: refresh.skipped_file_count,
        attempt_count: refresh.attempt_count,
        schema_version: refresh.schema_version,
    })
}

fn refresh_restored_preimages(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
    lease_id: AgentWorkspaceLeaseId,
) -> Result<()> {
    let paths = transitions
        .iter()
        .map(|transition| transition.absolute_path.clone())
        .collect::<Vec<_>>();
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/workspace-refresh",
        json!({"filePaths": &paths}),
        LeasedRawOperation::ReadOnly,
    )?;
    let refresh: ProtocolRefreshEvidence = serde_json::from_value(raw).map_err(|error| {
        compiler_verification_error(format!(
            "Rollback compiler refresh evidence was malformed: {error}"
        ))
    })?;
    validate_restored_preimage_refresh(&refresh, transitions)
}

fn validate_restored_preimage_refresh(
    refresh: &ProtocolRefreshEvidence,
    transitions: &[ExactMutationTransition],
) -> Result<()> {
    let expected_paths = transitions
        .iter()
        .map(|transition| transition.absolute_path.clone())
        .collect::<Vec<_>>();
    let expected_refreshed = transitions
        .iter()
        .filter_map(|transition| match transition.preimage {
            ExactMutationPreimage::Present { .. } => Some(transition.absolute_path.clone()),
            ExactMutationPreimage::Absent => None,
        })
        .collect::<Vec<_>>();
    let expected_removed = transitions
        .iter()
        .filter_map(|transition| match transition.preimage {
            ExactMutationPreimage::Absent => Some(transition.absolute_path.clone()),
            ExactMutationPreimage::Present { .. } => None,
        })
        .collect::<Vec<_>>();
    let status_paths = refresh
        .file_statuses
        .iter()
        .map(|status| status.file_path.clone())
        .collect::<Vec<_>>();
    let status_matches = refresh
        .file_statuses
        .iter()
        .zip(transitions)
        .all(|(status, transition)| match transition.preimage {
            ExactMutationPreimage::Absent => status.is_removed(),
            ExactMutationPreimage::Present { .. } => status.is_analyzed(),
        });
    if refresh.semantic_outcome != ProtocolAnalysisOutcome::Complete
        || refresh.full_refresh
        || status_paths != expected_paths
        || !status_matches
        || refresh.refreshed_files != expected_refreshed
        || refresh.removed_files != expected_removed
        || !refresh.external_failure_outcomes.is_empty()
        || !refresh.relationship_failures.is_empty()
        || refresh.requested_file_count != expected_refreshed.len()
        || refresh.analyzed_file_count != expected_refreshed.len()
        || refresh.skipped_file_count != 0
        || refresh.removed_file_count != expected_removed.len()
        || refresh.attempt_count == 0
        || refresh.schema_version != crate::SCHEMA_VERSION
    {
        return Err(compiler_verification_error(
            "Rollback refresh did not prove every restored source admission or exact removal.",
        ));
    }
    let _ = refresh.elapsed_millis;
    Ok(())
}

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

fn normalize_compiler_diagnostic(
    diagnostic: ProtocolDiagnostic,
    expected_paths: &[&str],
) -> Result<CompilerDiagnosticEvidence> {
    if diagnostic.location.start_offset > diagnostic.location.end_offset
        || diagnostic.location.start_line == 0
        || diagnostic.location.start_column == 0
        || !expected_paths.contains(&diagnostic.location.file_path.as_str())
    {
        return Err(compiler_verification_error(
            "Compiler diagnostic location did not identify one requested exact file.",
        ));
    }
    let normalized_message = diagnostic
        .message
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if normalized_message.is_empty()
        || diagnostic.code.as_ref().is_some_and(|code| code.trim().is_empty())
    {
        return Err(compiler_verification_error(
            "Compiler diagnostic identity contained an empty message or code.",
        ));
    }
    let file_path = diagnostic.location.file_path;
    Ok(CompilerDiagnosticEvidence {
        identity: CompilerDiagnosticIdentity {
            severity: diagnostic.severity,
            code: diagnostic.code.map(|code| code.trim().to_string()),
            canonical_path: file_path.clone(),
            message: normalized_message,
        },
        full_message: diagnostic.message,
        location: CompilerDiagnosticLocationEvidence {
            file_path,
            start_offset: diagnostic.location.start_offset,
            end_offset: diagnostic.location.end_offset,
            start_line: diagnostic.location.start_line,
            start_column: diagnostic.location.start_column,
            preview: diagnostic.location.preview,
        },
    })
}

fn is_normalized_absolute_session_path(raw: &str) -> bool {
    let path = Path::new(raw);
    path.is_absolute()
        && path.components().all(|component| {
            matches!(
                component,
                std::path::Component::RootDir | std::path::Component::Normal(_)
            )
        })
}

fn is_lowercase_session_sha256(raw: &str) -> bool {
    raw.len() == 64
        && raw
            .bytes()
            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
}

fn compiler_verification_error(message: impl Into<String>) -> CliError {
    CliError::new("KAST_COMPILER_VERIFICATION_INVALID", message)
}

#[cfg(test)]
mod rollback_refresh_contract_tests {
    use super::*;

    fn transition(preimage: ExactMutationPreimage) -> ExactMutationTransition {
        ExactMutationTransition {
            relative_path: "src/Restored.kt".to_string(),
            absolute_path: "/workspace/src/Restored.kt".to_string(),
            preimage,
            postimage: AgentExactByteImage::from_bytes(b"post"),
        }
    }

    #[test]
    fn existing_file_rollback_requires_restored_semantic_admission() {
        let refresh = serde_json::from_value(serde_json::json!({
            "refreshedFiles": ["/workspace/src/Restored.kt"],
            "removedFiles": [],
            "fullRefresh": false,
            "fileStatuses": [{
                "filePath": "/workspace/src/Restored.kt",
                "fileSystemDiscovery": "DISCOVERED",
                "sourceModuleOwnership": "OWNED",
                "indexAdmission": "ADMITTED",
                "analysisAvailability": "AVAILABLE",
                "analysisStatus": {
                    "filePath": "/workspace/src/Restored.kt",
                    "state": "ANALYZED"
                }
            }],
            "externalFailureOutcomes": [],
            "relationshipFailures": [],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 1,
            "analyzedFileCount": 1,
            "skippedFileCount": 0,
            "removedFileCount": 0,
            "attemptCount": 1,
            "elapsedMillis": 1,
            "schemaVersion": 6
        }))
        .expect("closed refresh evidence");
        let transitions = [transition(ExactMutationPreimage::Present {
            image: AgentExactByteImage::from_bytes(b"pre"),
        })];

        assert!(validate_restored_preimage_refresh(&refresh, &transitions).is_ok());
    }

    #[test]
    fn add_file_rollback_requires_exact_removal_admission() {
        let refresh = serde_json::from_value(serde_json::json!({
            "refreshedFiles": [],
            "removedFiles": ["/workspace/src/Restored.kt"],
            "fullRefresh": false,
            "fileStatuses": [{
                "filePath": "/workspace/src/Restored.kt",
                "fileSystemDiscovery": "REMOVED",
                "sourceModuleOwnership": "NOT_APPLICABLE",
                "indexAdmission": "NOT_APPLICABLE",
                "analysisAvailability": "NOT_APPLICABLE"
            }],
            "externalFailureOutcomes": [],
            "relationshipFailures": [],
            "semanticOutcome": "COMPLETE",
            "requestedFileCount": 0,
            "analyzedFileCount": 0,
            "skippedFileCount": 0,
            "removedFileCount": 1,
            "attemptCount": 1,
            "elapsedMillis": 1,
            "schemaVersion": 6
        }))
        .expect("closed removal evidence");
        let transitions = [transition(ExactMutationPreimage::Absent)];

        assert!(validate_restored_preimage_refresh(&refresh, &transitions).is_ok());
    }
}
