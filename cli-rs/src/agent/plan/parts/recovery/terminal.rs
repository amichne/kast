fn require_current_workspace(plan: &StoredPlan, plan_id: Uuid) -> Result<PathBuf> {
    let workspace_root = canonical_workspace_root()?;
    if plan.workspace_root != workspace_root.display().to_string() {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            format!(
                "Plan {plan_id} belongs to {}, not {}.",
                plan.workspace_root,
                workspace_root.display()
            ),
        ));
    }
    Ok(workspace_root)
}

fn finish_terminal_receipt(
    paths: &PlanPaths,
    plan: &mut StoredPlan,
    receipt: TerminalMutationReceipt,
) -> Result<i32> {
    receipt.validate_for(plan)?;
    let journal = paths
        .recovery
        .exists()
        .then(|| read_recovery(&paths.recovery, plan.plan_id, plan))
        .transpose()?;
    if let Some(journal) = &journal {
        if !journal.owned_scratch.is_empty() {
            return print_recovery_required(
                plan,
                "A terminal receipt is forbidden while journal-owned mutation scratch remains unresolved.",
            );
        }
        journal.validate_verified_terminal_evidence(&receipt)?;
    } else if matches!(receipt, TerminalMutationReceipt::Verified { .. }) {
        return Err(terminal_recovery_evidence_mismatch());
    }
    plan.state = StoredPlanState::Terminal {
        receipt: Box::new(receipt.clone()),
    };
    let persistence = if terminal_receipt_persistence_failure_active() {
        Err(CliError::new(
            "KAST_TEST_TERMINAL_RECEIPT_PERSISTENCE_FAILED",
            "Terminal receipt persistence failed at the deterministic test seam.",
        ))
    } else {
        replace_plan(&paths.plan, plan)
    };
    if let Err(error) = persistence {
        if paths.recovery.exists() {
            return print_recovery_required(
                plan,
                format!(
                    "Terminal source evidence is retained in the durable recovery journal, but the terminal receipt could not be persisted: {}.",
                    error.message
                ),
            );
        }
        return Err(error);
    }
    print_terminal_receipt(plan, &receipt)
}

fn replay_terminal_receipt(
    paths: &PlanPaths,
    plan: &StoredPlan,
    receipt: &TerminalMutationReceipt,
) -> Result<i32> {
    if matches!(receipt, TerminalMutationReceipt::Verified { .. }) {
        let journal = read_recovery(&paths.recovery, plan.plan_id, plan)?;
        journal.validate_verified_terminal_evidence(receipt)?;
    }
    print_terminal_receipt(plan, receipt)
}

fn terminal_receipt_persistence_failure_active() -> bool {
    cfg!(debug_assertions)
        && std::env::var("KAST_TEST_MUTATION_FAILURE_POINT")
            .is_ok_and(|value| value == "TERMINAL_RECEIPT_PERSISTENCE")
}

fn print_terminal_receipt(plan: &StoredPlan, receipt: &TerminalMutationReceipt) -> Result<i32> {
    let status = if matches!(receipt, TerminalMutationReceipt::Verified { .. }) {
        crate::agent::public_protocol::OperationStatus::Complete
    } else {
        crate::agent::public_protocol::OperationStatus::Rejected
    };
    print_plan_protocol(
        plan.runtime_output()?,
        status,
        &PublicTerminalMutationReceipt::from(receipt),
    )?;
    Ok(receipt.exit_code())
}

fn print_recovery_required(plan: &StoredPlan, reason: impl Into<String>) -> Result<i32> {
    let receipt = RecoveryRequiredReceipt::new(plan, reason);
    print_plan_protocol(
        plan.runtime_output()?,
        crate::agent::public_protocol::OperationStatus::Rejected,
        &receipt,
    )?;
    Ok(1)
}
