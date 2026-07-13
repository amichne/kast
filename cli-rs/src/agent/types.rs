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
        let Some(result) = result else {
            return if matches!(method, "raw/diagnostics" | "raw/workspace-refresh") {
                Self::Invalid
            } else {
                Self::NotDiagnostics
            };
        };
        let Ok(request) = serde_json::from_value::<AgentDiagnosticsRequest>(request.clone()) else {
            return if matches!(method, "raw/diagnostics" | "raw/workspace-refresh") {
                Self::Invalid
            } else {
                Self::NotDiagnostics
            };
        };
        match method {
            "raw/diagnostics" => serde_json::from_value::<AgentDiagnosticsResult>(result.clone())
                .ok()
                .and_then(|evidence| evidence.validated_summary(&request.params.file_paths))
                .map_or(Self::Invalid, Self::Valid),
            "raw/workspace-refresh" => {
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRefreshResult {
    refreshed_files: Vec<String>,
    removed_files: Vec<String>,
    full_refresh: bool,
    file_statuses: Vec<AgentSemanticAdmissionStatus>,
    #[serde(flatten)]
    summary: AgentSemanticAnalysisSummary,
    removed_file_count: usize,
    attempt_count: usize,
    elapsed_millis: u64,
    schema_version: u32,
}

impl AgentRefreshResult {
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
        let refreshed_file_paths = self
            .refreshed_files
            .iter()
            .map(|file_path| normalized_absolute_path(file_path))
            .collect::<Option<Vec<_>>>()?;
        let removed_file_paths = self
            .removed_files
            .iter()
            .map(|file_path| normalized_absolute_path(file_path))
            .collect::<Option<Vec<_>>>()?;

        if self.attempt_count == 0
            || self.schema_version != SCHEMA_VERSION
            || self.full_refresh != requested_file_paths.is_empty()
        {
            return None;
        }
        if self.full_refresh {
            let is_empty_complete_refresh = self.file_statuses.is_empty()
                && self.refreshed_files.is_empty()
                && self.removed_files.is_empty()
                && self.summary.semantic_outcome == AgentSemanticAnalysisOutcome::Complete
                && self.summary.requested_file_count == 0
                && self.summary.analyzed_file_count == 0
                && self.summary.skipped_file_count == 0
                && self.removed_file_count == 0;
            return is_empty_complete_refresh.then_some(self.summary);
        }

        if status_file_paths != requested_file_paths
            || self
                .file_statuses
                .iter()
                .any(|status| !status.is_valid())
        {
            return None;
        }
        let admitted_file_paths = self
            .file_statuses
            .iter()
            .filter(|status| status.is_admitted())
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>()?;
        let status_removed_file_paths = self
            .file_statuses
            .iter()
            .filter(|status| status.is_removed())
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>()?;
        let requested_file_count = self
            .file_statuses
            .iter()
            .filter(|status| !status.is_removed())
            .count();
        let analyzed_file_count = admitted_file_paths.len();
        let skipped_file_count = requested_file_count.checked_sub(analyzed_file_count)?;
        let expected_outcome = if skipped_file_count == 0 {
            AgentSemanticAnalysisOutcome::Complete
        } else {
            AgentSemanticAnalysisOutcome::Incomplete
        };

        if refreshed_file_paths != admitted_file_paths
            || removed_file_paths != status_removed_file_paths
            || self.summary.semantic_outcome != expected_outcome
            || self.summary.requested_file_count != requested_file_count
            || self.summary.analyzed_file_count != analyzed_file_count
            || self.summary.skipped_file_count != skipped_file_count
            || self.removed_file_count != status_removed_file_paths.len()
        {
            return None;
        }
        Some(self.summary)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSemanticAdmissionStatus {
    file_path: String,
    file_system_discovery: AgentFileSystemDiscoveryState,
    source_module_ownership: AgentSourceModuleOwnershipState,
    index_admission: AgentIndexAdmissionState,
    analysis_availability: AgentAnalysisAvailabilityState,
    analysis_status: Option<AgentFileAnalysisStatus>,
}

impl AgentSemanticAdmissionStatus {
    fn is_admitted(&self) -> bool {
        self.analysis_status
            .as_ref()
            .is_some_and(|status| status.state == AgentFileAnalysisState::Analyzed)
    }

    fn is_removed(&self) -> bool {
        self.file_system_discovery == AgentFileSystemDiscoveryState::Removed
    }

    fn is_valid(&self) -> bool {
        let Some(file_path) = normalized_absolute_path(&self.file_path) else {
            return false;
        };
        if self.analysis_status.as_ref().is_some_and(|status| {
            !status.is_valid()
                || normalized_absolute_path(&status.file_path).as_ref() != Some(&file_path)
        }) {
            return false;
        }
        match self.file_system_discovery {
            AgentFileSystemDiscoveryState::Removed => {
                self.source_module_ownership == AgentSourceModuleOwnershipState::NotApplicable
                    && self.index_admission == AgentIndexAdmissionState::NotApplicable
                    && self.analysis_availability
                        == AgentAnalysisAvailabilityState::NotApplicable
                    && self.analysis_status.is_none()
            }
            AgentFileSystemDiscoveryState::Pending => {
                self.source_module_ownership == AgentSourceModuleOwnershipState::NotApplicable
                    && self.index_admission == AgentIndexAdmissionState::NotApplicable
                    && self.analysis_availability == AgentAnalysisAvailabilityState::Pending
                    && self.analysis_status.as_ref().is_some_and(|status| {
                        status.state == AgentFileAnalysisState::PendingIndex
                    })
            }
            AgentFileSystemDiscoveryState::Discovered => self.is_valid_discovered_state(),
        }
    }

    fn is_valid_discovered_state(&self) -> bool {
        let Some(status) = self.analysis_status.as_ref() else {
            return false;
        };
        match self.source_module_ownership {
            AgentSourceModuleOwnershipState::OutsideSourceModules => {
                self.index_admission == AgentIndexAdmissionState::NotApplicable
                    && self.analysis_availability
                        == AgentAnalysisAvailabilityState::NotApplicable
                    && status.state == AgentFileAnalysisState::OutsideSourceModules
            }
            AgentSourceModuleOwnershipState::NotApplicable => false,
            AgentSourceModuleOwnershipState::Owned => match self.index_admission {
                AgentIndexAdmissionState::NotApplicable => false,
                AgentIndexAdmissionState::Pending => {
                    self.analysis_availability == AgentAnalysisAvailabilityState::Pending
                        && status.state == AgentFileAnalysisState::PendingIndex
                }
                AgentIndexAdmissionState::Admitted => match self.analysis_availability {
                    AgentAnalysisAvailabilityState::Available => {
                        status.state == AgentFileAnalysisState::Analyzed
                    }
                    AgentAnalysisAvailabilityState::Pending => {
                        status.state == AgentFileAnalysisState::PendingIndex
                    }
                    AgentAnalysisAvailabilityState::Failed => {
                        status.state == AgentFileAnalysisState::BackendFailure
                    }
                    AgentAnalysisAvailabilityState::NotApplicable => false,
                },
            },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentFileSystemDiscoveryState {
    Discovered,
    Pending,
    Removed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSourceModuleOwnershipState {
    Owned,
    OutsideSourceModules,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentIndexAdmissionState {
    Admitted,
    Pending,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAnalysisAvailabilityState {
    Available,
    Pending,
    Failed,
    NotApplicable,
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
    operation: AgentOperation,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AgentOperation {
    ReadOnly,
    Mutation,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolLookupResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    mode: AgentSymbolMode,
    request: Value,
    outcome: AgentSymbolLookupOutcome,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentSymbolLookupOutcome {
    Resolved {
        source: AgentSymbolLookupSource,
        symbol: Value,
        resolution: Value,
        relations: Vec<AgentSymbolRelation>,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    NotFound {
        source: AgentSymbolLookupSource,
        query: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    Ambiguous {
        source: AgentSymbolLookupSource,
        query: String,
        candidates: Vec<Value>,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    Discovered {
        source: AgentSymbolLookupSource,
        query: String,
        candidates: Vec<Value>,
    },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "kebab-case")]
enum AgentSymbolLookupSource {
    Compiler,
    IndexedExact,
    Fuzzy,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolRelation {
    relation: &'static str,
    result: Value,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCompilerFallback {
    code: String,
    message: String,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
enum AgentCompilerResolveResponse {
    #[serde(rename = "RESOLVE_SUCCESS")]
    Resolved { symbol: AgentCompilerSymbolIdentity },
    #[serde(rename = "RESOLVE_NOT_FOUND")]
    NotFound,
    #[serde(rename = "RESOLVE_AMBIGUOUS")]
    Ambiguous { candidates: Vec<Value> },
    #[serde(rename = "RESOLVE_FAILURE")]
    OperationalFailure,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCompilerSymbolIdentity {
    fq_name: String,
    #[serde(flatten)]
    fields: BTreeMap<String, Value>,
}
