fn load_persisted_plan_content(plan: &StoredPlan, paths: &PlanPaths) -> Result<Option<Vec<u8>>> {
    if plan.operation.requires_content() {
        let contents = read_private_file(&paths.content, "KAST_PLAN_CONTENT_UNAVAILABLE")?;
        let expected = plan.content_sha256.as_deref().ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The stored content-bearing plan has no content digest.",
            )
        })?;
        if manifest::sha256_bytes(&contents) != expected {
            return Err(CliError::new(
                "KAST_PLAN_CONTENT_CHANGED",
                "The stored change content no longer matches the validated plan.",
            ));
        }
        Ok(Some(contents))
    } else {
        if plan.content_sha256.is_some() || paths.content.exists() {
            return Err(CliError::new(
                "KAST_PLAN_INVALID",
                "The stored rename plan unexpectedly contains change content.",
            ));
        }
        Ok(None)
    }
}

fn revalidate_persisted_authority(
    plan: &StoredPlan,
    content: Option<&[u8]>,
    workspace_root: &Path,
    lease_id: AgentWorkspaceLeaseId,
) -> Result<()> {
    let regenerated = match &plan.operation {
        StoredOperation::Rename { authority } => {
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/rename",
                json!({
                    "position": authority.target_position(),
                    "newName": authority.new_name(),
                    "dryRun": true,
                }),
                LeasedRawOperation::ReadOnly,
            )?;
            let preview: AgentRenamePreview = parse_closed_raw(raw, "rename revalidation")?;
            preview.validate().map_err(authority_revalidation_error)?;
            StoredOperation::Rename {
                authority: Box::new(preview.into_authority()),
            }
        }
        StoredOperation::Replace { authority } => {
            let proposed = required_private_utf8(content, "replacement")?;
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/plan-replacement",
                json!({
                    "target": authority.target_value(),
                    "proposedDeclaration": proposed,
                }),
                LeasedRawOperation::ReadOnly,
            )?;
            let preview: AgentReplacementPlanResult =
                parse_closed_raw(raw, "replacement revalidation")?;
            preview.validate().map_err(authority_revalidation_error)?;
            StoredOperation::Replace {
                authority: Box::new(preview.into_authority()),
            }
        }
        StoredOperation::AddFile { authority } => {
            let proposed = required_private_utf8(content, "add-file")?;
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/plan-add-file",
                json!({
                    "targetPath": authority.target_path(),
                    "proposedContent": proposed,
                }),
                LeasedRawOperation::ReadOnly,
            )?;
            let preview: AgentAddFilePlanResult = parse_closed_raw(raw, "add-file revalidation")?;
            preview
                .validate_for(authority.target_path(), proposed)
                .map_err(authority_revalidation_error)?;
            StoredOperation::AddFile {
                authority: Box::new(preview.into_authority()),
            }
        }
        StoredOperation::AddDeclaration { authority } => {
            let proposed = required_private_utf8(content, "add-declaration")?;
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/plan-add-declaration",
                json!({
                    "targetPath": authority.target_path(),
                    "expectedCurrentSha256": authority.expected_current_sha256(),
                    "proposedDeclaration": proposed,
                }),
                LeasedRawOperation::ReadOnly,
            )?;
            let preview: AgentAddDeclarationPlanResult =
                parse_closed_raw(raw, "add-declaration revalidation")?;
            preview
                .validate_for(
                    authority.target_path(),
                    authority.expected_current_sha256(),
                    proposed,
                )
                .map_err(authority_revalidation_error)?;
            StoredOperation::AddDeclaration {
                authority: Box::new(preview.into_authority()),
            }
        }
    };
    if plan.operation.authority_bytes()? != regenerated.authority_bytes()? {
        return Err(CliError::new(
            "KAST_MUTATION_AUTHORITY_CHANGED",
            "Revalidation did not reproduce the persisted typed mutation authority byte for byte.",
        ));
    }
    Ok(())
}

fn required_private_utf8<'a>(content: Option<&'a [u8]>, operation: &str) -> Result<&'a str> {
    std::str::from_utf8(content.ok_or_else(|| {
        CliError::new(
            "KAST_PLAN_CONTENT_UNAVAILABLE",
            format!("The stored {operation} authority requires private content."),
        )
    })?)
    .map_err(|_| {
        CliError::new(
            "KAST_PLAN_CONTENT_CHANGED",
            format!("The stored {operation} content is not exact UTF-8."),
        )
    })
}

fn parse_closed_raw<T: for<'de> Deserialize<'de>>(raw: Value, name: &str) -> Result<T> {
    serde_json::from_value(raw).map_err(|error| {
        CliError::new(
            "KAST_RAW_MUTATION_SESSION_INVALID",
            format!("The {name} response violated its closed typed contract: {error}"),
        )
    })
}

fn authority_revalidation_error(message: String) -> CliError {
    CliError::new("KAST_MUTATION_AUTHORITY_CHANGED", message)
}

fn is_definitive_revalidation_rejection(error: &CliError) -> bool {
    matches!(
        error.code,
        "KAST_MUTATION_REVALIDATION_REJECTED" | "KAST_MUTATION_AUTHORITY_CHANGED"
    )
}

fn prewrite_release_error(context: &str, release_error: &CliError) -> CliError {
    CliError::new(
        "KAST_MUTATION_LEASE_RELEASE_FAILED",
        format!(
            "{context}; no recovery journal or source write exists, but the owned lease could not be released: {}.",
            release_error.message
        ),
    )
}

fn fail_before_recovery_journal(lease: OwnedMutationLease, error: CliError) -> Result<i32> {
    match lease.release() {
        Ok(_) => Err(error),
        Err(release_error) => Err(prewrite_release_error(&error.message, &release_error)),
    }
}

fn recovery_namespace_may_be_occupied(path: &Path) -> bool {
    match fs::symlink_metadata(path) {
        Ok(_) => true,
        Err(error) => error.kind() != std::io::ErrorKind::NotFound,
    }
}

fn write_initial_recovery(path: &Path, journal: &RecoveryJournal) -> Result<()> {
    if MutationFailurePoint::RecoveryJournalPersistence.active() {
        return Err(CliError::new(
            "KAST_TEST_RECOVERY_JOURNAL_PERSISTENCE_FAILED",
            "Recovery journal persistence failed at the deterministic test seam.",
        ));
    }
    write_recovery(path, journal)
}

fn observe_exact_transitions(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
    lease_id: AgentWorkspaceLeaseId,
    mutation_attempt_id: Option<Uuid>,
) -> Result<Vec<RawExactFileObservation>> {
    transitions
        .iter()
        .map(|transition| {
            observe_exact_relative_path(
                workspace_root,
                &transition.relative_path,
                lease_id.clone(),
                mutation_attempt_id,
            )
        })
        .collect()
}

fn observe_exact_relative_path(
    workspace_root: &Path,
    relative_path: &str,
    lease_id: AgentWorkspaceLeaseId,
    mutation_attempt_id: Option<Uuid>,
) -> Result<RawExactFileObservation> {
    let params = if let Some(mutation_attempt_id) = mutation_attempt_id {
        json!({
            "filePath": relative_path,
            "mutationAttemptId": mutation_attempt_id.hyphenated().to_string(),
        })
    } else {
        json!({"filePath": relative_path})
    };
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/exact-file-observation",
        params,
        LeasedRawOperation::ReadOnly,
    )?;
    let observation: RawExactFileObservation =
        parse_closed_raw(raw, "exact-file observation")?;
    observation.validate_for(relative_path)?;
    Ok(observation)
}

fn apply_exact_transition(
    workspace_root: &Path,
    transition: &ExactMutationTransition,
    lease_id: AgentWorkspaceLeaseId,
    mutation_attempt_id: Uuid,
    scratch: &MutationScratchAuthority,
) -> Result<()> {
    match &transition.preimage {
        ExactMutationPreimage::Present { image } => {
            let request = AgentExactFileImageCasRequest::forward(
                transition.absolute_path.clone(),
                image,
                &transition.postimage,
            )
            .for_attempt(mutation_attempt_id, scratch.wire_set());
            let params = serde_json::to_value(&request)?;
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/exact-file-image-cas",
                params,
                LeasedRawOperation::ExactFileImageCas,
            )?;
            let response: AgentExactFileImageCasResponse =
                parse_closed_raw(raw, "exact-file CAS")?;
            response.validate_for(&request).map_err(|message| {
                CliError::new("KAST_EXACT_FILE_CAS_INVALID", message)
            })
        }
        ExactMutationPreimage::Absent => {
            let content = std::str::from_utf8(&transition.postimage.validate().map_err(|message| {
                CliError::new("KAST_PLAN_INVALID", message)
            })?)
            .map_err(|_| {
                CliError::new("KAST_PLAN_INVALID", "The add-file postimage is not UTF-8.")
            })?
            .to_string();
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/apply-edits",
                json!({
                    "mutationAttemptId": mutation_attempt_id.hyphenated().to_string(),
                    "edits": [],
                    "fileHashes": [],
                    "mutationScratchSets": [scratch.wire_set()],
                    "fileOperations": [{
                        "type": "CREATE_FILE",
                        "filePath": &transition.absolute_path,
                        "content": content,
                        "parentPolicy": "REQUIRE_EXISTING_PARENTS",
                    }],
                }),
                LeasedRawOperation::FileOperation,
            )?;
            let result: RawApplyEditsResult = parse_closed_raw(raw, "add-file create")?;
            if result.schema_version != crate::SCHEMA_VERSION
                || !result.applied.is_empty()
                || result.affected_files != [transition.absolute_path.clone()]
                || result.created_files != [transition.absolute_path.clone()]
                || !result.deleted_files.is_empty()
            {
                return Err(CliError::new(
                    "KAST_ADD_FILE_CREATE_INVALID",
                    "The raw add-file result did not bind one exact created path.",
                ));
            }
            Ok(())
        }
    }
}

fn verify_mutation_postcondition(
    workspace_root: &Path,
    operation: &StoredOperation,
    transitions: &[ExactMutationTransition],
    lease_id: AgentWorkspaceLeaseId,
) -> Result<MutationPostconditionResult> {
    let query = MutationPostconditionQuery {
        authority: operation.postcondition_authority(),
    };
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/verify-mutation-postcondition",
        serde_json::to_value(query)?,
        LeasedRawOperation::ReadOnly,
    )?;
    let result: MutationPostconditionResult =
        parse_closed_raw(raw, "mutation postcondition")?;
    result.validate_for(operation, transitions)?;
    Ok(result)
}

fn stop_with_recovery_required(
    plan: &StoredPlan,
    lease: OwnedMutationLease,
    reason: impl Into<String>,
) -> Result<i32> {
    let mut reason = reason.into();
    if let Err(error) = lease.release() {
        reason.push_str(" Lease release also failed: ");
        reason.push_str(&error.message);
        reason.push('.');
    }
    print_recovery_required(plan, reason)
}
