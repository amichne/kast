fn verify_package_checks(transaction: &Transaction<'_>) -> Result<(), ReadDatabaseError> {
    let normalized = normalized_table_sql(transaction, "file_metadata")?;
    let required_tokens = [
        "PROVEN_ROOT",
        "PROVEN_NAMED",
        "UNPROVEN",
        "NOT_SCANNED",
        "SEMANTIC_ANALYSIS_UNAVAILABLE",
        "SEMANTIC_ANALYSIS_FAILED",
        "LEGACY_TEXT_ONLY",
        "PACKAGE_STATE='PROVEN_ROOT'",
        "PACKAGE_STATE='PROVEN_NAMED'",
        "PACKAGE_STATE='UNPROVEN'",
        "PACKAGE_FQ_IDISNULL",
        "PACKAGE_FQ_IDISNOTNULL",
        "PACKAGE_UNPROVEN_REASONISNULL",
    ];
    if required_tokens
        .iter()
        .any(|token| !normalized.contains(token))
    {
        return Err(ReadDatabaseError::Incompatible(
            "file_metadata package evidence CHECK contract is incomplete".to_string(),
        ));
    }
    Ok(())
}

fn verify_progress_checks(transaction: &Transaction<'_>) -> Result<(), ReadDatabaseError> {
    let normalized = normalized_table_sql(transaction, "module_index_progress")?;
    if !normalized.contains(
        "RELATIONSHIP_INDEX_STATUSIN('PENDING','INDEXING','COMPLETE','DEGRADED','FAILED')",
    ) {
        return Err(ReadDatabaseError::Incompatible(
            "module_index_progress status CHECK contract is incomplete".to_string(),
        ));
    }
    Ok(())
}

fn verify_file_stage_outcome_checks(
    transaction: &Transaction<'_>,
) -> Result<(), ReadDatabaseError> {
    let normalized = normalized_table_sql(transaction, "file_stage_outcomes")?;
    let required_tokens = [
        "STAGEIN('SOURCE','RELATIONSHIPS','SEMANTIC_GRAPH')",
        "OUTCOME_STATUSIN('COMPLETE','LIMITED','FAILED','EXTERNAL_BOUNDARY')",
        "FAILURE_CODEIN('PSI_UNAVAILABLE')",
        "OUTCOME_STATUSIN('COMPLETE','LIMITED')",
        "FAILURE_IDISNULL",
        "OUTCOME_STATUSIN('FAILED','EXTERNAL_BOUNDARY')",
        "FAILURE_IDISNOTNULL",
    ];
    if required_tokens
        .iter()
        .any(|token| !normalized.contains(token))
    {
        return Err(ReadDatabaseError::Incompatible(
            "file_stage_outcomes enum CHECK contract is incomplete".to_string(),
        ));
    }
    Ok(())
}

fn normalized_table_sql(
    transaction: &Transaction<'_>,
    table: &str,
) -> Result<String, ReadDatabaseError> {
    let sql = transaction
        .query_row(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            [table],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(incompatible_sql)?
        .ok_or_else(|| ReadDatabaseError::Incompatible(format!("{table} DDL is unavailable")))?;
    Ok(sql
        .chars()
        .filter(|character| !character.is_whitespace())
        .flat_map(char::to_uppercase)
        .collect())
}
