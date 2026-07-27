#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentEnvelope {
    pub ok: bool,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub raw_response: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<AgentError>,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentError {
    pub code: String,
    pub message: String,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub details: BTreeMap<String, Value>,
}

const AGENT_MAX_DIAGNOSTIC_RESULTS: u32 = 500;
const AGENT_MAX_COMPACT_DIAGNOSTICS: usize = 8;

#[derive(Debug, Clone, Copy)]
struct AgentDiagnosticsResultBudget(std::num::NonZeroU16);

impl TryFrom<u32> for AgentDiagnosticsResultBudget {
    type Error = String;

    fn try_from(value: u32) -> std::result::Result<Self, Self::Error> {
        if value > AGENT_MAX_DIAGNOSTIC_RESULTS {
            return Err(format!(
                "diagnostic result limit must be at most {AGENT_MAX_DIAGNOSTIC_RESULTS}"
            ));
        }
        let value = u16::try_from(value)
            .map_err(|_| "diagnostic result limit exceeded its typed range".to_string())?;
        let value = std::num::NonZeroU16::new(value)
            .ok_or_else(|| "diagnostic result limit must be greater than 0".to_string())?;
        Ok(Self(value))
    }
}

impl AgentDiagnosticsResultBudget {
    fn request_limit(self, detailed: bool) -> u32 {
        if detailed {
            u32::from(self.0.get())
        } else {
            u32::try_from(self.projection_limit()).expect("compact diagnostic limit fits u32")
        }
    }

    fn projection_limit(self) -> usize {
        usize::from(self.0.get()).min(AGENT_MAX_COMPACT_DIAGNOSTICS)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSemanticAnalysisSummary {
    semantic_outcome: AgentSemanticAnalysisOutcome,
    requested_file_count: usize,
    analyzed_file_count: usize,
    skipped_file_count: usize,
}

impl AgentSemanticAnalysisSummary {
    fn from_result(result: &Value) -> Option<Self> {
        serde_json::from_value(result.clone()).ok()
    }

    fn is_incomplete(&self) -> bool {
        self.semantic_outcome == AgentSemanticAnalysisOutcome::Incomplete
    }
}

enum AgentSemanticAnalysisEvidence {
    NotDiagnostics,
    Valid(AgentSemanticAnalysisSummary),
    Invalid,
}

impl AgentSemanticAnalysisEvidence {
    fn from_result(method: &str, request: &Value, result: Option<&Value>) -> Self {
        let Some(result) = result else {
            return if matches!(method, "raw/diagnostics" | "raw/workspace-refresh") {
                Self::Invalid
            } else {
                Self::NotDiagnostics
            };
        };
        match method {
            "raw/diagnostics" => {
                let Ok(request) =
                    serde_json::from_value::<AgentDiagnosticsRequest>(request.clone())
                else {
                    return Self::Invalid;
                };
                serde_json::from_value::<AgentDiagnosticsResult>(result.clone())
                    .ok()
                    .and_then(|evidence| evidence.validated_summary(&request.params.file_paths))
                    .map_or(Self::Invalid, Self::Valid)
            }
            "raw/workspace-refresh" => {
                let Ok(request) = serde_json::from_value::<AgentRefreshRequest>(request.clone())
                else {
                    return Self::Invalid;
                };
                serde_json::from_value::<AgentRefreshResult>(result.clone())
                    .ok()
                    .and_then(|evidence| evidence.validated_summary(&request.params.file_paths))
                    .map_or(Self::Invalid, Self::Valid)
            }
            _ => Self::NotDiagnostics,
        }
    }
}

#[derive(Debug, Deserialize)]
struct AgentDiagnosticsRequest {
    params: AgentDiagnosticsRequestParams,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsRequestParams {
    file_paths: Vec<String>,
    #[serde(rename = "maxResults")]
    _max_results: usize,
    #[serde(default, rename = "pageToken")]
    _page_token: Option<String>,
}

#[derive(Debug, Deserialize)]
struct AgentRefreshRequest {
    params: AgentRefreshRequestParams,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRefreshRequestParams {
    file_paths: Vec<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticSeverityCounts {
    error: usize,
    warning: usize,
    info: usize,
    total: usize,
}

impl AgentDiagnosticSeverityCounts {
    fn is_valid(self) -> bool {
        self.error
            .checked_add(self.warning)
            .and_then(|count| count.checked_add(self.info))
            == Some(self.total)
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentResultCardinality {
    Exact { total_count: usize },
    KnownMinimum { known_minimum_count: usize },
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentExactCardinality {
    Exact { total_count: usize },
}

impl AgentExactCardinality {
    fn total_count(self) -> usize {
        match self {
            Self::Exact { total_count } => total_count,
        }
    }
}

impl AgentResultCardinality {
    fn known_minimum(self) -> usize {
        match self {
            Self::Exact { total_count } => total_count,
            Self::KnownMinimum {
                known_minimum_count,
            } => known_minimum_count,
        }
    }

    fn is_exact(self) -> bool {
        matches!(self, Self::Exact { .. })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsResult {
    diagnostics: Vec<AgentDiagnostic>,
    file_statuses: Vec<AgentFileAnalysisStatus>,
    file_hashes: Vec<AgentDiagnosticsFileHash>,
    severity_counts: AgentDiagnosticSeverityCounts,
    cardinality: AgentExactCardinality,
    page: Option<AgentDiagnosticsPage>,
    semantic_outcome: AgentSemanticAnalysisOutcome,
    requested_file_count: usize,
    analyzed_file_count: usize,
    skipped_file_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsFileHash {
    file_path: String,
    hash: String,
}

impl AgentDiagnosticsFileHash {
    fn has_valid_digest(&self) -> bool {
        self.hash.len() == 64
            && self
                .hash
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsPage {
    truncated: bool,
    next_page_token: Option<String>,
}

impl AgentDiagnosticsResult {
    fn summary(&self) -> AgentSemanticAnalysisSummary {
        AgentSemanticAnalysisSummary {
            semantic_outcome: self.semantic_outcome,
            requested_file_count: self.requested_file_count,
            analyzed_file_count: self.analyzed_file_count,
            skipped_file_count: self.skipped_file_count,
        }
    }

    fn validated_summary(
        self,
        requested_file_paths: &[String],
    ) -> Option<AgentSemanticAnalysisSummary> {
        let requested_file_paths = requested_file_paths
            .iter()
            .map(|file_path| normalized_absolute_path(file_path))
            .collect::<Option<Vec<_>>>()?;
        let status_file_paths = self
            .file_statuses
            .iter()
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>()?;
        let status_file_paths_match = status_file_paths == requested_file_paths;
        let exact_total = self.cardinality.total_count();
        if !status_file_paths_match
            || !self.has_valid_file_hashes()
            || !self.severity_counts.is_valid()
            || self.severity_counts.total != exact_total
            || self.diagnostics.len() > exact_total
            || self
                .file_statuses
                .iter()
                .any(|status| !status.is_valid())
            || self
                .diagnostics
                .iter()
                .any(|diagnostic| !diagnostic.is_valid())
        {
            return None;
        }

        let analyzed_file_count = self
            .file_statuses
            .iter()
            .filter(|status| status.state == AgentFileAnalysisState::Analyzed)
            .count();
        let skipped_file_count = self.file_statuses.len().checked_sub(analyzed_file_count)?;
        let has_analysis_failure = self
            .diagnostics
            .iter()
            .any(|diagnostic| diagnostic.code.as_deref() == Some("ANALYSIS_FAILURE"));
        let visible_evidence_is_incomplete = skipped_file_count > 0 || has_analysis_failure;
        let summary = self.summary();
        let semantic_outcome_is_valid = match summary.semantic_outcome {
            AgentSemanticAnalysisOutcome::Complete => !visible_evidence_is_incomplete,
            AgentSemanticAnalysisOutcome::Incomplete => {
                visible_evidence_is_incomplete
                    || self.page.as_ref().is_some_and(|page| page.truncated)
            }
        };

        if summary.requested_file_count != requested_file_paths.len()
            || summary.requested_file_count != self.file_statuses.len()
            || summary.analyzed_file_count != analyzed_file_count
            || summary.skipped_file_count != skipped_file_count
            || !semantic_outcome_is_valid
        {
            return None;
        }
        Some(summary)
    }

    fn has_valid_file_hashes(&self) -> bool {
        let analyzed_status_file_paths = self
            .file_statuses
            .iter()
            .filter(|status| status.state == AgentFileAnalysisState::Analyzed)
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>();
        let hash_file_paths = self
            .file_hashes
            .iter()
            .map(|file_hash| normalized_absolute_path(&file_hash.file_path))
            .collect::<Option<Vec<_>>>();
        analyzed_status_file_paths.is_some()
            && hash_file_paths == analyzed_status_file_paths
            && self
                .file_hashes
                .iter()
                .all(AgentDiagnosticsFileHash::has_valid_digest)
    }
}
