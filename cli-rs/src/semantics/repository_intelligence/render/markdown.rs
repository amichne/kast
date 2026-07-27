pub(crate) fn render_markdown_report(response: &Value) -> Option<String> {
    let result = response.get("result")?;
    if result.get("canonicalResultModel") != Some(&Value::Bool(true)) {
        return None;
    }
    let mut report = String::new();
    writeln!(report, "# Kast repository intelligence").ok()?;
    writeln!(report).ok()?;
    for (label, pointer) in [
        ("Status", "/status"),
        ("Question", "/question"),
        ("Intent", "/intent"),
        ("Graph generation", "/graphGeneration"),
        ("Workspace", "/workspaceIdentity/canonicalRoot"),
    ] {
        if let Some(value) = result.pointer(pointer).and_then(markdown_scalar) {
            writeln!(report, "- {label}: `{}`", markdown_inline_code(&value)).ok()?;
        }
    }

    writeln!(report).ok()?;
    writeln!(report, "## Answer").ok()?;
    if let Some(relations) = result.get("contextRelations").and_then(Value::as_array) {
        for relation in relations {
            let source = relation
                .get("sourcePath")
                .and_then(Value::as_str)
                .unwrap_or("unknown");
            let line = relation
                .pointer("/sourceLocation/line")
                .and_then(Value::as_u64)
                .map(|line| format!(":{line}"))
                .unwrap_or_default();
            let target = relation
                .get("targetName")
                .and_then(Value::as_str)
                .unwrap_or("unknown");
            let kind = relation
                .get("kind")
                .and_then(Value::as_str)
                .unwrap_or("RELATED");
            let evidence = relation
                .get("evidenceClass")
                .and_then(Value::as_str)
                .unwrap_or("unknown");
            writeln!(report, "- `{source}{line}` {kind} `{target}` ({evidence})").ok()?;
        }
    }
    if let Some(findings) = result.get("findings").and_then(Value::as_array) {
        for finding in findings {
            let name = finding
                .get("name")
                .and_then(Value::as_str)
                .or_else(|| finding.get("type").and_then(Value::as_str))
                .unwrap_or("architecture finding");
            let summary = finding
                .get("summary")
                .and_then(Value::as_str)
                .unwrap_or_default();
            writeln!(report, "- {}: {}", markdown_inline_code(name), summary).ok()?;
        }
    }
    if result
        .get("contextRelations")
        .and_then(Value::as_array)
        .is_none_or(Vec::is_empty)
        && result
            .get("findings")
            .and_then(Value::as_array)
            .is_none_or(Vec::is_empty)
    {
        if let Some(selected) = result.get("selectedIdentity").and_then(Value::as_str) {
            writeln!(
                report,
                "- Selected compiler identity: `{}`",
                markdown_inline_code(selected)
            )
            .ok()?;
        } else {
            let status = result
                .get("status")
                .and_then(Value::as_str)
                .unwrap_or("UNKNOWN");
            writeln!(report, "- Canonical repository result: `{status}`").ok()?;
        }
    }

    let mut references = BTreeSet::new();
    collect_repository_source_references(result, &mut references);
    writeln!(report).ok()?;
    writeln!(report, "## Source references").ok()?;
    for (path, line) in references.iter().take(50) {
        if let Some(line) = line {
            writeln!(report, "- `{path}:{line}`").ok()?;
        } else {
            writeln!(report, "- `{path}`").ok()?;
        }
    }
    if references.len() > 50 {
        writeln!(
            report,
            "- {} additional references omitted by the presentation bound",
            references.len() - 50
        )
        .ok()?;
    }

    let descriptor = json!({
        "question": result.get("question"),
        "intent": result.get("intent"),
        "queryPlan": result.get("queryPlan"),
        "scope": result.get("scope"),
        "bounds": result.get("bounds"),
        "graphGeneration": result.get("graphGeneration"),
        "ordering": result.get("ordering")
    });
    writeln!(report).ok()?;
    writeln!(report, "## Reproducible query descriptor").ok()?;
    for line in serde_json::to_string_pretty(&descriptor).ok()?.lines() {
        writeln!(report, "    {line}").ok()?;
    }
    Some(report)
}

fn markdown_scalar(value: &Value) -> Option<String> {
    match value {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        _ => None,
    }
}

fn markdown_inline_code(value: &str) -> String {
    value.replace('`', "'")
}

fn collect_repository_source_references(
    value: &Value,
    references: &mut BTreeSet<(String, Option<u64>)>,
) {
    match value {
        Value::Object(object) => {
            if let Some(path) = object
                .get("sourcePath")
                .or_else(|| object.get("path"))
                .and_then(Value::as_str)
                .filter(|path| path.contains('/'))
            {
                let line = object
                    .get("sourceLocation")
                    .and_then(|location| location.get("line"))
                    .or_else(|| {
                        object
                            .get("declarationRange")
                            .and_then(|range| range.get("line"))
                    })
                    .or_else(|| object.get("line"))
                    .and_then(Value::as_u64);
                references.insert((path.to_string(), line));
            }
            for nested in object.values() {
                collect_repository_source_references(nested, references);
            }
        }
        Value::Array(values) => {
            for nested in values {
                collect_repository_source_references(nested, references);
            }
        }
        _ => {}
    }
}
