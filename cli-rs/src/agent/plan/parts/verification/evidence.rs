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
