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

fn maximum_relation_limit() -> AgentRelationLimit {
    "200"
        .parse()
        .expect("the typed maximum relationship limit is valid")
}

fn maximum_relation_depth() -> AgentRelationDepth {
    "8".parse()
        .expect("the typed maximum relationship depth is valid")
}

fn ready_result(workspace_root: &Path, status: Option<&RuntimeStatusResponse>) -> Option<UpResult> {
    let status = status?;
    semantic_status_ready(workspace_root, status).then(|| UpResult {
        root: workspace_root.display().to_string(),
        ready: true,
        runtime: "READY",
        backend: status.backend_name.clone(),
        reference_index_ready: status.reference_index_ready,
        source_module_count: status.source_module_names.len(),
        next: vec!["kast refresh", "kast files", "kast symbol find <query>"],
    })
}

fn semantic_status_ready(workspace_root: &Path, status: &RuntimeStatusResponse) -> bool {
    config::normalize(PathBuf::from(&status.workspace_root))
        == config::normalize(workspace_root.to_path_buf())
        && status.state == RuntimeState::Ready
        && status.healthy
        && status.active
        && !status.indexing
}

fn runtime_state_name(state: &RuntimeState) -> &'static str {
    match state {
        RuntimeState::Starting => "STARTING",
        RuntimeState::Indexing => "INDEXING",
        RuntimeState::Ready => "READY",
        RuntimeState::Degraded => "DEGRADED",
    }
}
