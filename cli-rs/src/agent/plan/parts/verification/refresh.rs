fn refresh_exact_transitions(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
    lease_id: AgentWorkspaceLeaseId,
) -> Result<CompilerRefreshEvidence> {
    let paths = transitions
        .iter()
        .map(|transition| transition.absolute_path.clone())
        .collect::<Vec<_>>();
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/workspace-refresh",
        json!({"filePaths": &paths}),
        LeasedRawOperation::ReadOnly,
    )?;
    let refresh: ProtocolRefreshEvidence = serde_json::from_value(raw).map_err(|error| {
        compiler_verification_error(format!("Compiler refresh evidence was malformed: {error}"))
    })?;
    let status_paths = refresh
        .file_statuses
        .iter()
        .map(|status| status.file_path.clone())
        .collect::<Vec<_>>();
    if refresh.semantic_outcome != ProtocolAnalysisOutcome::Complete
        || refresh.full_refresh
        || refresh.refreshed_files != paths
        || status_paths != paths
        || refresh
            .file_statuses
            .iter()
            .any(|status| !status.is_analyzed())
        || !refresh.removed_files.is_empty()
        || !refresh.external_failure_outcomes.is_empty()
        || !refresh.relationship_failures.is_empty()
        || refresh.removed_file_count != 0
        || refresh.requested_file_count != paths.len()
        || refresh.analyzed_file_count != paths.len()
        || refresh.skipped_file_count != 0
        || refresh.attempt_count == 0
        || refresh.schema_version != crate::SCHEMA_VERSION
    {
        return Err(compiler_verification_error(
            "Compiler refresh was incomplete or did not cover every exact transition.",
        ));
    }
    let _ = refresh.elapsed_millis;
    Ok(CompilerRefreshEvidence {
        outcome: CompleteCompilerAnalysis::Complete,
        file_paths: paths,
        requested_file_count: refresh.requested_file_count,
        analyzed_file_count: refresh.analyzed_file_count,
        skipped_file_count: refresh.skipped_file_count,
        attempt_count: refresh.attempt_count,
        schema_version: refresh.schema_version,
    })
}

fn refresh_restored_preimages(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
    lease_id: AgentWorkspaceLeaseId,
) -> Result<()> {
    let paths = transitions
        .iter()
        .map(|transition| transition.absolute_path.clone())
        .collect::<Vec<_>>();
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/workspace-refresh",
        json!({"filePaths": &paths}),
        LeasedRawOperation::ReadOnly,
    )?;
    let refresh: ProtocolRefreshEvidence = serde_json::from_value(raw).map_err(|error| {
        compiler_verification_error(format!(
            "Rollback compiler refresh evidence was malformed: {error}"
        ))
    })?;
    validate_restored_preimage_refresh(&refresh, transitions)
}

fn validate_restored_preimage_refresh(
    refresh: &ProtocolRefreshEvidence,
    transitions: &[ExactMutationTransition],
) -> Result<()> {
    let expected_paths = transitions
        .iter()
        .map(|transition| transition.absolute_path.clone())
        .collect::<Vec<_>>();
    let expected_refreshed = transitions
        .iter()
        .filter_map(|transition| match transition.preimage {
            ExactMutationPreimage::Present { .. } => Some(transition.absolute_path.clone()),
            ExactMutationPreimage::Absent => None,
        })
        .collect::<Vec<_>>();
    let expected_removed = transitions
        .iter()
        .filter_map(|transition| match transition.preimage {
            ExactMutationPreimage::Absent => Some(transition.absolute_path.clone()),
            ExactMutationPreimage::Present { .. } => None,
        })
        .collect::<Vec<_>>();
    let status_paths = refresh
        .file_statuses
        .iter()
        .map(|status| status.file_path.clone())
        .collect::<Vec<_>>();
    let status_matches = refresh
        .file_statuses
        .iter()
        .zip(transitions)
        .all(|(status, transition)| match transition.preimage {
            ExactMutationPreimage::Absent => status.is_removed(),
            ExactMutationPreimage::Present { .. } => status.is_analyzed(),
        });
    if refresh.semantic_outcome != ProtocolAnalysisOutcome::Complete
        || refresh.full_refresh
        || status_paths != expected_paths
        || !status_matches
        || refresh.refreshed_files != expected_refreshed
        || refresh.removed_files != expected_removed
        || !refresh.external_failure_outcomes.is_empty()
        || !refresh.relationship_failures.is_empty()
        || refresh.requested_file_count != expected_refreshed.len()
        || refresh.analyzed_file_count != expected_refreshed.len()
        || refresh.skipped_file_count != 0
        || refresh.removed_file_count != expected_removed.len()
        || refresh.attempt_count == 0
        || refresh.schema_version != crate::SCHEMA_VERSION
    {
        return Err(compiler_verification_error(
            "Rollback refresh did not prove every restored source admission or exact removal.",
        ));
    }
    let _ = refresh.elapsed_millis;
    Ok(())
}
