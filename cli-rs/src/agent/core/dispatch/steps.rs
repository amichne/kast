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
        match runtime::semantic_workspace_route(
            runtime.workspace_root.clone(),
            runtime.backend_name,
        ) {
            Ok(runtime::SemanticWorkspaceRoute::Admitted(admission)) => {
                runtime.workspace_root = Some(admission.workspace_root.clone());
                runtime.backend_name = Some(admission.backend_name);
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
    let session = if daemon_step_count > 1 {
        let session = if method == "agent/verify" {
            runtime::raw_rpc_session_reuse_only(
                runtime.workspace_root.clone(),
                runtime.backend_name,
            )
        } else {
            runtime::raw_rpc_session(runtime.workspace_root.clone(), runtime.backend_name)
        };
        match session {
            Ok(session) => Some(session),
            Err(error) => {
                return error_envelope(method.to_string(), None, AgentError::from_cli_error(error));
            }
        }
    } else {
        None
    };
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
    let error = (!ok).then(|| {
        let mut error = agent_error("AGENT_COMMAND_FAILED", "Agent command failed.");
        error
            .details
            .insert("issues".to_string(), result["issues"].clone());
        error
    });
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
