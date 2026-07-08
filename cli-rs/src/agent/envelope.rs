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
