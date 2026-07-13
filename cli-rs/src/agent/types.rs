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
    fn is_incomplete(&self) -> bool {
        self.semantic_outcome == AgentSemanticAnalysisOutcome::Incomplete
    }
}

enum AgentSemanticAnalysisEvidence {
    Absent,
    Valid(AgentSemanticAnalysisSummary),
    Invalid,
}

impl AgentSemanticAnalysisEvidence {
    fn from_result(result: &Value) -> Self {
        const FIELDS: [&str; 4] = [
            "semanticOutcome",
            "requestedFileCount",
            "analyzedFileCount",
            "skippedFileCount",
        ];
        if FIELDS.iter().all(|field| result.get(field).is_none()) {
            return Self::Absent;
        }
        let Ok(summary) = serde_json::from_value::<AgentSemanticAnalysisSummary>(result.clone())
        else {
            return Self::Invalid;
        };
        let Some(classified_file_count) = summary
            .analyzed_file_count
            .checked_add(summary.skipped_file_count)
        else {
            return Self::Invalid;
        };
        if summary.requested_file_count != classified_file_count {
            return Self::Invalid;
        }
        Self::Valid(summary)
    }
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
