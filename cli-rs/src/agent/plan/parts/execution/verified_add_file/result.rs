#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum VerifiedAddFileApplyResult {
    Verified {
        plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
        plan_version: VerifiedAddFilePlanVersion,
        operation: VerifiedAddFileOperation,
        publication: VerifiedAddFilePublication,
        identity: VerifiedAddFileIdentity,
        postimage_sha256: VerifiedAddFileSha256,
        schema_version: u32,
    },
    Rejected {
        plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
        plan_version: VerifiedAddFilePlanVersion,
        stage: VerifiedAddFilePlanStage,
        progress: VerifiedAddFileProgress,
        failure: VerifiedAddFileFailure,
        operation: VerifiedAddFileOperation,
        schema_version: u32,
    },
    RolledBack {
        plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
        plan_version: VerifiedAddFilePlanVersion,
        stage: VerifiedAddFilePlanStage,
        progress: VerifiedAddFileProgress,
        failure: VerifiedAddFileFailure,
        recovery_action: VerifiedAddFileRecoveryAction,
        operation: VerifiedAddFileOperation,
        schema_version: u32,
    },
    RecoveryRequired {
        plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
        recovery_id: crate::agent::public_protocol::VerifiedAddFileRecoveryId,
        plan_version: VerifiedAddFilePlanVersion,
        stage: VerifiedAddFilePlanStage,
        progress: VerifiedAddFileProgress,
        failure: VerifiedAddFileFailure,
        recovery_action: VerifiedAddFileRecoveryAction,
        operation: VerifiedAddFileOperation,
        schema_version: u32,
    },
    ReconciliationRequired {
        plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
        recovery_id: crate::agent::public_protocol::VerifiedAddFileRecoveryId,
        plan_version: VerifiedAddFilePlanVersion,
        stage: VerifiedAddFilePlanStage,
        progress: VerifiedAddFileProgress,
        failure: VerifiedAddFileFailure,
        reconciliation_action: VerifiedAddFileReconciliationAction,
        operation: VerifiedAddFileOperation,
        schema_version: u32,
    },
}

macro_rules! stored_add_file_result {
    ($name:ident, $($variant:ident)|+) => {
        #[derive(Clone, Debug, Deserialize, Serialize)]
        #[serde(try_from = "VerifiedAddFileApplyResult", into = "VerifiedAddFileApplyResult")]
        struct $name(VerifiedAddFileApplyResult);

        impl TryFrom<VerifiedAddFileApplyResult> for $name {
            type Error = &'static str;

            fn try_from(result: VerifiedAddFileApplyResult) -> std::result::Result<Self, Self::Error> {
                matches!(result, $(VerifiedAddFileApplyResult::$variant { .. })|+)
                    .then_some(Self(result))
                    .ok_or("The stored add-file state contained an incompatible result variant.")
            }
        }

        impl From<$name> for VerifiedAddFileApplyResult {
            fn from(result: $name) -> Self {
                result.0
            }
        }

        impl $name {
            fn as_result(&self) -> &VerifiedAddFileApplyResult {
                &self.0
            }

            fn admit(&self, plan: &StoredVerifiedAddFilePlan) -> Result<Self> {
                Self::try_from(self.0.clone().admit(plan)?).map_err(|message| {
                    CliError::new("KAST_PLAN_INVALID", message)
                })
            }
        }
    };
}

stored_add_file_result!(StoredRecoveryAddFileResult, RecoveryRequired);
stored_add_file_result!(StoredReconciliationAddFileResult, ReconciliationRequired);
stored_add_file_result!(StoredTerminalAddFileResult, Verified | RolledBack);

struct AdmittedVerifiedAddFileResultPersistence {
    result: VerifiedAddFileApplyResult,
    state: StoredVerifiedAddFileState,
}

impl AdmittedVerifiedAddFileResultPersistence {
    fn from_result(result: VerifiedAddFileApplyResult) -> Result<Self> {
        if result.is_verified() || result.is_rolled_back() {
            return StoredTerminalAddFileResult::try_from(result)
                .map(|stored| Self {
                    result: stored.as_result().clone(),
                    state: StoredVerifiedAddFileState::Terminal { result: stored },
                })
                .map_err(|message| CliError::new("KAST_VERIFIED_ADD_FILE_RESULT_INVALID", message));
        }
        if result.is_recovery_required() {
            return StoredRecoveryAddFileResult::try_from(result)
                .map(|stored| Self {
                    result: stored.as_result().clone(),
                    state: StoredVerifiedAddFileState::RecoveryRequired { result: stored },
                })
                .map_err(|message| CliError::new("KAST_VERIFIED_ADD_FILE_RESULT_INVALID", message));
        }
        if result.is_reconciliation_required() {
            return StoredReconciliationAddFileResult::try_from(result)
                .map(|stored| Self {
                    result: stored.as_result().clone(),
                    state: StoredVerifiedAddFileState::ReconciliationRequired { result: stored },
                })
                .map_err(|message| CliError::new("KAST_VERIFIED_ADD_FILE_RESULT_INVALID", message));
        }
        if matches!(&result, VerifiedAddFileApplyResult::Rejected { .. }) {
            return Ok(Self {
                result,
                state: StoredVerifiedAddFileState::AwaitingApproval,
            });
        }
        Err(CliError::new(
            "KAST_VERIFIED_ADD_FILE_RESULT_INVALID",
            "The server result did not map to one closed persisted add-file state.",
        ))
    }

    fn into_parts(self) -> (VerifiedAddFileApplyResult, StoredVerifiedAddFileState) {
        (self.result, self.state)
    }
}

impl VerifiedAddFileApplyResult {
    fn admit(self, plan: &StoredVerifiedAddFilePlan) -> Result<Self> {
        let valid = match &self {
            Self::Verified {
                plan_id,
                plan_version,
                publication,
                identity,
                postimage_sha256,
                schema_version,
                ..
            } => {
                plan_id == &plan.plan_id
                    && plan_version.value() == VERIFIED_ADD_FILE_TERMINAL_VERSION
                    && identity.target_path == plan.target_path.as_str()
                    && publication.generation > plan.planned_generation
                    && !identity.declarations.is_empty()
                    && identity
                        .declarations
                        .iter()
                        .all(|declaration| !declaration.name.trim().is_empty())
                    && postimage_sha256 == &plan.postimage_sha256
                    && *schema_version == crate::SCHEMA_VERSION
            }
            Self::Rejected {
                plan_id,
                plan_version,
                stage,
                progress,
                failure,
                schema_version,
                ..
            } => {
                plan_id == &plan.plan_id
                    && plan_version.value() == VERIFIED_ADD_FILE_INITIAL_VERSION
                    && stage_matches_progress(*stage, *progress)
                    && rejected_failure_matches(*progress, *failure)
                    && *schema_version == crate::SCHEMA_VERSION
            }
            Self::RolledBack {
                plan_id,
                plan_version,
                stage,
                progress,
                failure,
                schema_version,
                ..
            } => {
                plan_id == &plan.plan_id
                    && plan_version.value() == VERIFIED_ADD_FILE_TERMINAL_VERSION
                    && stage_matches_progress(*stage, *progress)
                    && recovery_failure_matches(*progress, *failure)
                    && *schema_version == crate::SCHEMA_VERSION
            }
            Self::RecoveryRequired {
                plan_id,
                recovery_id,
                plan_version,
                stage,
                progress,
                failure,
                schema_version,
                ..
            }
            | Self::ReconciliationRequired {
                plan_id,
                recovery_id,
                plan_version,
                stage,
                progress,
                failure,
                schema_version,
                ..
            } => {
                plan_id == &plan.plan_id
                    && recovery_id
                        == &crate::agent::public_protocol::VerifiedAddFileRecoveryId::from_plan_id(
                            &plan.plan_id,
                        )
                    && plan_version.value() == VERIFIED_ADD_FILE_INITIAL_VERSION
                    && stage_matches_progress(*stage, *progress)
                    && recovery_failure_matches(*progress, *failure)
                    && *schema_version == crate::SCHEMA_VERSION
            }
        };
        valid.then_some(self).ok_or_else(|| {
            CliError::new(
                "KAST_VERIFIED_ADD_FILE_RESULT_INVALID",
                "The server result did not preserve the exact persisted add-file authority.",
            )
        })
    }

    fn recovery_id(&self) -> Option<&crate::agent::public_protocol::VerifiedAddFileRecoveryId> {
        match self {
            Self::RecoveryRequired { recovery_id, .. }
            | Self::ReconciliationRequired { recovery_id, .. } => Some(recovery_id),
            _ => None,
        }
    }

    fn is_verified(&self) -> bool {
        matches!(self, Self::Verified { .. })
    }

    fn is_rolled_back(&self) -> bool {
        matches!(self, Self::RolledBack { .. })
    }

    fn is_recovery_required(&self) -> bool {
        matches!(self, Self::RecoveryRequired { .. })
    }

    fn is_reconciliation_required(&self) -> bool {
        matches!(self, Self::ReconciliationRequired { .. })
    }

    fn exit_code(&self) -> i32 {
        i32::from(!self.is_verified())
    }
}

fn stage_matches_progress(
    stage: VerifiedAddFilePlanStage,
    progress: VerifiedAddFileProgress,
) -> bool {
    matches!(
        (stage, progress),
        (
            VerifiedAddFilePlanStage::AwaitingApproval,
            VerifiedAddFileProgress::IntentAdmission | VerifiedAddFileProgress::Planning
        ) | (
            VerifiedAddFilePlanStage::Approved,
            VerifiedAddFileProgress::Revalidation
        ) | (
            VerifiedAddFilePlanStage::RecoveryPrepared,
            VerifiedAddFileProgress::RecoveryPreparation
        ) | (
            VerifiedAddFilePlanStage::ApplyAdmitted,
            VerifiedAddFileProgress::SourceApplication
        ) | (
            VerifiedAddFilePlanStage::AppliedUnverified,
            VerifiedAddFileProgress::WorkspacePublication
                | VerifiedAddFileProgress::PsiAdmission
        )
    )
}

fn rejected_failure_matches(
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
) -> bool {
    matches!(
        (progress, failure),
        (
            VerifiedAddFileProgress::IntentAdmission,
            VerifiedAddFileFailure::WorkspaceMismatch | VerifiedAddFileFailure::PlanNotFound
        ) | (
            VerifiedAddFileProgress::Planning,
            VerifiedAddFileFailure::TargetAlreadyExists
                | VerifiedAddFileFailure::TargetGenerated
                | VerifiedAddFileFailure::TargetAmbiguouslyOwned
                | VerifiedAddFileFailure::TargetSymlinkEscape
                | VerifiedAddFileFailure::PackageOrDeclarationInvalid
                | VerifiedAddFileFailure::Cancelled
        ) | (
            VerifiedAddFileProgress::Revalidation,
            VerifiedAddFileFailure::StalePlanVersion
                | VerifiedAddFileFailure::ApprovalRejected
                | VerifiedAddFileFailure::PlanRevalidationFailed
                | VerifiedAddFileFailure::Cancelled
        ) | (
            VerifiedAddFileProgress::RecoveryPreparation,
            VerifiedAddFileFailure::TargetAlreadyExists
                | VerifiedAddFileFailure::TargetNotWritable
                | VerifiedAddFileFailure::TargetSymlinkEscape
                | VerifiedAddFileFailure::PlanNotFound
        ) | (
            VerifiedAddFileProgress::SourceApplication,
            VerifiedAddFileFailure::VcsWritePromptRejected
        )
    )
}

fn recovery_failure_matches(
    progress: VerifiedAddFileProgress,
    failure: VerifiedAddFileFailure,
) -> bool {
    matches!(
        (progress, failure),
        (
            VerifiedAddFileProgress::SourceApplication,
            VerifiedAddFileFailure::SourceApplicationFailed | VerifiedAddFileFailure::Cancelled
        ) | (
            VerifiedAddFileProgress::WorkspacePublication,
            VerifiedAddFileFailure::PublicationFailed | VerifiedAddFileFailure::Cancelled
        ) | (
            VerifiedAddFileProgress::PsiAdmission,
            VerifiedAddFileFailure::PsiNotAdmitted
                | VerifiedAddFileFailure::GenerationNotAdvanced
                | VerifiedAddFileFailure::Cancelled
        )
    )
}
