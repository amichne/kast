#[derive(Debug, Clone)]
enum AgentResultView<Field> {
    Compact,
    Fields(Vec<Field>),
    Count,
    Verbose,
    Explain,
}

impl<Field: Clone> AgentResultView<Field> {
    fn from_parts(
        verbose: bool,
        explain: bool,
        fields: &[Field],
        count: bool,
    ) -> Self {
        if verbose {
            Self::Verbose
        } else if explain {
            Self::Explain
        } else if count {
            Self::Count
        } else if fields.is_empty() {
            Self::Compact
        } else {
            Self::Fields(fields.to_vec())
        }
    }

    fn detailed(&self) -> bool {
        matches!(self, Self::Verbose | Self::Explain)
    }
}

#[derive(Debug, Clone)]
enum AgentProjectionRequest {
    Passthrough,
    Symbol(AgentResultView<AgentSymbolField>),
    Diagnostics(AgentResultView<AgentDiagnosticsField>),
    Mutation(AgentResultView<AgentMutationField>),
    Verify(AgentResultView<AgentVerifyField>),
}

impl AgentProjectionRequest {
    fn for_command(command: &AgentCommand) -> Self {
        match command {
            AgentCommand::Verify(args) => Self::Verify(verify_result_view(&args.view)),
            AgentCommand::Symbol(args) => Self::Symbol(symbol_result_view(&args.view)),
            AgentCommand::Diagnostics(args) => {
                Self::Diagnostics(diagnostics_result_view(&args.view))
            }
            AgentCommand::Rename(args) => Self::Mutation(mutation_result_view(&args.mutation.view)),
            AgentCommand::AddFile(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::AddDeclaration(args) | AgentCommand::AddImplementation(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::AddStatement(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::ReplaceDeclaration(args) => {
                Self::Mutation(mutation_result_view(&args.mutation.view))
            }
            AgentCommand::Operation(args) => match &args.command {
                AgentOperationCommand::Status(args) | AgentOperationCommand::Cancel(args) => {
                    Self::Mutation(mutation_result_view(&args.view))
                }
            },
            AgentCommand::Lsp(_)
            | AgentCommand::Impact(_)
            | AgentCommand::Tools(_)
            | AgentCommand::Call(_)
            | AgentCommand::Workflow(_) => Self::Passthrough,
        }
    }

    fn project(self, envelope: AgentEnvelope) -> AgentEnvelope {
        match self {
            Self::Symbol(view) => project_symbol_envelope(envelope, view),
            Self::Diagnostics(view) => project_diagnostics_envelope(envelope, view),
            Self::Mutation(view) => project_mutation_envelope(envelope, view),
            Self::Verify(view) => project_verify_envelope(envelope, view),
            Self::Passthrough => envelope,
        }
    }
}

fn verify_result_view(view: &AgentVerifyViewArgs) -> AgentResultView<AgentVerifyField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn symbol_result_view(view: &AgentSymbolViewArgs) -> AgentResultView<AgentSymbolField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn diagnostics_result_view(
    view: &AgentDiagnosticsViewArgs,
) -> AgentResultView<AgentDiagnosticsField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

fn mutation_result_view(view: &AgentMutationViewArgs) -> AgentResultView<AgentMutationField> {
    AgentResultView::from_parts(view.verbose, view.explain, &view.fields, view.count)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentStepCommandProjectionInput {
    #[serde(rename = "type")]
    result_type: String,
    steps: Vec<AgentStepProjectionInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentStepProjectionInput {
    name: String,
    method: String,
    ok: bool,
    #[serde(default)]
    result: Option<Value>,
    #[serde(default)]
    error: Option<AgentError>,
}

impl AgentStepCommandProjectionInput {
    fn validated(result: Value) -> std::result::Result<Self, String> {
        let input = serde_json::from_value::<Self>(result).map_err(|error| error.to_string())?;
        if input.result_type != "KAST_AGENT_COMMAND" {
            return Err(format!(
                "expected KAST_AGENT_COMMAND, found {}",
                input.result_type
            ));
        }
        Ok(input)
    }

    fn step(&self, method: &str) -> Option<&AgentStepProjectionInput> {
        self.steps.iter().find(|step| step.method == method)
    }

    fn first_error(&self) -> Option<AgentError> {
        self.steps.iter().find_map(|step| step.error.clone())
    }
}

#[derive(Debug, Clone, Copy, Serialize)]
struct AgentDiagnosticSeverityCounts {
    error: usize,
    warning: usize,
    info: usize,
    total: usize,
}

impl AgentDiagnosticSeverityCounts {
    fn from_diagnostics(diagnostics: &[AgentDiagnostic]) -> Self {
        Self::from_severities(diagnostics.iter().map(|diagnostic| diagnostic.severity))
    }

    fn from_severities(severities: impl IntoIterator<Item = AgentDiagnosticSeverity>) -> Self {
        let mut error = 0;
        let mut warning = 0;
        let mut info = 0;
        for severity in severities {
            match severity {
                AgentDiagnosticSeverity::Error => error += 1,
                AgentDiagnosticSeverity::Warning => warning += 1,
                AgentDiagnosticSeverity::Info => info += 1,
            }
        }
        Self {
            error,
            warning,
            info,
            total: error + warning + info,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    analysis: AgentSemanticAnalysisSummary,
    severity_counts: AgentDiagnosticSeverityCounts,
    diagnostics: Vec<AgentDiagnostic>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsSelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    analysis: Option<AgentSemanticAnalysisSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    severity_counts: Option<AgentDiagnosticSeverityCounts>,
    #[serde(skip_serializing_if = "Option::is_none")]
    diagnostics: Option<Vec<AgentDiagnostic>>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    analysis: AgentSemanticAnalysisSummary,
    severity_counts: AgentDiagnosticSeverityCounts,
    schema_version: u32,
}

fn project_diagnostics_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentDiagnosticsField>,
) -> AgentEnvelope {
    if view.detailed() {
        return envelope;
    }
    let Some(result) = envelope.result.clone() else {
        return compact_error_envelope(envelope);
    };
    let command = match AgentStepCommandProjectionInput::validated(result) {
        Ok(command) => command,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("diagnostics result violated the projection contract: {error}"),
            );
        }
    };
    let Some(step) = command.step("raw/diagnostics") else {
        return invalid_projection_envelope(
            envelope.method,
            "diagnostics result did not contain the diagnostics step",
        );
    };
    let Some(result) = step.result.clone() else {
        return compact_command_error_envelope(envelope, &command);
    };
    let diagnostics = match serde_json::from_value::<AgentDiagnosticsResult>(result) {
        Ok(diagnostics) => diagnostics,
        Err(error) => {
            if let Some(error) = step.error.clone() {
                return AgentEnvelope {
                    ok: false,
                    method: envelope.method,
                    request: None,
                    response: None,
                    result: None,
                    raw_response: None,
                    error: compact_agent_error(Some(error)),
                    schema_version: SCHEMA_VERSION,
                };
            }
            return invalid_projection_envelope(
                envelope.method,
                format!("diagnostics evidence violated the projection contract: {error}"),
            );
        }
    };
    let analysis = diagnostics.summary;
    let severity_counts = AgentDiagnosticSeverityCounts::from_diagnostics(&diagnostics.diagnostics);
    let ok = envelope.ok;
    let method = envelope.method;
    let error = compact_agent_error(step.error.clone().or(envelope.error));
    match view {
        AgentResultView::Compact => projected_agent_envelope(
            method,
            ok,
            AgentDiagnosticsCompactResult {
                result_type: "KAST_AGENT_DIAGNOSTICS_RESULT",
                ok,
                analysis,
                severity_counts,
                diagnostics: diagnostics.diagnostics,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Fields(fields) => {
            let selected = |field| fields.contains(&field);
            projected_agent_envelope(
                method,
                ok,
                AgentDiagnosticsSelectedResult {
                    result_type: "KAST_AGENT_DIAGNOSTICS_SELECTION",
                    ok,
                    analysis: selected(AgentDiagnosticsField::Analysis).then_some(analysis),
                    severity_counts: selected(AgentDiagnosticsField::SeverityCounts)
                        .then_some(severity_counts),
                    diagnostics: selected(AgentDiagnosticsField::Diagnostics)
                        .then_some(diagnostics.diagnostics),
                    schema_version: SCHEMA_VERSION,
                },
                error,
            )
        }
        AgentResultView::Count => projected_agent_envelope(
            method,
            ok,
            AgentDiagnosticsCountResult {
                result_type: "KAST_AGENT_DIAGNOSTICS_COUNT",
                ok,
                analysis,
                severity_counts,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed diagnostics views returned before projection")
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRuntimeProjection {
    state: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    healthy: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    active: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    indexing: Option<bool>,
    backend_name: String,
    backend_version: String,
    workspace_root: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentServerLimitsProjection {
    request_timeout_millis: u64,
    max_results: u64,
    max_concurrent_requests: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentCapabilitiesProjectionInput {
    read_capabilities: Vec<String>,
    mutation_capabilities: Vec<String>,
    #[serde(default)]
    limits: Option<AgentServerLimitsProjection>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCapabilitiesProjection {
    read_count: usize,
    mutation_count: usize,
    read: Vec<String>,
    mutation: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    limits: Option<AgentServerLimitsProjection>,
}

impl From<AgentCapabilitiesProjectionInput> for AgentCapabilitiesProjection {
    fn from(value: AgentCapabilitiesProjectionInput) -> Self {
        Self {
            read_count: value.read_capabilities.len(),
            mutation_count: value.mutation_capabilities.len(),
            read: value.read_capabilities,
            mutation: value.mutation_capabilities,
            limits: value.limits,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
struct AgentHealthProjection {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<String>,
}

#[derive(Debug, Deserialize)]
struct AgentHealthProjectionInput {
    #[serde(default)]
    status: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentVerifyCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    health: AgentHealthProjection,
    runtime: AgentRuntimeProjection,
    capabilities: AgentCapabilitiesProjection,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentVerifySelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    health: Option<AgentHealthProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    runtime: Option<AgentRuntimeProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    capabilities: Option<AgentCapabilitiesProjection>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentVerifyCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    check_count: usize,
    passed_count: usize,
    failed_count: usize,
    read_capability_count: usize,
    mutation_capability_count: usize,
    schema_version: u32,
}

fn project_verify_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentVerifyField>,
) -> AgentEnvelope {
    if view.detailed() {
        return envelope;
    }
    let Some(result) = envelope.result.clone() else {
        return compact_error_envelope(envelope);
    };
    let command = match AgentStepCommandProjectionInput::validated(result) {
        Ok(command) => command,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("verification result violated the projection contract: {error}"),
            );
        }
    };
    let Some(health_step) = command.step("health") else {
        return if envelope.ok {
            invalid_projection_envelope(envelope.method, "verification result omitted health")
        } else {
            compact_command_error_envelope(envelope, &command)
        };
    };
    let Some(runtime_step) = command.step("runtime/status") else {
        return if envelope.ok {
            invalid_projection_envelope(
                envelope.method,
                "verification result omitted runtime status",
            )
        } else {
            compact_command_error_envelope(envelope, &command)
        };
    };
    let Some(capabilities_step) = command.step("capabilities") else {
        return if envelope.ok {
            invalid_projection_envelope(
                envelope.method,
                "verification result omitted capabilities",
            )
        } else {
            compact_command_error_envelope(envelope, &command)
        };
    };
    let runtime = match runtime_step
        .result
        .clone()
        .ok_or_else(|| "runtime status did not contain a result".to_string())
        .and_then(|result| {
            serde_json::from_value::<AgentRuntimeProjection>(result)
                .map_err(|error| error.to_string())
        }) {
        Ok(runtime) => runtime,
        Err(error) => return invalid_projection_envelope(envelope.method, error),
    };
    let capabilities = match capabilities_step
        .result
        .clone()
        .ok_or_else(|| "capabilities did not contain a result".to_string())
        .and_then(|result| {
            serde_json::from_value::<AgentCapabilitiesProjectionInput>(result)
                .map_err(|error| error.to_string())
        }) {
        Ok(capabilities) => AgentCapabilitiesProjection::from(capabilities),
        Err(error) => return invalid_projection_envelope(envelope.method, error),
    };
    let health_input = health_step
        .result
        .clone()
        .and_then(|result| serde_json::from_value::<AgentHealthProjectionInput>(result).ok());
    let health = AgentHealthProjection {
        ok: health_step.ok,
        status: health_input.and_then(|input| input.status),
    };
    let check_count = command.steps.len();
    let passed_count = command.steps.iter().filter(|step| step.ok).count();
    let failed_count = check_count.saturating_sub(passed_count);
    let ok = envelope.ok;
    let method = envelope.method;
    let error = compact_agent_error(envelope.error);
    match view {
        AgentResultView::Compact => projected_agent_envelope(
            method,
            ok,
            AgentVerifyCompactResult {
                result_type: "KAST_AGENT_VERIFY_RESULT",
                ok,
                health,
                runtime,
                capabilities,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Fields(fields) => {
            let selected = |field| fields.contains(&field);
            projected_agent_envelope(
                method,
                ok,
                AgentVerifySelectedResult {
                    result_type: "KAST_AGENT_VERIFY_SELECTION",
                    ok,
                    health: selected(AgentVerifyField::Health).then_some(health),
                    runtime: selected(AgentVerifyField::Runtime).then_some(runtime),
                    capabilities: selected(AgentVerifyField::Capabilities)
                        .then_some(capabilities),
                    schema_version: SCHEMA_VERSION,
                },
                error,
            )
        }
        AgentResultView::Count => projected_agent_envelope(
            method,
            ok,
            AgentVerifyCountResult {
                result_type: "KAST_AGENT_VERIFY_COUNT",
                ok,
                check_count,
                passed_count,
                failed_count,
                read_capability_count: capabilities.read_count,
                mutation_capability_count: capabilities.mutation_count,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed verification views returned before projection")
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum AgentMutationProjectionInput {
    Plan(AgentMutationPlanProjectionInput),
    Receipt(AgentMutationReceiptProjectionInput),
    Snapshot(AgentMutationOperationProjectionInput),
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
    mutation_kind: Option<String>,
    #[serde(default)]
    symbol: Option<String>,
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
}

#[derive(Debug, Deserialize)]
struct AgentMutationPlanPlacementInput {
    scope: AgentMutationPlanScopeInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationPlanScopeInput {
    #[serde(default)]
    inside_file: Option<String>,
    #[serde(default)]
    inside_scope: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationReceiptProjectionInput {
    operation: AgentMutationOperationProjectionInput,
    deduplicated: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationOperationProjectionInput {
    operation_id: String,
    idempotency_key: String,
    mutation_kind: String,
    state: AgentMutationStateProjectionInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationStateProjectionInput {
    #[serde(rename = "type")]
    state_type: String,
    trace: AgentMutationTraceProjectionInput,
    cancellation_requested: bool,
    #[serde(default)]
    stage: Option<String>,
    #[serde(default)]
    result: Option<AgentMutationAppliedResultProjectionInput>,
    #[serde(default)]
    failure: Option<AgentMutationFailureProjectionInput>,
    #[serde(default)]
    message: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationTraceProjectionInput {
    edit_application_state: String,
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
struct AgentProtocolErrorProjectionInput {
    code: String,
    message: String,
    retryable: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationFailureProjection {
    kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    stage: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    retryable: Option<bool>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationOperationProjection {
    #[serde(skip_serializing_if = "Option::is_none")]
    operation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    idempotency_key: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    mutation_kind: Option<String>,
    state: String,
    edit_application_state: String,
    cancellation_requested: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    stage: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    failure: Option<AgentMutationFailureProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    deduplicated: Option<bool>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentMutationPlanProjection {
    method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    symbol: Option<String>,
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
    inside_scope: Option<String>,
}

#[derive(Debug)]
struct AgentMutationProjection {
    operation: AgentMutationOperationProjection,
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
                    .and_then(|placement| placement.scope.inside_file.clone());
                let inside_scope = params
                    .placement
                    .as_ref()
                    .and_then(|placement| placement.scope.inside_scope.clone());
                let file_path = params.file_path.or(inside_file);
                let mutation_kind = params
                    .mutation_kind
                    .unwrap_or_else(|| mutation_kind_from_method(&method));
                Ok(Self {
                    operation: AgentMutationOperationProjection {
                        operation_id: None,
                        idempotency_key: None,
                        mutation_kind: Some(mutation_kind),
                        state: "PLANNED".to_string(),
                        edit_application_state: "NOT_STARTED".to_string(),
                        cancellation_requested: false,
                        stage: None,
                        failure: None,
                        message: None,
                        deduplicated: None,
                    },
                    plan: Some(AgentMutationPlanProjection {
                        method,
                        symbol: params.symbol,
                        new_name: params.new_name,
                        kind: params.kind,
                        file_hint: params.file_hint,
                        containing_type: params.containing_type,
                        file_path: file_path.clone(),
                        content_file: params.content_file,
                        inside_scope,
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
            AgentMutationProjectionInput::Receipt(receipt) => {
                Self::from_operation(receipt.operation, Some(receipt.deduplicated))
            }
            AgentMutationProjectionInput::Snapshot(operation) => {
                Self::from_operation(operation, None)
            }
        }
    }
}

impl AgentMutationProjection {
    fn from_operation(
        operation: AgentMutationOperationProjectionInput,
        deduplicated: Option<bool>,
    ) -> std::result::Result<Self, String> {
        if operation.operation_id.trim().is_empty()
            || operation.idempotency_key.trim().is_empty()
            || operation.mutation_kind.trim().is_empty()
            || operation.state.state_type.trim().is_empty()
            || operation
                .state
                .trace
                .edit_application_state
                .trim()
                .is_empty()
        {
            return Err("mutation operation identity or state was empty".to_string());
        }
        let AgentMutationStateProjectionInput {
            state_type,
            trace,
            cancellation_requested,
            stage,
            result,
            failure,
            message,
        } = operation.state;
        let (result, failure, message) = match state_type.as_str() {
            "COMPLETED" => {
                let result = result
                    .ok_or_else(|| "completed mutation omitted its typed result".to_string())?;
                (result.into_projection()?, None, None)
            }
            "FAILED" => {
                let failure = failure
                    .ok_or_else(|| "failed mutation omitted its typed failure".to_string())?;
                let failure = failure.into_projection()?;
                (failure.result, Some(failure.failure), None)
            }
            "CANCELLED" => {
                let message = message
                    .filter(|message| !message.trim().is_empty())
                    .ok_or_else(|| "cancelled mutation omitted its message".to_string())?;
                if !cancellation_requested {
                    return Err("cancelled mutation did not retain its cancellation request".into());
                }
                (AgentMutationResultEvidence::empty(), None, Some(message))
            }
            "QUEUED" | "APPLYING" | "VALIDATING" => {
                if matches!(state_type.as_str(), "APPLYING" | "VALIDATING")
                    && stage.as_ref().is_none_or(|stage| stage.trim().is_empty())
                {
                    return Err("active mutation state omitted its progress stage".to_string());
                }
                (AgentMutationResultEvidence::empty(), None, None)
            }
            other => return Err(format!("unknown mutation operation state {other}")),
        };
        Ok(Self {
            operation: AgentMutationOperationProjection {
                operation_id: Some(operation.operation_id),
                idempotency_key: Some(operation.idempotency_key),
                mutation_kind: Some(operation.mutation_kind),
                state: state_type,
                edit_application_state: trace.edit_application_state,
                cancellation_requested,
                stage,
                failure,
                message,
                deduplicated,
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
        let (code, message, retryable) = match protocol_error {
            Some(error) => (Some(error.code), Some(error.message), Some(error.retryable)),
            None => (None, response_message, None),
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
                code,
                message,
                retryable,
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
    operation: AgentMutationOperationProjection,
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
    operation: Option<AgentMutationOperationProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    plan: Option<AgentMutationPlanProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    state: Option<AgentMutationOperationProjection>,
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
    operation: AgentMutationOperationProjection,
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
                operation: projection.operation,
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
                    operation: selected(AgentMutationField::Operation)
                        .then_some(projection.operation.clone()),
                    plan: selected(AgentMutationField::Operation)
                        .then_some(projection.plan)
                        .flatten(),
                    state: selected(AgentMutationField::State)
                        .then_some(projection.operation),
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
                operation: projection.operation,
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolLookupProjectionInput {
    mode: AgentSymbolMode,
    outcome: AgentSymbolOutcomeProjectionInput,
}

#[derive(Debug, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentSymbolOutcomeProjectionInput {
    Resolved {
        source: String,
        symbol: Value,
        #[serde(default)]
        relations: Vec<AgentSymbolRelationProjectionInput>,
    },
    NotFound {
        source: String,
        query: String,
    },
    Ambiguous {
        source: String,
        query: String,
        candidates: Vec<Value>,
    },
    Discovered {
        source: String,
        query: String,
        candidates: Vec<Value>,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolRelationProjectionInput {
    relation: String,
    result: AgentRelationshipResultInput,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationshipResultInput {
    #[serde(default)]
    references: Vec<AgentLocationInput>,
    #[serde(default)]
    calls: Vec<AgentCallInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentCallInput {
    #[serde(default)]
    symbol: Option<String>,
    #[serde(default)]
    caller: Option<String>,
    #[serde(default)]
    callee: Option<String>,
    #[serde(default)]
    location: Option<AgentLocationInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolEvidenceInput {
    #[serde(default)]
    fq_name: Option<String>,
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    location: Option<AgentLocationInput>,
    #[serde(default)]
    declaration: Option<AgentIndexedDeclarationInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentIndexedDeclarationInput {
    fq_name: String,
    kind: String,
    file: AgentIndexedFileInput,
    #[serde(default)]
    declaration_offset: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct AgentIndexedFileInput {
    path: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentLocationInput {
    file_path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_offset: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    end_offset: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_line: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_column: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    preview: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolIdentityProjection {
    fq_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
}

#[derive(Debug, Clone)]
struct AgentSymbolEvidenceProjection {
    identity: AgentSymbolIdentityProjection,
    location: Option<AgentLocationInput>,
}

impl TryFrom<Value> for AgentSymbolEvidenceProjection {
    type Error = String;

    fn try_from(value: Value) -> std::result::Result<Self, Self::Error> {
        let input = serde_json::from_value::<AgentSymbolEvidenceInput>(value)
            .map_err(|error| error.to_string())?;
        match (input.fq_name, input.declaration) {
            (Some(fq_name), _) => Ok(Self {
                identity: AgentSymbolIdentityProjection {
                    fq_name,
                    kind: input.kind,
                },
                location: input.location,
            }),
            (None, Some(declaration)) => Ok(Self {
                identity: AgentSymbolIdentityProjection {
                    fq_name: declaration.fq_name,
                    kind: Some(declaration.kind),
                },
                location: Some(AgentLocationInput {
                    file_path: declaration.file.path,
                    start_offset: declaration.declaration_offset,
                    end_offset: None,
                    start_line: None,
                    start_column: None,
                    preview: None,
                }),
            }),
            (None, None) => Err("symbol evidence did not contain fqName or declaration".to_string()),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolCandidateProjection {
    identity: AgentSymbolIdentityProjection,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
}

impl From<AgentSymbolEvidenceProjection> for AgentSymbolCandidateProjection {
    fn from(value: AgentSymbolEvidenceProjection) -> Self {
        Self {
            identity: value.identity,
            location: value.location,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationshipItemProjection {
    #[serde(skip_serializing_if = "Option::is_none")]
    symbol: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationshipProjection {
    relation: String,
    count: usize,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    items: Vec<AgentRelationshipItemProjection>,
}

#[derive(Debug, Clone)]
struct AgentSymbolProjection {
    mode: AgentSymbolMode,
    outcome: &'static str,
    ambiguous: bool,
    source: String,
    query: Option<String>,
    identity: Option<AgentSymbolIdentityProjection>,
    location: Option<AgentLocationInput>,
    candidates: Vec<AgentSymbolCandidateProjection>,
    relationships: Vec<AgentRelationshipProjection>,
}

impl TryFrom<AgentSymbolLookupProjectionInput> for AgentSymbolProjection {
    type Error = String;

    fn try_from(input: AgentSymbolLookupProjectionInput) -> std::result::Result<Self, Self::Error> {
        let mode = input.mode;
        match input.outcome {
            AgentSymbolOutcomeProjectionInput::Resolved {
                source,
                symbol,
                relations,
            } => {
                let symbol = AgentSymbolEvidenceProjection::try_from(symbol)?;
                Ok(Self {
                    mode,
                    outcome: "RESOLVED",
                    ambiguous: false,
                    source,
                    query: None,
                    identity: Some(symbol.identity),
                    location: symbol.location,
                    candidates: Vec::new(),
                    relationships: relations
                        .into_iter()
                        .map(AgentRelationshipProjection::from)
                        .collect(),
                })
            }
            AgentSymbolOutcomeProjectionInput::NotFound { source, query } => Ok(Self {
                mode,
                outcome: "NOT_FOUND",
                ambiguous: false,
                source,
                query: Some(query),
                identity: None,
                location: None,
                candidates: Vec::new(),
                relationships: Vec::new(),
            }),
            AgentSymbolOutcomeProjectionInput::Ambiguous {
                source,
                query,
                candidates,
            } => Ok(Self {
                mode,
                outcome: "AMBIGUOUS",
                ambiguous: true,
                source,
                query: Some(query),
                identity: None,
                location: None,
                candidates: project_symbol_candidates(candidates)?,
                relationships: Vec::new(),
            }),
            AgentSymbolOutcomeProjectionInput::Discovered {
                source,
                query,
                candidates,
            } => Ok(Self {
                mode,
                outcome: "DISCOVERED",
                ambiguous: false,
                source,
                query: Some(query),
                identity: None,
                location: None,
                candidates: project_symbol_candidates(candidates)?,
                relationships: Vec::new(),
            }),
        }
    }
}

fn project_symbol_candidates(
    candidates: Vec<Value>,
) -> std::result::Result<Vec<AgentSymbolCandidateProjection>, String> {
    candidates
        .into_iter()
        .map(AgentSymbolEvidenceProjection::try_from)
        .map(|result| result.map(AgentSymbolCandidateProjection::from))
        .collect()
}

impl From<AgentSymbolRelationProjectionInput> for AgentRelationshipProjection {
    fn from(value: AgentSymbolRelationProjectionInput) -> Self {
        let mut items = value
            .result
            .references
            .into_iter()
            .map(|location| AgentRelationshipItemProjection {
                symbol: None,
                location: Some(location),
            })
            .collect::<Vec<_>>();
        items.extend(value.result.calls.into_iter().map(|call| {
            AgentRelationshipItemProjection {
                symbol: call.symbol.or(call.caller).or(call.callee),
                location: call.location,
            }
        }));
        Self {
            relation: value.relation,
            count: items.len(),
            items,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    mode: AgentSymbolMode,
    confidence_mode: &'static str,
    outcome: &'static str,
    ambiguous: bool,
    source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    query: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    identity: Option<AgentSymbolIdentityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    candidates: Vec<AgentSymbolCandidateProjection>,
    relationships: Vec<AgentRelationshipProjection>,
    schema_version: u32,
}

impl From<AgentSymbolProjection> for AgentSymbolCompactResult {
    fn from(value: AgentSymbolProjection) -> Self {
        Self {
            result_type: "KAST_AGENT_SYMBOL_RESULT",
            ok: true,
            mode: value.mode,
            confidence_mode: symbol_confidence_mode(value.mode),
            outcome: value.outcome,
            ambiguous: value.ambiguous,
            source: value.source,
            query: value.query,
            identity: value.identity,
            location: value.location,
            candidates: value.candidates,
            relationships: value.relationships,
            schema_version: SCHEMA_VERSION,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolSelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    mode: Option<AgentSymbolMode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    confidence_mode: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    outcome: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ambiguous: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    identity: Option<AgentSymbolIdentityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    relationships: Option<Vec<AgentRelationshipProjection>>,
    schema_version: u32,
}

impl AgentSymbolSelectedResult {
    fn from_projection(value: AgentSymbolProjection, fields: &[AgentSymbolField]) -> Self {
        let selected = |field| fields.contains(&field);
        Self {
            result_type: "KAST_AGENT_SYMBOL_SELECTION",
            ok: true,
            mode: selected(AgentSymbolField::Mode).then_some(value.mode),
            confidence_mode: selected(AgentSymbolField::Mode)
                .then_some(symbol_confidence_mode(value.mode)),
            outcome: selected(AgentSymbolField::Outcome).then_some(value.outcome),
            ambiguous: selected(AgentSymbolField::Ambiguity).then_some(value.ambiguous),
            source: selected(AgentSymbolField::Source).then_some(value.source),
            identity: selected(AgentSymbolField::Identity)
                .then_some(value.identity)
                .flatten(),
            location: selected(AgentSymbolField::Location)
                .then_some(value.location)
                .flatten(),
            relationships: selected(AgentSymbolField::Relationships)
                .then_some(value.relationships),
            schema_version: SCHEMA_VERSION,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    result_count: usize,
    candidate_count: usize,
    relationship_count: usize,
    schema_version: u32,
}

impl From<AgentSymbolProjection> for AgentSymbolCountResult {
    fn from(value: AgentSymbolProjection) -> Self {
        Self {
            result_type: "KAST_AGENT_SYMBOL_COUNT",
            ok: true,
            result_count: usize::from(value.identity.is_some()),
            candidate_count: value.candidates.len(),
            relationship_count: value
                .relationships
                .iter()
                .map(|relationship| relationship.count)
                .sum(),
            schema_version: SCHEMA_VERSION,
        }
    }
}

fn symbol_confidence_mode(mode: AgentSymbolMode) -> &'static str {
    match mode {
        AgentSymbolMode::Exact => "exact",
        AgentSymbolMode::Discovery => "ranked",
    }
}

fn project_symbol_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentSymbolField>,
) -> AgentEnvelope {
    if view.detailed() {
        return envelope;
    }
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let Some(result) = envelope.result.clone() else {
        return invalid_projection_envelope(
            envelope.method,
            "symbol result projection requires a result",
        );
    };
    let input = match serde_json::from_value::<AgentSymbolLookupProjectionInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("symbol result violated the projection contract: {error}"),
            );
        }
    };
    let projection = match AgentSymbolProjection::try_from(input) {
        Ok(projection) => projection,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("symbol result violated the projection contract: {error}"),
            );
        }
    };
    let method = envelope.method;
    match view {
        AgentResultView::Compact => result_envelope(method, AgentSymbolCompactResult::from(projection)),
        AgentResultView::Fields(fields) => result_envelope(
            method,
            AgentSymbolSelectedResult::from_projection(projection, &fields),
        ),
        AgentResultView::Count => result_envelope(method, AgentSymbolCountResult::from(projection)),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed symbol views returned before projection")
        }
    }
}

fn compact_error_envelope(envelope: AgentEnvelope) -> AgentEnvelope {
    AgentEnvelope {
        ok: false,
        method: envelope.method,
        request: None,
        response: None,
        result: None,
        raw_response: None,
        error: compact_agent_error(envelope.error),
        schema_version: SCHEMA_VERSION,
    }
}

fn compact_command_error_envelope(
    mut envelope: AgentEnvelope,
    command: &AgentStepCommandProjectionInput,
) -> AgentEnvelope {
    if let Some(error) = command.first_error() {
        envelope.error = Some(error);
    }
    compact_error_envelope(envelope)
}

fn compact_agent_error(error: Option<AgentError>) -> Option<AgentError> {
    error.map(|error| AgentError {
        code: error.code,
        message: error.message,
        details: BTreeMap::new(),
    })
}

fn projected_agent_envelope(
    method: String,
    ok: bool,
    result: impl Serialize,
    error: Option<AgentError>,
) -> AgentEnvelope {
    AgentEnvelope {
        ok,
        method,
        request: None,
        response: None,
        result: Some(serde_json::to_value(result).unwrap_or(Value::Null)),
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn invalid_projection_envelope(method: String, message: impl Into<String>) -> AgentEnvelope {
    error_envelope(
        method,
        None,
        agent_error("AGENT_RESULT_INVALID", message.into()),
    )
}

#[cfg(test)]
mod result_projection_tests {
    use super::*;

    #[test]
    fn diagnostics_count_view_retains_completeness_and_severity_counts() {
        let projected = project_diagnostics_envelope(
            command_envelope(
                "agent/diagnostics",
                vec![json!({
                    "name": "diagnostics",
                    "method": "raw/diagnostics",
                    "mutates": false,
                    "ok": true,
                    "result": {
                        "diagnostics": [{
                            "location": diagnostic_location(),
                            "severity": "ERROR",
                            "message": "Broken",
                            "code": "BROKEN"
                        }],
                        "fileStatuses": [{
                            "filePath": "/workspace/App.kt",
                            "state": "ANALYZED"
                        }],
                        "semanticOutcome": "COMPLETE",
                        "requestedFileCount": 1,
                        "analyzedFileCount": 1,
                        "skippedFileCount": 0
                    },
                    "error": null
                })],
            ),
            AgentResultView::Count,
        );
        let result = projected.result.expect("diagnostics count");

        assert_eq!(result["type"], "KAST_AGENT_DIAGNOSTICS_COUNT");
        assert_eq!(result["analysis"]["analyzedFileCount"], 1);
        assert_eq!(result["severityCounts"]["error"], 1);
        assert!(result.get("diagnostics").is_none(), "{result}");
    }

    #[test]
    fn verify_count_view_retains_check_and_capability_counts() {
        let projected = project_verify_envelope(
            command_envelope(
                "agent/verify",
                vec![
                    json!({
                        "name": "health", "method": "health", "mutates": false,
                        "ok": true, "result": {"status": "READY"}, "error": null
                    }),
                    json!({
                        "name": "runtime-status", "method": "runtime/status", "mutates": false,
                        "ok": true,
                        "result": {
                            "state": "READY", "backendName": "idea",
                            "backendVersion": "test", "workspaceRoot": "/workspace"
                        },
                        "error": null
                    }),
                    json!({
                        "name": "capabilities", "method": "capabilities", "mutates": false,
                        "ok": true,
                        "result": {
                            "readCapabilities": ["symbol/resolve", "raw/diagnostics"],
                            "mutationCapabilities": ["mutation/submit"]
                        },
                        "error": null
                    }),
                ],
            ),
            AgentResultView::Count,
        );
        let result = projected.result.expect("verify count");

        assert_eq!(result["type"], "KAST_AGENT_VERIFY_COUNT");
        assert_eq!(result["checkCount"], 3);
        assert_eq!(result["passedCount"], 3);
        assert_eq!(result["readCapabilityCount"], 2);
        assert_eq!(result["mutationCapabilityCount"], 1);
    }

    #[test]
    fn verify_failure_retains_the_failed_step_error_without_raw_steps() {
        let mut envelope = command_envelope(
            "agent/verify",
            vec![json!({
                "name": "health", "method": "health", "mutates": false,
                "ok": false, "result": null,
                "error": {"code": "BACKEND_NOT_READY", "message": "Indexing"}
            })],
        );
        envelope.ok = false;
        envelope.error = Some(agent_error("AGENT_COMMAND_FAILED", "Agent command failed."));

        let projected = project_verify_envelope(envelope, AgentResultView::Compact);

        assert!(!projected.ok);
        assert!(projected.result.is_none());
        assert_eq!(
            projected.error.expect("verify error").code,
            "BACKEND_NOT_READY"
        );
    }

    #[test]
    fn diagnostics_failure_retains_the_failed_step_error_without_raw_steps() {
        let mut envelope = command_envelope(
            "agent/diagnostics",
            vec![json!({
                "name": "diagnostics", "method": "raw/diagnostics", "mutates": false,
                "ok": false, "result": null,
                "error": {
                    "code": "SEMANTIC_ANALYSIS_INVALID",
                    "message": "Evidence was malformed"
                }
            })],
        );
        envelope.ok = false;
        envelope.error = Some(agent_error("AGENT_COMMAND_FAILED", "Agent command failed."));

        let projected = project_diagnostics_envelope(envelope, AgentResultView::Compact);

        assert!(!projected.ok);
        assert!(projected.result.is_none());
        assert_eq!(
            projected.error.expect("diagnostics error").code,
            "SEMANTIC_ANALYSIS_INVALID"
        );
    }

    #[test]
    fn mutation_selected_view_emits_only_compatible_selected_fields() {
        let projected = project_mutation_envelope(
            result_envelope(
                "mutation/status".to_string(),
                json!({
                    "operationId": "00000000-0000-0000-0000-000000000337",
                    "idempotencyKey": "issue-337",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "COMPLETED",
                        "trace": {"editApplicationState": "COMPLETED"},
                        "cancellationRequested": false,
                        "result": {
                            "type": "SCOPE_MUTATION_RESULT",
                            "response": {
                                "editCount": 1,
                                "affectedFiles": ["/workspace/App.kt"],
                                "createdFiles": [],
                                "diagnostics": {"errorCount": 0, "warningCount": 0}
                            }
                        }
                    }
                }),
            ),
            AgentResultView::Fields(vec![
                AgentMutationField::State,
                AgentMutationField::Files,
            ]),
        );
        let result = projected.result.expect("mutation selection");

        assert_eq!(result["type"], "KAST_AGENT_MUTATION_SELECTION");
        assert_eq!(result["state"]["state"], "COMPLETED");
        assert_eq!(result["files"], json!(["/workspace/App.kt"]));
        assert!(result.get("operation").is_none(), "{result}");
        assert!(result.get("edits").is_none(), "{result}");
        assert!(result.get("diagnostics").is_none(), "{result}");
    }

    #[test]
    fn mutation_failure_retains_typed_failure_evidence_without_the_raw_snapshot() {
        let projected = project_mutation_envelope(
            result_envelope(
                "mutation/status".to_string(),
                json!({
                    "operationId": "00000000-0000-0000-0000-000000000337",
                    "idempotencyKey": "issue-337-failure",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "FAILED",
                        "trace": {"editApplicationState": "NOT_STARTED"},
                        "cancellationRequested": false,
                        "failure": {
                            "type": "THROWN_FAILURE",
                            "error": {
                                "requestId": "request-337",
                                "code": "MUTATION_BACKEND_FAILED",
                                "message": "Backend unavailable",
                                "retryable": true,
                                "details": {}
                            }
                        }
                    }
                }),
            ),
            AgentResultView::Compact,
        );
        let result = projected.result.expect("mutation failure result");

        assert_eq!(result["operation"]["state"], "FAILED");
        assert_eq!(
            result["operation"]["failure"]["kind"],
            "THROWN_FAILURE"
        );
        assert_eq!(
            result["operation"]["failure"]["code"],
            "MUTATION_BACKEND_FAILED"
        );
        assert_eq!(result["operation"]["failure"]["retryable"], true);
    }

    #[test]
    fn applied_invalid_mutation_retains_edits_files_and_diagnostic_counts() {
        let projected = project_mutation_envelope(
            result_envelope(
                "mutation/status".to_string(),
                json!({
                    "operationId": "00000000-0000-0000-0000-000000000338",
                    "idempotencyKey": "issue-337-invalid",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "FAILED",
                        "trace": {"editApplicationState": "COMPLETED"},
                        "cancellationRequested": false,
                        "failure": {
                            "type": "APPLIED_INVALID_RENAME",
                            "response": {
                                "editCount": 1,
                                "affectedFiles": ["/workspace/App.kt"],
                                "applyResult": {
                                    "applied": [{
                                        "filePath": "/workspace/App.kt",
                                        "startOffset": 1,
                                        "endOffset": 4,
                                        "newText": "Renamed"
                                    }],
                                    "affectedFiles": ["/workspace/App.kt"]
                                },
                                "diagnostics": {
                                    "errorCount": 2,
                                    "warningCount": 1
                                }
                            }
                        }
                    }
                }),
            ),
            AgentResultView::Compact,
        );
        let result = projected.result.expect("applied invalid result");

        assert_eq!(result["appliedEditCount"], 1);
        assert_eq!(result["edits"][0]["filePath"], "/workspace/App.kt");
        assert_eq!(result["files"], json!(["/workspace/App.kt"]));
        assert_eq!(result["diagnostics"]["error"], 2);
        assert_eq!(result["diagnostics"]["warning"], 1);
    }

    fn command_envelope(method: &str, steps: Vec<Value>) -> AgentEnvelope {
        result_envelope(
            method.to_string(),
            json!({
                "type": "KAST_AGENT_COMMAND",
                "ok": true,
                "steps": steps,
                "issues": [],
                "schemaVersion": SCHEMA_VERSION
            }),
        )
    }

    fn diagnostic_location() -> Value {
        json!({
            "filePath": "/workspace/App.kt",
            "startOffset": 0,
            "endOffset": 1,
            "startLine": 1,
            "startColumn": 1,
            "preview": "x"
        })
    }
}
