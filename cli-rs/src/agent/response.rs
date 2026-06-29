fn response_envelope(method: String, request: Value, raw_response: String) -> AgentEnvelope {
    let response = match serde_json::from_str::<Value>(&raw_response) {
        Ok(response) => response,
        Err(error) => {
            let mut agent_error = AgentError::from_cli_error(CliError::from(error));
            agent_error
                .details
                .insert("rawResponse".to_string(), json!(raw_response));
            return AgentEnvelope {
                ok: false,
                method,
                request: Some(request),
                response: None,
                result: None,
                raw_response: None,
                error: Some(agent_error),
                schema_version: SCHEMA_VERSION,
            };
        }
    };
    let result = response.get("result").cloned();
    let error = response_error(&response).or_else(|| result_failure(&result));
    AgentEnvelope {
        ok: error.is_none() && result.is_some(),
        method,
        request: Some(request),
        response: Some(response),
        result,
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn response_error(response: &Value) -> Option<AgentError> {
    let error = response.get("error")?;
    let code_value = error
        .get("data")
        .and_then(|data| data.get("code"))
        .or_else(|| error.get("code"));
    let message_value = error
        .get("data")
        .and_then(|data| data.get("message"))
        .or_else(|| error.get("message"));
    let code = code_value
        .and_then(Value::as_str)
        .map(str::to_string)
        .unwrap_or_else(|| {
            code_value
                .map(Value::to_string)
                .unwrap_or_else(|| "RPC_ERROR".to_string())
        });
    let message = message_value
        .and_then(Value::as_str)
        .map(str::to_string)
        .unwrap_or_else(|| "JSON-RPC request failed.".to_string());
    let mut agent_error = AgentError {
        code,
        message,
        details: BTreeMap::new(),
    };
    agent_error
        .details
        .insert("rpcError".to_string(), error.clone());
    Some(agent_error)
}

fn result_failure(result: &Option<Value>) -> Option<AgentError> {
    let result = result.as_ref()?;
    if result.get("ok").and_then(Value::as_bool) != Some(false) {
        return None;
    }
    let code = result
        .get("code")
        .or_else(|| result.get("type"))
        .and_then(Value::as_str)
        .unwrap_or("KAST_RESULT_NOT_OK")
        .to_string();
    let message = result
        .get("message")
        .and_then(Value::as_str)
        .unwrap_or("Kast result reported ok=false.")
        .to_string();
    let mut agent_error = AgentError {
        code,
        message,
        details: BTreeMap::new(),
    };
    agent_error
        .details
        .insert("result".to_string(), result.clone());
    Some(agent_error)
}

fn error_envelope(method: String, request: Option<Value>, error: AgentError) -> AgentEnvelope {
    AgentEnvelope {
        ok: false,
        method,
        request,
        response: None,
        result: None,
        raw_response: None,
        error: Some(error),
        schema_version: SCHEMA_VERSION,
    }
}

fn agent_error(code: &str, message: impl Into<String>) -> AgentError {
    AgentError {
        code: code.to_string(),
        message: message.into(),
        details: BTreeMap::new(),
    }
}

impl AgentError {
    fn from_cli_error(error: CliError) -> Self {
        Self {
            code: error.code.to_string(),
            message: error.message,
            details: error
                .details
                .into_iter()
                .map(|(key, value)| (key, json!(value)))
                .collect(),
        }
    }
}
