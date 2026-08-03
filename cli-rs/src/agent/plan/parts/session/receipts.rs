#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredPlanState {
    Planned,
    Terminal {
        receipt: Box<TerminalMutationReceipt>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum TerminalMutationReceipt {
    Verified {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        files: Vec<VerifiedMutationFile>,
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: Box<CompilerVerificationEvidence>,
        lease: Box<MutationLeaseReceipt>,
        schema_version: u32,
    },
    Rejected {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
    Conflicted {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
    RolledBack {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
}

impl TerminalMutationReceipt {
    fn verified(
        plan: &StoredPlan,
        files: Vec<VerifiedMutationFile>,
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: CompilerVerificationEvidence,
        lease: MutationLeaseReceipt,
    ) -> Self {
        Self::Verified {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            files,
            diagnostics,
            compiler_verification: Box::new(compiler_verification),
            lease: Box::new(lease),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn rejected(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::Rejected {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn conflicted(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::Conflicted {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn rolled_back(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::RolledBack {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn validate_for(&self, plan: &StoredPlan) -> Result<()> {
        let identity_matches = match self {
            Self::Verified {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            }
            | Self::Rejected {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            }
            | Self::Conflicted {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            }
            | Self::RolledBack {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            } => {
                plan_id == &plan.plan_id.hyphenated().to_string()
                    && recovery_id == plan_id
                    && workspace_root == &plan.workspace_root
                    && operation == plan.operation.name()
                    && *schema_version == crate::SCHEMA_VERSION
            }
        };
        if !identity_matches {
            return Err(CliError::new(
                "KAST_PLAN_INVALID",
                "The stored terminal receipt identity does not match its plan.",
            ));
        }
        match self {
            Self::Verified {
                files,
                diagnostics,
                compiler_verification,
                lease,
                ..
            } => {
                let transitions = plan.operation.transitions(Path::new(&plan.workspace_root))?;
                let expected_files = transitions
                    .iter()
                    .map(ExactMutationTransition::verified_file)
                    .collect::<Vec<_>>();
                compiler_verification.validate_for(&plan.operation, &transitions)?;
                if files != &expected_files
                    || diagnostics
                        != &compiler_verification
                            .analysis
                            .post_diagnostics
                            .verified_diagnostics()
                    || lease
                        .validate_for(plan.plan_id, Path::new(&plan.workspace_root))
                        .is_err()
                {
                    return Err(CliError::new(
                        "KAST_PLAN_INVALID",
                        "The stored VERIFIED receipt does not bind exact files, diagnostics, compiler proof, and released lease evidence.",
                    ));
                }
            }
            Self::Rejected { reason, .. }
            | Self::Conflicted { reason, .. }
            | Self::RolledBack { reason, .. }
                if reason.trim().is_empty() =>
            {
                return Err(CliError::new(
                    "KAST_PLAN_INVALID",
                    "A non-verified terminal receipt needs one non-blank reason.",
                ));
            }
            Self::Rejected { .. } | Self::Conflicted { .. } | Self::RolledBack { .. } => {}
        }
        Ok(())
    }

    fn exit_code(&self) -> i32 {
        match self {
            Self::Verified { .. } => 0,
            Self::Rejected { .. }
            | Self::Conflicted { .. }
            | Self::RolledBack { .. } => 1,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum RecoveryRequiredReceipt {
    RecoveryRequired {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
}

impl RecoveryRequiredReceipt {
    fn new(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::RecoveryRequired {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }
}
