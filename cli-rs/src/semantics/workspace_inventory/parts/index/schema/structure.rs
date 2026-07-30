fn verify_required_structure(transaction: &Transaction<'_>) -> Result<(), ReadDatabaseError> {
    for (table, required_columns) in REQUIRED_TABLE_COLUMNS {
        let mut statement = transaction
            .prepare(&format!("PRAGMA table_info({table})"))
            .map_err(incompatible_sql)?;
        let columns = statement
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(incompatible_sql)?
            .collect::<rusqlite::Result<BTreeSet<_>>>()
            .map_err(incompatible_sql)?;
        if columns.is_empty() {
            return Err(ReadDatabaseError::Incompatible(format!(
                "required source-index table `{table}` is missing"
            )));
        }
        let missing: Vec<_> = required_columns
            .iter()
            .filter(|column| !columns.contains(**column))
            .copied()
            .collect();
        if !missing.is_empty() {
            return Err(ReadDatabaseError::Incompatible(format!(
                "required source-index table `{table}` is missing columns: {}",
                missing.join(", ")
            )));
        }
    }
    verify_primary_key(transaction, "file_metadata", &["prefix_id", "filename"])?;
    verify_primary_key(transaction, "path_prefixes", &["prefix_id"])?;
    verify_primary_key(transaction, "fq_names", &["fq_id"])?;
    verify_primary_key(transaction, "file_manifest", &["prefix_id", "filename"])?;
    verify_primary_key(
        transaction,
        "file_stage_outcomes",
        &["prefix_id", "filename", "stage"],
    )?;
    verify_primary_key(transaction, "module_index_progress", &["module_name"])?;
    verify_primary_key(
        transaction,
        "file_gradle_projects",
        &["prefix_id", "filename", "build_root", "project_path"],
    )?;
    verify_primary_key(
        transaction,
        "file_gradle_source_sets",
        &[
            "prefix_id",
            "filename",
            "build_root",
            "project_path",
            "source_set_name",
        ],
    )?;
    verify_not_null(transaction, "schema_version", &["version", "generation"])?;
    verify_not_null(transaction, "path_prefixes", &["dir_path"])?;
    verify_not_null(transaction, "fq_names", &["fq_name"])?;
    verify_not_null(
        transaction,
        "file_manifest",
        &["prefix_id", "filename", "last_modified_millis"],
    )?;
    verify_not_null(
        transaction,
        "file_stage_outcomes",
        &[
            "prefix_id",
            "filename",
            "stage",
            "content_hash",
            "stage_version",
            "outcome_status",
            "limitations_json",
        ],
    )?;
    verify_nullable(
        transaction,
        "file_stage_outcomes",
        &[
            "stage_input_fingerprint",
            "failure_id",
            "failure_code",
            "failure_message",
        ],
    )?;
    verify_not_null(
        transaction,
        "module_index_progress",
        &[
            "relationship_index_status",
            "indexed_file_count",
            "total_file_count",
        ],
    )?;
    verify_not_null(transaction, "pending_updates", &["applied"])?;
    verify_not_null(
        transaction,
        "file_metadata",
        &["prefix_id", "filename", "package_state"],
    )?;
    verify_not_null(
        transaction,
        "file_gradle_projects",
        &["prefix_id", "filename", "build_root", "project_path"],
    )?;
    verify_not_null(
        transaction,
        "file_gradle_source_sets",
        &[
            "prefix_id",
            "filename",
            "build_root",
            "project_path",
            "source_set_name",
        ],
    )?;
    verify_foreign_key(
        transaction,
        "file_metadata",
        "fq_names",
        &[("package_fq_id", "fq_id")],
        "NO ACTION",
    )?;
    verify_foreign_key(
        transaction,
        "file_gradle_projects",
        "file_metadata",
        &[("prefix_id", "prefix_id"), ("filename", "filename")],
        "CASCADE",
    )?;
    verify_foreign_key(
        transaction,
        "file_gradle_source_sets",
        "file_gradle_projects",
        &[
            ("prefix_id", "prefix_id"),
            ("filename", "filename"),
            ("build_root", "build_root"),
            ("project_path", "project_path"),
        ],
        "CASCADE",
    )?;
    verify_unique_key(transaction, "path_prefixes", &["dir_path"])?;
    verify_unique_key(transaction, "fq_names", &["fq_name"])?;
    verify_package_checks(transaction)?;
    verify_progress_checks(transaction)?;
    verify_file_stage_outcome_checks(transaction)?;
    Ok(())
}
