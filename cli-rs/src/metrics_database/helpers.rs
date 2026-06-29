impl Confidence {
    fn for_dead_code_visibility(&self, visibility: &str) -> Self {
        if self.semantic_basis != "K2_RESOLVED" {
            return self.clone();
        }
        let level = match visibility {
            "PUBLIC" | "INTERNAL" | "PROTECTED" => "MEDIUM",
            _ => "HIGH",
        };
        Self {
            level: level.to_string(),
            index_completeness: self.index_completeness,
            semantic_basis: self.semantic_basis.clone(),
        }
    }
}

fn nested_string_map<I>(
    rows: rusqlite::Result<I>,
) -> DirectResult<BTreeMap<String, BTreeMap<String, i64>>>
where
    I: Iterator<Item = rusqlite::Result<(String, String, i64)>>,
{
    let mut values: BTreeMap<String, BTreeMap<String, i64>> = BTreeMap::new();
    for row in rows.map_err(sql_error)? {
        let (outer, inner, count) = row.map_err(sql_error)?;
        values.entry(outer).or_default().insert(inner, count);
    }
    Ok(values)
}

fn collect_json<T, I>(rows: I) -> DirectResult<Value>
where
    T: Serialize,
    I: Iterator<Item = rusqlite::Result<T>>,
{
    let mut values = Vec::new();
    for row in rows {
        values.push(row.map_err(sql_error)?);
    }
    serde_json::to_value(values).map_err(json_direct_error)
}

fn string_column<I>(rows: rusqlite::Result<I>) -> DirectResult<Vec<String>>
where
    I: Iterator<Item = rusqlite::Result<String>>,
{
    let mut values = Vec::new();
    for row in rows.map_err(sql_error)? {
        values.push(row.map_err(sql_error)?);
    }
    Ok(values)
}

fn dead_code_reason(visibility: &str) -> &'static str {
    if visibility == "PUBLIC" {
        "Declaration has no inbound reference rows; public declarations may still be used externally."
    } else {
        "Declaration has no inbound reference rows in the K2 declaration registry."
    }
}

fn sql_error(error: rusqlite::Error) -> DirectMetricsError {
    if error.sqlite_error_code() == Some(ErrorCode::OperationInterrupted) {
        return DirectMetricsError::Query(CliError::new(
            "METRICS_QUERY_CANCELLED",
            "metrics query was cancelled before it completed",
        ));
    }
    DirectMetricsError::Query(CliError::new("SQLITE_ERROR", error.to_string()))
}

fn json_direct_error(error: serde_json::Error) -> DirectMetricsError {
    DirectMetricsError::Query(CliError::new("JSON_ERROR", error.to_string()))
}
