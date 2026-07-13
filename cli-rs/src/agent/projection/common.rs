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
