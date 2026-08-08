fn print_refresh_noop(workspace_root: &Path, output_format: OutputFormat) -> Result<i32> {
    let admission =
        match crate::repository_intelligence::semantic_graph_read_admission(workspace_root) {
            Ok(admission) => admission,
            Err(error) => {
                return print_actionable_failure(
                    agent::public_protocol::OperationId::WorkspaceRefresh,
                    "GRAPH_EVIDENCE_UNAVAILABLE",
                    &error.message,
                    "kast workspace refresh",
                    output_format,
                );
            }
        };
    if admission.is_rejected() {
        return print_actionable_failure(
            agent::public_protocol::OperationId::WorkspaceRefresh,
            "GRAPH_EVIDENCE_INCOMPLETE",
            "Persisted semantic graph evidence is incomplete.",
            "kast workspace refresh",
            output_format,
        );
    }
    print_public_value(
        agent::public_protocol::OperationId::WorkspaceRefresh,
        agent::public_protocol::OperationStatus::Complete,
        &json!({
            "fileCount": 0,
            "qualification": admission
                .qualification()
                .expect("non-rejected graph evidence has a qualification"),
            "coverage": admission.coverage(),
            "message": "Semantic graph evidence is current.",
        }),
        output_format,
    )
}

fn changed_kotlin_files(workspace_root: &Path) -> Result<std::result::Result<Vec<String>, Value>> {
    let mut args = workspace_files_args(workspace_root.to_path_buf());
    args.dirty = Some(WorkspaceDirtyFilter::Dirty);
    args.view = AgentWorkspaceFilesViewArgs {
        fields: vec![AgentWorkspaceFilesField::Path],
        ..Default::default()
    };
    let envelope = projected_value(AgentCommand::WorkspaceFiles(args))?;
    if envelope.get("ok") != Some(&Value::Bool(true)) {
        return Ok(Err(envelope));
    }
    let result = envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Changed-file discovery completed without a result.",
        )
    })?;
    let coverage_complete = result.get("coverage").is_some_and(|coverage| {
        coverage.get("candidateInventory").and_then(Value::as_str) == Some("COMPLETE")
            && coverage.get("filterEvidence").and_then(Value::as_str) == Some("COMPLETE")
    });
    if !coverage_complete {
        return Err(CliError::new(
            "CHANGED_FILE_EVIDENCE_INCOMPLETE",
            "Kast could not prove the complete changed Kotlin file set. Pass explicit paths to `kast diagnostic check --file <PATH>`.",
        ));
    }
    let truncated = result
        .get("truncated")
        .and_then(Value::as_bool)
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Changed-file discovery returned no truncation evidence.",
            )
        })?;
    if truncated {
        return Err(CliError::new(
            "CHANGED_FILE_SET_TOO_LARGE",
            "More than 200 changed Kotlin files were found. Run `kast diagnostic check --file <PATH>...` with explicit batches.",
        ));
    }
    let files = result.get("files").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Changed-file discovery returned no file collection.",
        )
    })?;
    let mut file_paths = Vec::new();
    collect_string_fields(files, "filePath", &mut file_paths);
    file_paths.sort();
    file_paths.dedup();
    Ok(Ok(file_paths))
}

fn collect_string_fields(value: &Value, key: &str, values: &mut Vec<String>) {
    match value {
        Value::Object(fields) => {
            if let Some(value) = fields.get(key).and_then(Value::as_str) {
                values.push(value.to_string());
            }
            for value in fields.values() {
                collect_string_fields(value, key, values);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_string_fields(item, key, values);
            }
        }
        _ => {}
    }
}
