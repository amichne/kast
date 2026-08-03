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
