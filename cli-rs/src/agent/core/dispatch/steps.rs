struct AgentPublicStep {
    name: &'static str,
    method: &'static str,
    params: Value,
    mutates: bool,
}

impl AgentPublicStep {
    fn new(name: &'static str, method: &'static str, params: Value, mutates: bool) -> Self {
        Self {
            name,
            method,
            params,
            mutates,
        }
    }
}

fn execute_agent_steps(
    method: &'static str,
    mut runtime: AgentRuntimeArgs,
    steps: Vec<AgentPublicStep>,
) -> AgentEnvelope {
    let daemon_step_count = steps
        .iter()
        .filter(|step| agent_step_uses_daemon(step.method))
        .count();
    let mut workspace_admission = None;
    if daemon_step_count > 0 {
        let route = if method == "agent/verify" {
            runtime::semantic_workspace_route_reuse_only(runtime.workspace_root.clone())
        } else {
            runtime::semantic_workspace_route(runtime.workspace_root.clone())
        };
        match route {
            Ok(runtime::SemanticWorkspaceRoute::Admitted(admission)) => {
                runtime.workspace_root = Some(admission.workspace_root().to_path_buf());
                workspace_admission = Some(admission);
            }
            Ok(runtime::SemanticWorkspaceRoute::Rejected(rejection)) => {
                let mut error = agent_error(rejection.code, rejection.message);
                error
                    .details
                    .insert("semanticWorkspace".to_string(), json!(rejection.evidence));
                return error_envelope(method.to_string(), None, error);
            }
            Err(error) => {
                return error_envelope(method.to_string(), None, AgentError::from_cli_error(error));
            }
        }
    }
    let session = workspace_admission
        .as_ref()
        .map(|admission| runtime::raw_rpc_session_for_admission(admission.as_ref().clone()));
    let mut step_results = Vec::with_capacity(steps.len());
    let mut issues = Vec::new();
    let mut semantic_analysis = None;
    for step in steps {
        let step_session = session
            .as_ref()
            .filter(|_| agent_step_uses_daemon(step.method));
        let envelope = execute_request_with_session(
            AgentRequest {
                method: step.method.to_string(),
                request: json_rpc_request(step.method, step.params),
                runtime: runtime.clone(),
                full_response: step.method == "raw/diagnostics",
                operation: AgentOperation::ReadOnly,
            },
            step_session,
        );
        if matches!(step.method, "raw/workspace-refresh" | "raw/diagnostics") {
            let evidence_is_invalid = envelope
                .error
                .as_ref()
                .is_some_and(|error| error.code == "SEMANTIC_ANALYSIS_INVALID");
            semantic_analysis = (!evidence_is_invalid)
                .then_some(envelope.result.as_ref())
                .flatten()
                .and_then(AgentSemanticAnalysisSummary::from_result);
        }
        if !envelope.ok {
            issues.push(json!({
                "code": "AGENT_STEP_FAILED",
                "step": step.name,
                "method": step.method,
            }));
        }
        step_results.push(json!({
            "name": step.name,
            "method": step.method,
            "mutates": step.mutates,
            "ok": envelope.ok,
            "result": envelope.result,
            "error": envelope.error,
        }));
        if !issues.is_empty() {
            break;
        }
    }
    let semantic_workspace = workspace_admission
        .as_ref()
        .and_then(|admission| verification_workspace_evidence(method, admission, &step_results));
    if method == "agent/verify" && semantic_workspace.is_none() && issues.is_empty() {
        issues.push(json!({
            "code": "SEMANTIC_WORKSPACE_EVIDENCE_INVALID",
            "step": "runtime-status",
            "method": "runtime/status",
        }));
    }
    let semantic_graph = if issues.is_empty() {
        workspace_admission.as_ref().and_then(|admission| {
            verification_semantic_graph_readiness(method, admission, &step_results)
        })
    } else {
        None
    };
    let semantic_graph_failure = semantic_graph
        .as_ref()
        .and_then(semantic_graph_verification_failure);
    if let (Some((code, _)), Some(readiness)) =
        (semantic_graph_failure, semantic_graph.as_ref())
    {
        issues.push(json!({
            "code": code,
            "state": readiness.state,
        }));
    }
    let ok = issues.is_empty();
    let mut result = json!({
        "type": "KAST_AGENT_COMMAND",
        "ok": ok,
        "steps": step_results,
        "issues": issues,
        "schemaVersion": SCHEMA_VERSION,
    });
    if let (Some(summary), Some(result)) = (semantic_analysis, result.as_object_mut()) {
        result.insert("semanticAnalysis".to_string(), json!(summary));
    }
    if let (Some(semantic_workspace), Some(result)) = (semantic_workspace, result.as_object_mut()) {
        result.insert("semanticWorkspace".to_string(), json!(semantic_workspace));
    }
    if let (Some(semantic_graph), Some(result)) = (semantic_graph, result.as_object_mut()) {
        result.insert("semanticGraph".to_string(), json!(semantic_graph));
    }
    let error = semantic_graph_failure.map_or_else(
        || {
            (!ok).then(|| {
                let mut error = agent_error("AGENT_COMMAND_FAILED", "Agent command failed.");
                error
                    .details
                    .insert("issues".to_string(), result["issues"].clone());
                error
            })
        },
        |(code, message)| {
            let mut error = agent_error(code, message);
            error
                .details
                .insert("issues".to_string(), result["issues"].clone());
            error
                .details
                .insert("semanticGraph".to_string(), result["semanticGraph"].clone());
            Some(error)
        },
    );
    AgentEnvelope {
        ok,
        method: method.to_string(),
        request: None,
        response: None,
        result: Some(result),
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn semantic_graph_verification_failure(
    readiness: &crate::repository_intelligence::SemanticGraphReadiness,
) -> Option<(&'static str, &'static str)> {
    use crate::repository_intelligence::SemanticGraphReadinessState;

    match readiness.state {
        SemanticGraphReadinessState::Ready => None,
        SemanticGraphReadinessState::Incomplete => Some((
            "SEMANTIC_GRAPH_COVERAGE_INCOMPLETE",
            "Persisted semantic graph coverage is incomplete; refresh each affected file with `kast agent graph --operation refresh --file-path <path-to-kotlin-file>`, then retry verification.",
        )),
        SemanticGraphReadinessState::Unavailable => Some((
            "SEMANTIC_GRAPH_COVERAGE_UNAVAILABLE",
            "Persisted semantic graph coverage is unavailable; refresh a selected file with `kast agent graph --operation refresh --file-path <path-to-kotlin-file>`, then retry verification.",
        )),
    }
}

fn verification_semantic_graph_readiness(
    method: &str,
    admission: &runtime::SemanticWorkspaceAdmission,
    step_results: &[Value],
) -> Option<crate::repository_intelligence::SemanticGraphReadiness> {
    if method != "agent/verify" {
        return None;
    }
    let capabilities = step_results
        .iter()
        .find(|step| step.get("name").and_then(Value::as_str) == Some("capabilities"))?
        .get("result")?;
    let advertised = capabilities
        .get("readCapabilities")
        .and_then(Value::as_array)
        .is_some_and(|capabilities| {
            capabilities
                .iter()
                .any(|capability| capability.as_str() == Some("SEMANTIC_GRAPH"))
        });
    advertised.then(|| {
        crate::repository_intelligence::semantic_graph_readiness(admission.workspace_root())
    })
}

fn verification_workspace_evidence(
    method: &str,
    admission: &runtime::SemanticWorkspaceAdmission,
    step_results: &[Value],
) -> Option<runtime::SemanticWorkspaceEvidence> {
    if method != "agent/verify" {
        return None;
    }
    let status = step_results
        .iter()
        .find(|step| step.get("name").and_then(Value::as_str) == Some("runtime-status"))?
        .get("result")?;
    let status: runtime::RuntimeStatusResponse = serde_json::from_value(status.clone()).ok()?;
    runtime::compiler_backed_workspace_evidence(admission, &status)
}

fn agent_step_uses_daemon(method: &str) -> bool {
    !matches!(method, "database/metrics" | "symbol/query")
}
