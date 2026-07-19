const AGENT_COMPACT_DIAGNOSTIC_MESSAGE_CHARS: usize = 256;
const AGENT_COMPACT_DIAGNOSTIC_PREVIEW_CHARS: usize = 160;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCompactDiagnosticLocation {
    file_path: String,
    start_offset: usize,
    end_offset: usize,
    start_line: usize,
    start_column: usize,
    preview: String,
    preview_truncated: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCompactDiagnostic {
    location: AgentCompactDiagnosticLocation,
    severity: AgentDiagnosticSeverity,
    message: String,
    message_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    code: Option<String>,
}

impl From<AgentDiagnostic> for AgentCompactDiagnostic {
    fn from(diagnostic: AgentDiagnostic) -> Self {
        let (message, message_truncated) = truncate_diagnostic_text(
            diagnostic.message,
            std::num::NonZeroUsize::new(AGENT_COMPACT_DIAGNOSTIC_MESSAGE_CHARS)
                .expect("diagnostic message limit must be nonzero"),
        );
        let (preview, preview_truncated) = truncate_diagnostic_text(
            diagnostic.location.preview,
            std::num::NonZeroUsize::new(AGENT_COMPACT_DIAGNOSTIC_PREVIEW_CHARS)
                .expect("diagnostic preview limit must be nonzero"),
        );
        Self {
            location: AgentCompactDiagnosticLocation {
                file_path: diagnostic.location.file_path,
                start_offset: diagnostic.location.start_offset,
                end_offset: diagnostic.location.end_offset,
                start_line: diagnostic.location.start_line,
                start_column: diagnostic.location.start_column,
                preview,
                preview_truncated,
            },
            severity: diagnostic.severity,
            message,
            message_truncated,
            code: diagnostic.code,
        }
    }
}

fn truncate_diagnostic_text(
    value: String,
    max_chars: std::num::NonZeroUsize,
) -> (String, bool) {
    let max_chars = max_chars.get();
    if value.chars().count() <= max_chars {
        return (value, false);
    }
    let mut truncated = value.chars().take(max_chars - 1).collect::<String>();
    truncated.push('…');
    (truncated, true)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsCardinalityProjection {
    #[serde(flatten)]
    cardinality: AgentExactCardinality,
    returned_count: usize,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    file_paths: Vec<String>,
    file_hashes: Vec<AgentDiagnosticsFileHash>,
    analysis: AgentSemanticAnalysisSummary,
    severity_counts: AgentDiagnosticSeverityCounts,
    cardinality: AgentDiagnosticsCardinalityProjection,
    diagnostics: Vec<AgentCompactDiagnostic>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsSelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    file_paths: Vec<String>,
    file_hashes: Vec<AgentDiagnosticsFileHash>,
    #[serde(skip_serializing_if = "Option::is_none")]
    analysis: Option<AgentSemanticAnalysisSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    severity_counts: Option<AgentDiagnosticSeverityCounts>,
    cardinality: AgentDiagnosticsCardinalityProjection,
    #[serde(skip_serializing_if = "Option::is_none")]
    diagnostics: Option<Vec<AgentCompactDiagnostic>>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticsCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    file_paths: Vec<String>,
    file_hashes: Vec<AgentDiagnosticsFileHash>,
    analysis: AgentSemanticAnalysisSummary,
    severity_counts: AgentDiagnosticSeverityCounts,
    cardinality: AgentDiagnosticsCardinalityProjection,
    schema_version: u32,
}

fn project_diagnostics_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentDiagnosticsField>,
    result_limit: usize,
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
    if !envelope.ok {
        return compact_command_error_envelope(envelope, &command);
    }
    let Some(step) = command.step("raw/diagnostics") else {
        return invalid_projection_envelope(
            envelope.method,
            "diagnostics result did not contain the diagnostics step",
        );
    };
    let file_paths = command.file_paths.clone();
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
    if !diagnostics.has_valid_file_hashes() {
        return invalid_projection_envelope(
            envelope.method,
            "diagnostics evidence violated the validated semantic contract",
        );
    }
    let analysis = diagnostics.summary();
    let file_hashes = diagnostics.file_hashes;
    let severity_counts = diagnostics.severity_counts;
    let cardinality = diagnostics.cardinality;
    let page = diagnostics.page;
    let compact_diagnostics = diagnostics
        .diagnostics
        .into_iter()
        .take(result_limit)
        .map(AgentCompactDiagnostic::from)
        .collect::<Vec<_>>();
    let returned_count = compact_diagnostics.len();
    let cardinality = AgentDiagnosticsCardinalityProjection {
        cardinality,
        returned_count,
        truncated: page.as_ref().is_some_and(|page| page.truncated)
            || cardinality.total_count() > returned_count,
        next_page_token: page.and_then(|page| page.next_page_token),
    };
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
                file_paths,
                file_hashes,
                analysis,
                severity_counts,
                cardinality,
                diagnostics: compact_diagnostics,
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
                    file_paths,
                    file_hashes,
                    analysis: selected(AgentDiagnosticsField::Analysis).then_some(analysis),
                    severity_counts: selected(AgentDiagnosticsField::SeverityCounts)
                        .then_some(severity_counts),
                    cardinality,
                    diagnostics: selected(AgentDiagnosticsField::Diagnostics)
                        .then_some(compact_diagnostics),
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
                file_paths,
                file_hashes,
                analysis,
                severity_counts,
                cardinality,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed diagnostics views returned before projection")
        }
    }
}
