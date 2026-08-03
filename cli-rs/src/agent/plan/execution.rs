pub(crate) fn run_change(args: KastChangeArgs) -> Result<i32> {
    let workspace_root = canonical_workspace_root()?;
    let requested = RequestedOperation::from(args.command);
    let content = requested.requires_content().then(read_stdin).transpose()?;
    let plan_id = Uuid::new_v4();
    let paths = PlanPaths::new(plan_id);
    ensure_private_directory(&paths.directory)?;

    let preview_content_path = match content.as_deref() {
        Some(content) => {
            write_private_file(&paths.preview_content, content)?;
            Some(paths.preview_content.as_path())
        }
        None => None,
    };
    let preview = match agent_adapter::projected_value(requested.command(
        workspace_root.clone(),
        preview_content_path,
        false,
        None,
        None,
    )?) {
        Ok(preview) => preview,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    if preview.get("ok") != Some(&Value::Bool(true)) {
        remove_if_exists(&paths.preview_content);
        return agent_adapter::print_projected_value(preview);
    }
    let preview_result = match projected_result(&preview) {
        Ok(result) => result,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    let operation = match requested.into_stored(preview_result) {
        Ok(operation) => operation,
        Err(error) => {
            remove_if_exists(&paths.preview_content);
            return Err(error);
        }
    };
    let public_plan = public_plan(preview_result);
    let content_sha256 = content.as_deref().map(manifest::sha256_bytes);
    if let Err(message) = operation.validate_content_sha256(content_sha256.as_deref()) {
        remove_if_exists(&paths.preview_content);
        return Err(CliError::new("KAST_INVALID_AGENT_RESULT", message));
    }

    if content.is_some()
        && let Err(error) = rename_private_file(&paths.preview_content, &paths.content)
    {
        remove_if_exists(&paths.preview_content);
        return Err(error);
    }
    let stored = StoredPlan {
        schema_version: PLAN_SCHEMA_VERSION,
        plan_id,
        workspace_root: workspace_root.display().to_string(),
        operation,
        content_sha256,
        state: StoredPlanState::Planned,
    };
    if let Err(error) = write_plan(&paths.plan, &stored) {
        remove_if_exists(&paths.content);
        return Err(error);
    }

    let result = ChangeResult {
        plan_id: plan_id.hyphenated().to_string(),
        operation: stored.operation.name(),
        plan: public_plan,
        next: format!("kast apply {}", plan_id.hyphenated()),
    };
    output::print_structured(&result, crate::cli::OutputFormat::Toon)?;
    Ok(0)
}

pub(crate) fn run_apply(raw_plan_id: String) -> Result<i32> {
    let plan_id = parse_plan_id(&raw_plan_id)?;
    let paths = PlanPaths::new(plan_id);
    let _operation_lock = PlanOperationLock::acquire(&paths.lock)?;
    let mut plan = read_plan(&paths.plan, plan_id)?;
    let workspace_root = require_current_workspace(&plan, plan_id)?;
    if let StoredPlanState::Terminal { receipt } = &plan.state {
        return print_terminal_receipt(receipt);
    }
    if paths.recovery.exists() {
        read_recovery(&paths.recovery, plan_id, &plan)?;
        return print_recovery_required(
            &plan,
            "This plan already has a durable recovery journal; use `kast recover` before retrying apply.",
        );
    }

    let content = if plan.operation.requires_content() {
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
        Some(contents)
    } else {
        if plan.content_sha256.is_some() || paths.content.exists() {
            return Err(CliError::new(
                "KAST_PLAN_INVALID",
                "The stored rename plan unexpectedly contains change content.",
            ));
        }
        None
    };
    let lease = OwnedMutationLease::acquire(&workspace_root)?;
    let lease_id = lease.id();
    let prewrite: Result<_> = (|| {
        let transitions = plan.operation.transitions(&workspace_root)?;
        let observations = observe_exact_transitions(
            &workspace_root,
            &transitions,
            lease_id.clone(),
            None,
        )?;
        if !observations
            .iter()
            .zip(&transitions)
            .all(|(observation, transition)| transition.matches_pre(observation))
        {
            return Ok(Err(transitions));
        }
        revalidate_persisted_authority(
            &plan,
            content.as_deref(),
            &workspace_root,
            lease_id.clone(),
        )?;
        let pre_files = transitions
            .iter()
            .filter_map(|transition| match &transition.preimage {
                ExactMutationPreimage::Absent => None,
                ExactMutationPreimage::Present { image } => Some(CompilerDiagnosticFileHash {
                    file_path: transition.absolute_path.clone(),
                    sha256: image.sha256().to_string(),
                }),
            })
            .collect::<Vec<_>>();
        let pre_diagnostics = collect_complete_diagnostics(
            &workspace_root,
            &pre_files,
            lease_id.clone(),
        )?;
        Ok(Ok((transitions, pre_diagnostics)))
    })();
    let (transitions, pre_diagnostics) = match prewrite {
        Ok(Ok(value)) => value,
        Ok(Err(_)) => {
            if let Err(error) = lease.release() {
                return Err(prewrite_release_error(
                    "Apply observed a source conflict",
                    &error,
                ));
            }
            let receipt = TerminalMutationReceipt::conflicted(
                &plan,
                "At least one source path no longer matches its exact planned preimage.",
            );
            return finish_terminal_receipt(&paths, &mut plan, receipt);
        }
        Err(error) => {
            if let Err(release_error) = lease.release() {
                return Err(prewrite_release_error(&error.message, &release_error));
            }
            if is_definitive_revalidation_rejection(&error) {
                let receipt = TerminalMutationReceipt::rejected(&plan, error.message);
                return finish_terminal_receipt(&paths, &mut plan, receipt);
            }
            return Err(error);
        }
    };

    if MutationFailurePoint::BeforeJournal.active() {
        let _ = lease.release();
        return Err(CliError::new(
            "KAST_TEST_MUTATION_INTERRUPTED",
            "Apply stopped before its recovery journal became durable.",
        ));
    }
    let mut journal = RecoveryJournal::prepared(&plan, transitions, pre_diagnostics)?;
    if let Err(error) = write_recovery(&paths.recovery, &journal) {
        let _ = lease.release();
        return Err(error);
    }
    if MutationFailurePoint::AfterJournal.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after its recovery journal became durable.",
        );
    }

    let initial_scratch = match inspect_mutation_scratch(
        &workspace_root,
        &journal,
        lease_id.clone(),
    ) {
        Ok(result) => result,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if initial_scratch.has_blocker() || initial_scratch.owned_present() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Mutation scratch admission did not prove every predeclared role absent and safe.",
        );
    }
    let admitted_observations = match observe_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
        Some(journal.mutation_attempt_id),
    ) {
        Ok(observations) => observations,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if !admitted_observations
        .iter()
        .zip(&journal.transitions)
        .all(|(observation, transition)| transition.matches_pre(observation))
    {
        return stop_with_recovery_required(
            &plan,
            lease,
            "The exact source preimage changed after the mutation attempt became durable.",
        );
    }

    for index in 0..journal.transitions.len() {
        let scratch = match journal.active_scratch(index) {
            Ok(scratch) => scratch.clone(),
            Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
        };
        if let Err(error) = apply_exact_transition(
            &workspace_root,
            &journal.transitions[index],
            lease_id.clone(),
            journal.mutation_attempt_id,
            &scratch,
        ) {
            let detail_failure = journal
                .validate_backend_recovery_details(&error)
                .err()
                .map(|failure| failure.message);
            let mut reason = format!(
                "An exact transition may have written before it failed: {}.",
                error.message
            );
            if let Some(failure) = detail_failure {
                reason.push_str(" Its backend recovery path details were invalid: ");
                reason.push_str(&failure);
                reason.push('.');
            }
            if let Ok(inspection) =
                inspect_mutation_scratch(&workspace_root, &journal, lease_id.clone())
                && (inspection.has_blocker() || inspection.owned_present())
            {
                reason.push_str(" Gated inspection found retained mutation scratch.");
            }
            return stop_with_recovery_required(&plan, lease, reason);
        }
        let inspection = match inspect_mutation_scratch(
            &workspace_root,
            &journal,
            lease_id.clone(),
        ) {
            Ok(result) => result,
            Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
        };
        if inspection.has_blocker()
            || inspection.owned_present()
            || !inspection.scratch_is_absent(&scratch)
        {
            return stop_with_recovery_required(
                &plan,
                lease,
                "A completed exact write did not leave every owned scratch role absent and safe.",
            );
        }
        if let Err(error) = journal.remove_scratch(&scratch) {
            return stop_with_recovery_required(&plan, lease, error.message);
        }
        journal.state = RecoveryJournalState::Writing {
            completed_transition_count: index + 1,
        };
        if let Err(error) = replace_recovery(&paths.recovery, &journal) {
            return stop_with_recovery_required(
                &plan,
                lease,
                format!("A completed write could not be checkpointed: {}.", error.message),
            );
        }
        if MutationFailurePoint::AfterWrite(index + 1).active() {
            return stop_with_recovery_required(
                &plan,
                lease,
                format!("Apply stopped after exact transition {}.", index + 1),
            );
        }
    }
    journal.state = RecoveryJournalState::WritesApplied;
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterAllWrites.active() {
        return stop_with_recovery_required(&plan, lease, "Apply stopped after all exact writes.");
    }
    let post_observations = match observe_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
        Some(journal.mutation_attempt_id),
    ) {
        Ok(observations) => observations,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if !post_observations
        .iter()
        .zip(&journal.transitions)
        .all(|(observation, transition)| transition.matches_post(observation))
    {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Exact observation did not prove every planned postimage after writes.",
        );
    }

    let refresh = match refresh_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
    ) {
        Ok(refresh) => refresh,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    journal.state = RecoveryJournalState::Refreshed;
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterRefresh.active() {
        return stop_with_recovery_required(&plan, lease, "Apply stopped after compiler refresh.");
    }

    let post_files = journal
        .transitions
        .iter()
        .map(|transition| CompilerDiagnosticFileHash {
            file_path: transition.absolute_path.clone(),
            sha256: transition.postimage.sha256().to_string(),
        })
        .collect::<Vec<_>>();
    let post_diagnostics = match collect_complete_diagnostics(
        &workspace_root,
        &post_files,
        lease_id.clone(),
    ) {
        Ok(diagnostics) => diagnostics,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    let deltas = match compare_diagnostic_snapshots(&journal.pre_diagnostics, &post_diagnostics) {
        Ok(deltas) => deltas,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    journal.state = RecoveryJournalState::DiagnosticsVerified;
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterDiagnostics.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after exact diagnostic comparison.",
        );
    }

    let semantic_postcondition = match verify_mutation_postcondition(
        &workspace_root,
        &plan.operation,
        &journal.transitions,
        lease_id.clone(),
    ) {
        Ok(evidence) => evidence,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    let compiler_verification = CompilerVerificationEvidence {
        pre_diagnostics: journal.pre_diagnostics.clone(),
        refresh,
        analysis: CompilerAnalysisEvidence {
            outcome: CompleteCompilerAnalysis::Complete,
            post_diagnostics: post_diagnostics.clone(),
            deltas,
        },
        semantic_postcondition,
    };
    journal.state = RecoveryJournalState::SemanticVerified {
        compiler_verification: Box::new(compiler_verification.clone()),
    };
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterSemanticVerification.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after compiler-backed semantic verification.",
        );
    }
    let terminal_scratch = match inspect_mutation_scratch(
        &workspace_root,
        &journal,
        lease_id,
    ) {
        Ok(inspection) => inspection,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if !journal.owned_scratch.is_empty()
        || terminal_scratch.has_blocker()
        || terminal_scratch.owned_present()
    {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Terminal verification found unresolved owned, unowned, or unsafe mutation scratch.",
        );
    }
    let files = journal
        .transitions
        .iter()
        .map(ExactMutationTransition::verified_file)
        .collect::<Vec<_>>();
    let diagnostics = post_diagnostics.verified_diagnostics();
    journal.state = RecoveryJournalState::DurableVerifiedEvidence {
        files: files.clone(),
        diagnostics,
        compiler_verification: Box::new(compiler_verification.clone()),
    };
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterDurableEvidence.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after verified evidence became durable.",
        );
    }
    let lease_receipt = match lease.release() {
        Ok(receipt) => receipt,
        Err(error) => {
            return print_recovery_required(
                &plan,
                format!("Apply verified source state but could not release its lease: {}.", error.message),
            );
        }
    };
    journal.state = RecoveryJournalState::VerifiedEvidence {
        files: files.clone(),
        diagnostics,
        compiler_verification: Box::new(compiler_verification.clone()),
        lease: lease_receipt.clone(),
    };
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return print_recovery_required(
            &plan,
            format!("Released verified evidence could not be made durable: {}.", error.message),
        );
    }
    let receipt = TerminalMutationReceipt::verified(
        &plan,
        files,
        diagnostics,
        compiler_verification,
        lease_receipt,
    );
    finish_terminal_receipt(&paths, &mut plan, receipt)
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
