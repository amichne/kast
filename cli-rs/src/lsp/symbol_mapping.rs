fn symbol_name(symbol: &Value) -> String {
    let fq_name = symbol
        .get("fqName")
        .and_then(Value::as_str)
        .unwrap_or("symbol");
    fq_name
        .rsplit('.')
        .next()
        .filter(|name| !name.is_empty())
        .unwrap_or(fq_name)
        .to_string()
}

fn symbol_detail(symbol: &Value) -> String {
    let fq_name = symbol.get("fqName").and_then(Value::as_str).unwrap_or("");
    let kind = symbol
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("SYMBOL");
    let type_label = symbol
        .get("returnType")
        .or_else(|| symbol.get("type"))
        .and_then(Value::as_str);
    match type_label {
        Some(label) if !label.is_empty() => format!("{kind} {fq_name}: {label}"),
        _ => format!("{kind} {fq_name}"),
    }
}

fn hover_markdown(symbol: &Value) -> String {
    let mut lines = vec![format!("```kotlin\n{}\n```", symbol_detail(symbol))];
    if let Some(documentation) = symbol.get("documentation").and_then(Value::as_str)
        && !documentation.trim().is_empty()
    {
        lines.push(documentation.trim().to_string());
    }
    lines.join("\n\n")
}

fn symbol_kind_value(symbol: &Value) -> u64 {
    match symbol
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("UNKNOWN")
    {
        "CLASS" => 5,
        "INTERFACE" => 11,
        "OBJECT" => 19,
        "FUNCTION" => 12,
        "PROPERTY" => 7,
        "PARAMETER" => 13,
        _ => 13,
    }
}

fn symbol_data(symbol: &Value) -> LspResult<Value> {
    let location = symbol
        .get("location")
        .ok_or_else(|| LspError::backend_contract("symbol missing location"))?;
    Ok(json!({
        "filePath": string_field(location, "filePath")?,
        "offset": usize_field(location, "startOffset")?,
        "fqName": symbol.get("fqName").cloned().unwrap_or(Value::Null),
        "kind": symbol.get("kind").cloned().unwrap_or(Value::Null)
    }))
}
