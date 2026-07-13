#[derive(Debug, Serialize)]
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentError {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub details: BTreeMap<String, Value>,
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
        if method != "raw/diagnostics" {
            return Self::NotDiagnostics;
        }
        let Some(result) = result else {
            return Self::Invalid;
        };
        let Ok(request) = serde_json::from_value::<AgentDiagnosticsRequest>(request.clone()) else {
            return Self::Invalid;
        };
        let Ok(evidence) = serde_json::from_value::<AgentDiagnosticsResult>(result.clone()) else {
            return Self::Invalid;
        };
        evidence
            .validated_summary(&request.params.file_paths)
            .map_or(Self::Invalid, Self::Valid)
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
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsResult {
    diagnostics: Vec<AgentDiagnostic>,
    file_statuses: Vec<AgentFileAnalysisStatus>,
    page: Option<AgentDiagnosticsPage>,
    #[serde(flatten)]
    summary: AgentSemanticAnalysisSummary,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsPage {
    truncated: bool,
    next_page_token: Option<String>,
}

impl AgentDiagnosticsResult {
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
        if !status_file_paths_match
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
        let semantic_outcome_is_valid = match self.summary.semantic_outcome {
            AgentSemanticAnalysisOutcome::Complete => !visible_evidence_is_incomplete,
            AgentSemanticAnalysisOutcome::Incomplete => {
                visible_evidence_is_incomplete
                    || self.page.as_ref().is_some_and(|page| page.truncated)
            }
        };

        if self.summary.requested_file_count != requested_file_paths.len()
            || self.summary.requested_file_count != self.file_statuses.len()
            || self.summary.analyzed_file_count != analyzed_file_count
            || self.summary.skipped_file_count != skipped_file_count
            || !semantic_outcome_is_valid
        {
            return None;
        }
        Some(self.summary)
    }
}

fn normalized_absolute_path(raw: &str) -> Option<PathBuf> {
    let path = Path::new(raw);
    if !path.is_absolute() {
        return None;
    }
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Prefix(_) | Component::RootDir | Component::Normal(_) => {
                normalized.push(component.as_os_str());
            }
            Component::CurDir => {}
            Component::ParentDir => {
                normalized.pop();
            }
        }
    }
    Some(normalized)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentFileAnalysisStatus {
    file_path: String,
    state: AgentFileAnalysisState,
    message: Option<String>,
}

impl AgentFileAnalysisStatus {
    fn is_valid(&self) -> bool {
        if self.file_path.trim().is_empty() {
            return false;
        }
        match self.state {
            AgentFileAnalysisState::Analyzed => self.message.is_none(),
            AgentFileAnalysisState::PendingIndex
            | AgentFileAnalysisState::OutsideSourceModules
            | AgentFileAnalysisState::MissingOnDisk
            | AgentFileAnalysisState::BackendFailure => self
                .message
                .as_deref()
                .is_some_and(|message| !message.trim().is_empty()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentFileAnalysisState {
    Analyzed,
    PendingIndex,
    OutsideSourceModules,
    MissingOnDisk,
    BackendFailure,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnostic {
    location: AgentDiagnosticLocation,
    severity: AgentDiagnosticSeverity,
    message: String,
    code: Option<String>,
}

impl AgentDiagnostic {
    fn is_valid(&self) -> bool {
        self.location.is_valid()
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticLocation {
    file_path: String,
    start_offset: usize,
    end_offset: usize,
    start_line: usize,
    start_column: usize,
    preview: String,
}

impl AgentDiagnosticLocation {
    fn is_valid(&self) -> bool {
        !self.file_path.trim().is_empty() && self.start_offset <= self.end_offset
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentDiagnosticSeverity {
    Error,
    Warning,
    Info,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSemanticAnalysisOutcome {
    Complete,
    Incomplete,
}

struct AgentRequest {
    method: String,
    request: Value,
    runtime: AgentRuntimeArgs,
    full_response: bool,
}
