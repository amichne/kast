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
    public_read_count: usize,
    read: Vec<String>,
    mutation: Vec<String>,
    public_read: Vec<AgentPublicCapabilityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    limits: Option<AgentServerLimitsProjection>,
}

impl From<AgentCapabilitiesProjectionInput> for AgentCapabilitiesProjection {
    fn from(value: AgentCapabilitiesProjectionInput) -> Self {
        let public_read = public_read_capabilities(&value.read_capabilities);
        Self {
            read_count: value.read_capabilities.len(),
            mutation_count: value.mutation_capabilities.len(),
            public_read_count: public_read.len(),
            read: value.read_capabilities,
            mutation: value.mutation_capabilities,
            public_read,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    semantic_workspace: Option<AgentSemanticWorkspaceProjection>,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    semantic_workspace: Option<AgentSemanticWorkspaceProjection>,
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
    public_read_capability_count: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    semantic_workspace: Option<AgentSemanticWorkspaceProjection>,
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
    if !envelope.ok {
        return compact_command_error_envelope(envelope, &command);
    }
    let Some(health_step) = command.step("health") else {
        return invalid_projection_envelope(envelope.method, "verification result omitted health");
    };
    let Some(runtime_step) = command.step("runtime/status") else {
        return invalid_projection_envelope(
            envelope.method,
            "verification result omitted runtime status",
        );
    };
    let Some(capabilities_step) = command.step("capabilities") else {
        return invalid_projection_envelope(
            envelope.method,
            "verification result omitted capabilities",
        );
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
    let semantic_workspace = command.semantic_workspace;
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
                semantic_workspace,
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
                    semantic_workspace,
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
                public_read_capability_count: capabilities.public_read_count,
                semantic_workspace,
                schema_version: SCHEMA_VERSION,
            },
            error,
        ),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed verification views returned before projection")
        }
    }
}
