#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum AgentMutationProjectionInput {
    Plan(Box<AgentMutationPlanProjectionInput>),
    Execution(AgentMutationExecutionProjectionInput),
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationPlanProjectionInput {
    #[serde(rename = "type")]
    result_type: String,
    apply_required: bool,
    request: AgentMutationPlanRequestInput,
}

#[derive(Debug, Deserialize)]
struct AgentMutationPlanRequestInput {
    method: String,
    params: AgentMutationPlanParamsInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationPlanParamsInput {
    #[serde(default, rename = "type")]
    request_type: Option<String>,
    #[serde(default)]
    symbol: Option<String>,
    #[serde(default)]
    selector_handle: Option<AgentSelectorHandle>,
    #[serde(default)]
    new_name: Option<String>,
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    file_hint: Option<String>,
    #[serde(default)]
    containing_type: Option<String>,
    #[serde(default)]
    file_path: Option<String>,
    #[serde(default)]
    content_file: Option<String>,
    #[serde(default)]
    placement: Option<AgentMutationPlanPlacementInput>,
    #[serde(default)]
    inside_scope: Option<String>,
    #[serde(default, rename = "anchor")]
    statement_anchor: Option<AgentMutationPlanStatementAnchorInput>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
struct AgentMutationPlanPlacementInput {
    scope: AgentMutationPlanScopeInput,
    anchor: AgentMutationPlanAnchorInput,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentMutationPlanScopeInput {
    FileScope { inside_file: String },
    NamedScope { inside_scope: String },
}

impl AgentMutationPlanScopeInput {
    fn inside_file(&self) -> Option<&str> {
        match self {
            Self::FileScope { inside_file } => Some(inside_file),
            Self::NamedScope { .. } => None,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentMutationPlanAnchorInput {
    AtAnchor { anchor: String },
    AfterSymbol { symbol: String },
    BeforeSymbol { symbol: String },
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "kebab-case")]
enum AgentMutationPlanStatementAnchorInput {
    BodyEnd,
}

impl AgentMutationPlanStatementAnchorInput {
    fn canonical(self) -> &'static str {
        match self {
            Self::BodyEnd => "body-end",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentMutationExecutionProjectionInput {
    Succeeded {
        result: AgentMutationAppliedResultProjectionInput,
        deduplicated: bool,
    },
    Failed {
        failure: Box<AgentMutationFailureProjectionInput>,
        deduplicated: bool,
    },
}

#[derive(Debug, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentMutationAppliedResultProjectionInput {
    RenameResult {
        response: AgentRenameResultProjectionInput,
    },
    ScopeMutationResult {
        response: AgentScopeMutationResultProjectionInput,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRenameResultProjectionInput {
    edit_count: usize,
    #[serde(default)]
    affected_files: Vec<String>,
    apply_result: AgentApplyEditsResultProjectionInput,
    diagnostics: AgentMutationDiagnosticsSummaryInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentScopeMutationResultProjectionInput {
    edit_count: usize,
    #[serde(default)]
    affected_files: Vec<String>,
    #[serde(default)]
    created_files: Vec<String>,
    diagnostics: AgentMutationDiagnosticsSummaryInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentApplyEditsResultProjectionInput {
    applied: Vec<AgentAppliedEditProjection>,
    #[serde(default)]
    affected_files: Vec<String>,
    #[serde(default)]
    created_files: Vec<String>,
    #[serde(default)]
    deleted_files: Vec<String>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationDiagnosticsSummaryInput {
    error_count: usize,
    warning_count: usize,
}

impl AgentMutationDiagnosticsSummaryInput {
    fn counts(self) -> AgentDiagnosticSeverityCounts {
        AgentDiagnosticSeverityCounts {
            error: self.error_count,
            warning: self.warning_count,
            info: 0,
            total: self.error_count + self.warning_count,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentAppliedEditProjection {
    file_path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_offset: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    end_offset: Option<u64>,
    new_text: String,
}

#[derive(Debug, Deserialize)]
struct AgentMutationFailureProjectionInput {
    #[serde(rename = "type")]
    failure_type: String,
    #[serde(default)]
    response: Option<AgentMutationFailureResponseProjectionInput>,
    #[serde(default)]
    error: Option<AgentProtocolErrorProjectionInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationFailureResponseProjectionInput {
    #[serde(default)]
    stage: Option<String>,
    #[serde(default)]
    message: Option<String>,
    #[serde(default)]
    error: Option<AgentProtocolErrorProjectionInput>,
    #[serde(default)]
    error_text: Option<String>,
    #[serde(default)]
    diagnostics: Option<AgentMutationDiagnosticsSummaryInput>,
    #[serde(default)]
    edit_count: Option<usize>,
    #[serde(default)]
    affected_files: Vec<String>,
    #[serde(default)]
    created_files: Vec<String>,
    #[serde(default)]
    apply_result: Option<AgentApplyEditsResultProjectionInput>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentProtocolErrorProjectionInput {
    request_id: String,
    code: String,
    message: String,
    retryable: bool,
    #[serde(default)]
    details: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationFailureProjection {
    kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    stage: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    request_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    retryable: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    details: Option<BTreeMap<String, String>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationExecutionProjection {
    outcome: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    deduplicated: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    failure: Option<AgentMutationFailureProjection>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationPlanProjection {
    method: String,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    request_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    symbol: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    selector_handle: Option<AgentSelectorHandle>,
    #[serde(skip_serializing_if = "Option::is_none")]
    new_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    file_hint: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    file_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    content_file: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    placement: Option<AgentMutationPlanPlacementInput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    inside_scope: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    anchor: Option<AgentMutationPlanAnchorInput>,
}

#[derive(Debug)]
struct AgentMutationProjection {
    execution: AgentMutationExecutionProjection,
    plan: Option<AgentMutationPlanProjection>,
    edit_count: usize,
    edits: Vec<AgentAppliedEditProjection>,
    files: Vec<String>,
    diagnostics: AgentDiagnosticSeverityCounts,
}

#[derive(Debug)]
struct AgentMutationResultEvidence {
    edit_count: usize,
    edits: Vec<AgentAppliedEditProjection>,
    files: Vec<String>,
    diagnostics: AgentDiagnosticSeverityCounts,
}

#[derive(Debug)]
struct AgentMutationFailureEvidence {
    failure: AgentMutationFailureProjection,
    result: AgentMutationResultEvidence,
}

impl TryFrom<AgentMutationProjectionInput> for AgentMutationProjection {
    type Error = String;

    fn try_from(input: AgentMutationProjectionInput) -> std::result::Result<Self, Self::Error> {
        match input {
            AgentMutationProjectionInput::Plan(plan) => {
                let plan = *plan;
                if !matches!(
                    plan.result_type.as_str(),
                    "KAST_AGENT_MUTATION_PLAN" | "KAST_AGENT_RENAME_PLAN"
                ) || !plan.apply_required
                {
                    return Err("mutation plan did not require explicit apply".to_string());
                }
                let AgentMutationPlanRequestInput { method, params } = plan.request;
                let inside_file = params
                    .placement
                    .as_ref()
                    .and_then(|placement| placement.scope.inside_file())
                    .map(str::to_string);
                let file_path = params.file_path.or(inside_file);
                let mutation_kind = mutation_kind_from_method(&method);
                Ok(Self {
                    execution: AgentMutationExecutionProjection {
                        outcome: format!("PLANNED_{mutation_kind}"),
                        deduplicated: None,
                        failure: None,
                    },
                    plan: Some(AgentMutationPlanProjection {
                        method,
                        request_type: params.request_type,
                        symbol: params.symbol,
                        selector_handle: params.selector_handle,
                        new_name: params.new_name,
                        kind: params.kind,
                        file_hint: params.file_hint,
                        containing_type: params.containing_type,
                        file_path: file_path.clone(),
                        content_file: params.content_file,
                        placement: params.placement,
                        inside_scope: params.inside_scope,
                        anchor: params.statement_anchor.map(|anchor| {
                            AgentMutationPlanAnchorInput::AtAnchor {
                                anchor: anchor.canonical().to_string(),
                            }
                        }),
                    }),
                    edit_count: 0,
                    edits: Vec::new(),
                    files: file_path.into_iter().collect(),
                    diagnostics: AgentDiagnosticSeverityCounts {
                        error: 0,
                        warning: 0,
                        info: 0,
                        total: 0,
                    },
                })
            }
            AgentMutationProjectionInput::Execution(execution) => Self::from_execution(execution),
        }
    }
}
