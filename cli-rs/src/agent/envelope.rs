fn workflow_envelope(method: String, summary: AgentWorkflowSummary) -> AgentEnvelope {
    let result = serde_json::to_value(&summary).unwrap_or(Value::Null);
    let ok = summary.ok;
    let error = (!ok).then(|| {
        let mut error = agent_error("AGENT_WORKFLOW_FAILED", "Agent workflow failed.");
        error.details.insert(
            "issues".to_string(),
            result.get("issues").cloned().unwrap_or(Value::Null),
        );
        error
    });
    AgentEnvelope {
        ok,
        method,
        request: None,
        response: None,
        result: Some(result),
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn result_envelope(method: String, result: impl Serialize) -> AgentEnvelope {
    AgentEnvelope {
        ok: true,
        method,
        request: None,
        response: None,
        result: Some(serde_json::to_value(result).unwrap_or(Value::Null)),
        raw_response: None,
        error: None,
        schema_version: SCHEMA_VERSION,
    }
}

fn workflow_out_dir(workflow: &str, requested: Option<&Path>) -> Result<PathBuf> {
    if let Some(path) = requested {
        return Ok(path.to_path_buf());
    }
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    Ok(std::env::temp_dir().join(format!(
        "kast-agent-workflow-{workflow}-{}-{seconds}",
        std::process::id()
    )))
}

fn write_json_file(path: &Path, value: &impl Serialize) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    fs::write(path, bytes)?;
    Ok(())
}

fn drop_nulls(value: Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.into_iter()
                .filter_map(|(key, value)| (!value.is_null()).then_some((key, value)))
                .collect(),
        ),
        value => value,
    }
}
