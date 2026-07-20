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

impl AgentMutationResultEvidence {
    fn empty() -> Self {
        Self {
            edit_count: 0,
            edits: Vec::new(),
            files: Vec::new(),
            diagnostics: AgentDiagnosticSeverityCounts {
                error: 0,
                warning: 0,
                info: 0,
                total: 0,
            },
        }
    }
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

impl AgentMutationProjection {
    fn from_execution(
        execution: AgentMutationExecutionProjectionInput,
    ) -> std::result::Result<Self, String> {
        let (outcome, deduplicated, result, failure) = match execution {
            AgentMutationExecutionProjectionInput::Succeeded {
                result,
                deduplicated,
            } => ("SUCCEEDED", deduplicated, result.into_projection()?, None),
            AgentMutationExecutionProjectionInput::Failed {
                failure,
                deduplicated,
            } => {
                let failure = (*failure).into_projection()?;
                ("FAILED", deduplicated, failure.result, Some(failure.failure))
            }
        };
        Ok(Self {
            execution: AgentMutationExecutionProjection {
                outcome: outcome.to_string(),
                deduplicated: Some(deduplicated),
                failure,
            },
            plan: None,
            edit_count: result.edit_count,
            edits: result.edits,
            files: result.files,
            diagnostics: result.diagnostics,
        })
    }
}

impl AgentMutationAppliedResultProjectionInput {
    fn into_projection(self) -> std::result::Result<AgentMutationResultEvidence, String> {
        match self {
            Self::RenameResult { response } => {
                let AgentRenameResultProjectionInput {
                    edit_count,
                    affected_files,
                    apply_result,
                    diagnostics,
                } = response;
                if edit_count != apply_result.applied.len() {
                    return Err("rename edit count disagreed with applied edit evidence".to_string());
                }
                let mut files = affected_files;
                extend_unique(&mut files, apply_result.affected_files);
                extend_unique(&mut files, apply_result.created_files);
                extend_unique(&mut files, apply_result.deleted_files);
                for edit in &apply_result.applied {
                    if !files.contains(&edit.file_path) {
                        files.push(edit.file_path.clone());
                    }
                }
                Ok(AgentMutationResultEvidence {
                    edit_count,
                    edits: apply_result.applied,
                    files,
                    diagnostics: diagnostics.counts(),
                })
            }
            Self::ScopeMutationResult { response } => {
                let mut files = response.affected_files;
                extend_unique(&mut files, response.created_files);
                Ok(AgentMutationResultEvidence {
                    edit_count: response.edit_count,
                    edits: Vec::new(),
                    files,
                    diagnostics: response.diagnostics.counts(),
                })
            }
        }
    }
}

impl AgentMutationFailureProjectionInput {
    fn into_projection(self) -> std::result::Result<AgentMutationFailureEvidence, String> {
        const FAILURE_KINDS: [&str; 5] = [
            "RENAME_FAILURE",
            "SCOPE_MUTATION_FAILURE",
            "APPLIED_INVALID_RENAME",
            "APPLIED_INVALID_SCOPE",
            "THROWN_FAILURE",
        ];
        if !FAILURE_KINDS.contains(&self.failure_type.as_str()) {
            return Err(format!("unknown mutation failure kind {}", self.failure_type));
        }
        if self.failure_type == "THROWN_FAILURE" && self.error.is_none() {
            return Err("thrown mutation failure omitted its protocol error".to_string());
        }
        if self.failure_type != "THROWN_FAILURE" && self.response.is_none() {
            return Err("mutation failure omitted its typed response".to_string());
        }
        let response = self.response;
        let protocol_error = self
            .error
            .or_else(|| response.as_ref().and_then(|response| response.error.clone()));
        let diagnostics = response
            .as_ref()
            .and_then(|response| response.diagnostics)
            .map(AgentMutationDiagnosticsSummaryInput::counts)
            .unwrap_or(AgentDiagnosticSeverityCounts {
                error: 0,
                warning: 0,
                info: 0,
                total: 0,
            });
        let stage = response.as_ref().and_then(|response| response.stage.clone());
        let response_message = response.as_ref().and_then(|response| {
            response
                .message
                .clone()
                .or_else(|| response.error_text.clone())
        });
        let (request_id, code, message, retryable, details) = match protocol_error {
            Some(error) => (
                Some(error.request_id),
                Some(error.code),
                Some(error.message),
                Some(error.retryable),
                Some(error.details),
            ),
            None => (None, None, response_message, None, None),
        };
        let mut edit_count = 0;
        let mut edits = Vec::new();
        let mut files = Vec::new();
        if matches!(
            self.failure_type.as_str(),
            "APPLIED_INVALID_RENAME" | "APPLIED_INVALID_SCOPE"
        ) {
            let response = response
                .as_ref()
                .expect("non-thrown failure response validated above");
            edit_count = response
                .edit_count
                .ok_or_else(|| "applied invalid mutation omitted its edit count".to_string())?;
            files.clone_from(&response.affected_files);
            extend_unique(&mut files, response.created_files.clone());
            if let Some(apply_result) = &response.apply_result {
                if edit_count != apply_result.applied.len() {
                    return Err(
                        "applied invalid rename edit count disagreed with edit evidence".to_string(),
                    );
                }
                edits.clone_from(&apply_result.applied);
                extend_unique(&mut files, apply_result.affected_files.clone());
                extend_unique(&mut files, apply_result.created_files.clone());
                extend_unique(&mut files, apply_result.deleted_files.clone());
                for edit in &edits {
                    if !files.contains(&edit.file_path) {
                        files.push(edit.file_path.clone());
                    }
                }
            }
        }
        Ok(AgentMutationFailureEvidence {
            failure: AgentMutationFailureProjection {
                kind: self.failure_type,
                stage,
                request_id,
                code,
                message,
                retryable,
                details,
            },
            result: AgentMutationResultEvidence {
                edit_count,
                edits,
                files,
                diagnostics,
            },
        })
    }
}

fn extend_unique(target: &mut Vec<String>, additions: Vec<String>) {
    for addition in additions {
        if !target.contains(&addition) {
            target.push(addition);
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    execution: AgentMutationExecutionProjection,
    #[serde(skip_serializing_if = "Option::is_none")]
    plan: Option<AgentMutationPlanProjection>,
    applied_edit_count: usize,
    edits: Vec<AgentAppliedEditProjection>,
    file_count: usize,
    files: Vec<String>,
    diagnostics: AgentDiagnosticSeverityCounts,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationSelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    plan: Option<AgentMutationPlanProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    outcome: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    deduplicated: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    edits: Option<Vec<AgentAppliedEditProjection>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    files: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    diagnostics: Option<AgentDiagnosticSeverityCounts>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    execution: AgentMutationExecutionProjection,
    applied_edit_count: usize,
    file_count: usize,
    diagnostics: AgentDiagnosticSeverityCounts,
    schema_version: u32,
}

fn project_mutation_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentMutationField>,
) -> AgentEnvelope {
    if view.detailed() {
        return envelope;
    }
    let Some(result) = envelope.result.clone() else {
        return compact_error_envelope(envelope);
    };
    let input = match serde_json::from_value::<AgentMutationProjectionInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("mutation result violated the projection contract: {error}"),
            );
        }
    };
    let projection = match AgentMutationProjection::try_from(input) {
        Ok(projection) => projection,
        Err(error) => return invalid_projection_envelope(envelope.method, error),
    };
    let ok = envelope.ok;
    let method = envelope.method;
    let error = compact_agent_error(envelope.error);
    match view {
        AgentResultView::Compact => projected_agent_envelope(
            method,
            ok,
            AgentMutationCompactResult {
                result_type: "KAST_AGENT_MUTATION_RESULT",
                ok,
                execution: projection.execution,
                plan: projection.plan,
                applied_edit_count: projection.edit_count,
                edits: projection.edits,
                file_count: projection.files.len(),
                files: projection.files,
                diagnostics: projection.diagnostics,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Fields(fields) => {
            let selected = |field| fields.contains(&field);
            projected_agent_envelope(
                method,
                ok,
                AgentMutationSelectedResult {
                    result_type: "KAST_AGENT_MUTATION_SELECTION",
                    ok,
                    plan: projection.plan,
                    outcome: selected(AgentMutationField::Outcome)
                        .then_some(projection.execution.outcome),
                    deduplicated: selected(AgentMutationField::Deduplicated)
                        .then_some(projection.execution.deduplicated)
                        .flatten(),
                    edits: selected(AgentMutationField::Edits).then_some(projection.edits),
                    files: selected(AgentMutationField::Files).then_some(projection.files),
                    diagnostics: selected(AgentMutationField::Diagnostics)
                        .then_some(projection.diagnostics),
                    schema_version: SCHEMA_VERSION,
                },
                error,
            )
        }
        AgentResultView::Count => projected_agent_envelope(
            method,
            ok,
            AgentMutationCountResult {
                result_type: "KAST_AGENT_MUTATION_COUNT",
                ok,
                execution: projection.execution,
                applied_edit_count: projection.edit_count,
                file_count: projection.files.len(),
                diagnostics: projection.diagnostics,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed mutation views returned before projection")
        }
    }
}

fn mutation_kind_from_method(method: &str) -> String {
    method
        .strip_prefix("symbol/")
        .unwrap_or(method)
        .replace('-', "_")
        .to_ascii_uppercase()
}
