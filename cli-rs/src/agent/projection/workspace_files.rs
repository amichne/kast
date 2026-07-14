fn project_workspace_files_result(
    result: WorkspaceFilesResult,
    view: &AgentWorkspaceFilesViewArgs,
    matching: &[&WorkspaceInventoryFile],
    cardinality: AgentResultCardinality,
    kind_coverage: WorkspaceKindMatchCoverage,
    filter_coverage: WorkspaceCoverageDimension,
) -> Value {
    if view.count {
        return workspace_files_count_result(
            matching,
            cardinality,
            kind_coverage,
            filter_coverage,
        );
    }
    let mut value = serde_json::to_value(result).unwrap_or(Value::Null);
    if !view.fields.is_empty() {
        if let Some(object) = value.as_object_mut() {
            object.insert(
                "type".to_string(),
                Value::String("KAST_AGENT_WORKSPACE_FILES_SELECTION".to_string()),
            );
            if let Some(files) = object.get_mut("files").and_then(Value::as_array_mut) {
                for file in files {
                    let Some(record) = file.as_object_mut() else {
                        continue;
                    };
                    record.retain(|key, _| workspace_files_selected_key(key, &view.fields));
                }
            }
        }
    } else if (view.verbose || view.explain)
        && let Some(object) = value.as_object_mut()
    {
        object.insert(
            "view".to_string(),
            Value::String(if view.explain { "EXPLAIN" } else { "VERBOSE" }.to_string()),
        );
    }
    value
}

fn workspace_files_selected_key(key: &str, fields: &[AgentWorkspaceFilesField]) -> bool {
    fields.iter().any(|field| match field {
        AgentWorkspaceFilesField::Path => matches!(key, "filePath" | "relativePath"),
        AgentWorkspaceFilesField::Module => {
            matches!(key, "backendModules" | "indexedGradleProjects")
        }
        AgentWorkspaceFilesField::SourceSet => key == "sourceSets",
        AgentWorkspaceFilesField::Kind => key == "kind",
        AgentWorkspaceFilesField::Package => key == "package",
        AgentWorkspaceFilesField::Index => key == "sourceIndex",
        AgentWorkspaceFilesField::Drift => key == "drift",
        AgentWorkspaceFilesField::Dirty => key == "dirty",
        AgentWorkspaceFilesField::Evidence => key == "evidence",
    })
}

fn workspace_files_count_result(
    matching: &[&WorkspaceInventoryFile],
    cardinality: AgentResultCardinality,
    kind_coverage: WorkspaceKindMatchCoverage,
    filter_coverage: WorkspaceCoverageDimension,
) -> Value {
    let groups = |values: Vec<(&'static str, WorkspaceFileKind)>| {
        let mut counts = BTreeMap::<&'static str, (usize, bool)>::new();
        for (value, kind) in values {
            let exact = workspace_files_kind_group_is_exact(
                kind,
                kind_coverage,
                filter_coverage,
            );
            counts
                .entry(value)
                .and_modify(|(count, group_exact)| {
                    *count += 1;
                    *group_exact &= exact;
                })
                .or_insert((1, exact));
        }
        values_to_group_cardinalities(counts)
    };
    let kind = groups(
        matching
            .iter()
            .map(|file| {
                let kind = file.kind();
                let value = match kind {
                    WorkspaceFileKind::Source => "KOTLIN_SOURCE",
                    WorkspaceFileKind::Script => "KOTLIN_SCRIPT",
                };
                (value, kind)
            })
            .collect(),
    );
    let index = groups(
        matching
            .iter()
            .map(|file| {
                let value = match file.index_state() {
                    WorkspaceFileIndexState::Indexed => "INDEXED",
                    WorkspaceFileIndexState::MetadataUnavailable
                    | WorkspaceFileIndexState::Incompatible(_) => "UNKNOWN",
                    WorkspaceFileIndexState::NotApplicable => "NOT_APPLICABLE",
                };
                (value, file.kind())
            })
            .collect(),
    );
    let drift = groups(
        matching
            .iter()
            .map(|file| {
                let value = match file.drift() {
                    WorkspaceFileDrift::InSync => "NONE",
                    WorkspaceFileDrift::FilesystemOnly => "FILESYSTEM_ONLY",
                    WorkspaceFileDrift::IndexOnly => "INDEX_ONLY",
                    WorkspaceFileDrift::MissingOnDisk => "MISSING_ON_DISK",
                    WorkspaceFileDrift::Unknown => "UNKNOWN",
                    WorkspaceFileDrift::NotApplicable => "NOT_APPLICABLE",
                };
                (value, file.kind())
            })
            .collect(),
    );
    let dirty = groups(
        matching
            .iter()
            .map(|file| {
                let value = match file.dirty_state() {
                    WorkspaceFileDirtyState::Clean => "CLEAN",
                    WorkspaceFileDirtyState::Dirty => "DIRTY",
                    WorkspaceFileDirtyState::Unknown => "UNKNOWN",
                    WorkspaceFileDirtyState::NotApplicable => "NOT_APPLICABLE",
                };
                (value, file.kind())
            })
            .collect(),
    );
    json!({
        "type": "KAST_AGENT_WORKSPACE_FILES_COUNT",
        "ok": true,
        "cardinality": cardinality,
        "returnedCount": 0,
        "truncated": !cardinality.is_exact() || cardinality.known_minimum() > 0,
        "groupedCardinalities": {
            "kind": kind,
            "index": index,
            "drift": drift,
            "dirty": dirty,
        },
        "schemaVersion": SCHEMA_VERSION,
    })
}

fn values_to_group_cardinalities(
    counts: BTreeMap<&'static str, (usize, bool)>,
) -> Vec<Value> {
    counts
        .into_iter()
        .map(|(value, (count, exact))| {
            let cardinality = if exact {
                AgentResultCardinality::Exact { total_count: count }
            } else {
                AgentResultCardinality::KnownMinimum {
                    known_minimum_count: count,
                }
            };
            json!({"value": value, "cardinality": cardinality})
        })
        .collect()
}

fn workspace_files_kind_group_is_exact(
    kind: WorkspaceFileKind,
    kind_coverage: WorkspaceKindMatchCoverage,
    filter_coverage: WorkspaceCoverageDimension,
) -> bool {
    filter_coverage == WorkspaceCoverageDimension::Complete
        && match kind {
            WorkspaceFileKind::Source => kind_coverage.source(),
            WorkspaceFileKind::Script => kind_coverage.script(),
        } == Some(WorkspaceCoverageDimension::Complete)
}
