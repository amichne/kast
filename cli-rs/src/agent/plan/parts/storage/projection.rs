fn projected_result(envelope: &Value) -> Result<&Value> {
    envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The validated change completed without a result.",
        )
    })
}

fn public_plan(preview: &Value) -> Value {
    let mut plan = preview.get("plan").cloned().unwrap_or_else(|| json!({}));
    if let Some(fields) = plan.as_object_mut() {
        for key in [
            "contentFile",
            "help",
            "method",
            "mutates",
            "ok",
            "schemaVersion",
            "applyRequired",
            "type",
        ] {
            fields.remove(key);
        }
    }
    redact_exact_image_bytes(plan)
}

fn redact_exact_image_bytes(value: Value) -> Value {
    match value {
        Value::Object(fields) => Value::Object(
            fields
                .into_iter()
                .filter_map(|(key, value)| {
                    (key != "contentBase64").then(|| (key, redact_exact_image_bytes(value)))
                })
                .collect(),
        ),
        Value::Array(items) => Value::Array(
            items
                .into_iter()
                .map(redact_exact_image_bytes)
                .collect(),
        ),
        scalar => scalar,
    }
}
