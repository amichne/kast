
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
