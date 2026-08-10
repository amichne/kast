fn refresh_relationship_failures(
    refresh_result: &Value,
    refreshed_paths: &[String],
) -> Result<Vec<Value>> {
    required_field(refresh_result, "relationshipFailures")?
        .as_array()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Workspace refresh returned non-array relationship failure evidence.",
            )
        })?
        .iter()
        .map(|failure| {
            let failure_id = required_string(failure, "failureId")?;
            let file_path = required_string(failure, "filePath")?;
            let code = required_string(failure, "code")?;
            let valid_id = uuid::Uuid::parse_str(failure_id)
                .ok()
                .is_some_and(|id| id.hyphenated().to_string() == failure_id);
            if !valid_id || code != "PSI_UNAVAILABLE" || !refreshed_paths.iter().any(|path| path == file_path) {
                return Err(CliError::new(
                    "KAST_EXTERNAL_FAILURE_EVIDENCE_INVALID",
                    "Workspace refresh returned invalid externalizable relationship failure evidence.",
                ));
            }
            Ok(json!({"path": file_path, "failureId": failure_id, "code": code}))
        })
        .collect()
}

fn required_string<'a>(value: &'a Value, field: &str) -> Result<&'a str> {
    value.get(field).and_then(Value::as_str).ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            format!("The typed operation returned no string `{field}` field."),
        )
    })
}

fn workspace_files_args(workspace_root: PathBuf) -> AgentWorkspaceFilesArgs {
    AgentWorkspaceFilesArgs {
        runtime: agent_runtime(workspace_root),
        module: None,
        source_set: None,
        kind: None,
        package_selector: None,
        dirty: None,
        drift: None,
        path_prefix: None,
        glob: None,
        limit: "200"
            .parse()
            .expect("the typed maximum workspace-file limit is valid"),
        page_token: None,
        view: Default::default(),
    }
}

#[cfg(test)]
fn semantic_status_ready(workspace_root: &Path, status: &RuntimeStatusResponse) -> bool {
    config::normalize(PathBuf::from(&status.workspace_root))
        == config::normalize(workspace_root.to_path_buf())
        && status.state == RuntimeState::Ready
        && status.healthy()
        && status.active()
        && !status.indexing()
}

fn public_file_collection(value: &Value) -> Result<Value> {
    let mut files = value.clone();
    for entry in files.as_array_mut().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "File listing returned a non-array file collection.",
        )
    })? {
        let paths = entry
            .as_object_mut()
            .ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "File listing returned a non-object file entry.",
                )
            })?
            .get_mut("paths")
            .and_then(Value::as_array_mut);
        if let Some(paths) = paths {
            for path in paths {
                public_file_record(path)?;
            }
        } else {
            public_file_record(entry)?;
        }
    }
    Ok(files)
}

fn public_file_record(value: &mut Value) -> Result<()> {
    let fields = value.as_object_mut().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "File listing returned a non-object path entry.",
        )
    })?;
    fields.remove("filePath");
    let relative = fields
        .remove("relativePath")
        .and_then(|value| value.as_str().map(str::to_string))
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "File listing returned no workspace-relative path.",
            )
        })?;
    let path = agent::public_protocol::WorkspaceKotlinPath::from_normalized(relative)
        .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message))?;
    fields.insert("path".to_string(), Value::String(path.as_str().to_string()));
    Ok(())
}

fn public_file_hashes(workspace_root: &Path, value: &Value) -> Result<Value> {
    let mut hashes = value.clone();
    for hash in hashes.as_array_mut().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Diagnostic check returned invalid file hash evidence.",
        )
    })? {
        replace_public_path(workspace_root, hash, "filePath", "path")?;
    }
    Ok(hashes)
}

fn public_diagnostics(workspace_root: &Path, value: &Value) -> Result<Value> {
    let mut diagnostics = value.clone();
    for diagnostic in diagnostics.as_array_mut().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Diagnostic check returned an invalid diagnostic collection.",
        )
    })? {
        let location = diagnostic.get_mut("location").ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Diagnostic check returned no source location.",
            )
        })?;
        replace_public_path(workspace_root, location, "filePath", "path")?;
    }
    Ok(diagnostics)
}

fn replace_public_path(
    workspace_root: &Path,
    value: &mut Value,
    source_field: &str,
    target_field: &str,
) -> Result<()> {
    let fields = value.as_object_mut().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Public source evidence returned a non-object path container.",
        )
    })?;
    let path = fields
        .remove(source_field)
        .and_then(|value| value.as_str().map(str::to_string))
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Public source evidence returned no source path.",
            )
        })?;
    fields.insert(
        target_field.to_string(),
        Value::String(public_source_path(workspace_root, &path)?),
    );
    Ok(())
}

fn public_source_path(workspace_root: &Path, value: &str) -> Result<String> {
    let candidate = Path::new(value);
    let relative = if candidate.is_absolute() {
        candidate.strip_prefix(workspace_root).map_err(|_| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Public source evidence named a path outside the workspace.",
            )
        })?
    } else {
        candidate
    };
    let normalized = relative
        .components()
        .map(|component| component.as_os_str().to_str())
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Public source evidence named a non-UTF-8 path.",
            )
        })?
        .join("/");
    agent::public_protocol::WorkspaceKotlinPath::from_normalized(normalized)
        .map(|path| path.as_str().to_string())
        .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message))
}
