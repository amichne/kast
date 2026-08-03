#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PublicMutationLeaseReceipt<'a> {
    state: WorkspaceLeaseState,
    ownership: WorkspaceLeaseOwnership,
    release_receipt: &'a WorkspaceLeaseReleaseReceipt,
}

impl<'a> From<&'a MutationLeaseReceipt> for PublicMutationLeaseReceipt<'a> {
    fn from(receipt: &'a MutationLeaseReceipt) -> Self {
        Self {
            state: receipt.state,
            ownership: receipt.ownership,
            release_receipt: &receipt.release_receipt,
        }
    }
}

#[derive(Serialize)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum PublicTerminalMutationReceipt<'a> {
    Verified {
        plan_id: &'a str,
        recovery_id: &'a str,
        workspace_root: &'a str,
        operation: &'a str,
        files: &'a [VerifiedMutationFile],
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: &'a CompilerVerificationEvidence,
        lease: PublicMutationLeaseReceipt<'a>,
        schema_version: u32,
    },
    Rejected {
        plan_id: &'a str,
        recovery_id: &'a str,
        workspace_root: &'a str,
        operation: &'a str,
        reason: &'a str,
        schema_version: u32,
    },
    Conflicted {
        plan_id: &'a str,
        recovery_id: &'a str,
        workspace_root: &'a str,
        operation: &'a str,
        reason: &'a str,
        schema_version: u32,
    },
    RolledBack {
        plan_id: &'a str,
        recovery_id: &'a str,
        workspace_root: &'a str,
        operation: &'a str,
        reason: &'a str,
        schema_version: u32,
    },
}

impl<'a> From<&'a TerminalMutationReceipt> for PublicTerminalMutationReceipt<'a> {
    fn from(receipt: &'a TerminalMutationReceipt) -> Self {
        match receipt {
            TerminalMutationReceipt::Verified {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                files,
                diagnostics,
                compiler_verification,
                lease,
                schema_version,
            } => Self::Verified {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                files,
                diagnostics: *diagnostics,
                compiler_verification,
                lease: PublicMutationLeaseReceipt::from(lease.as_ref()),
                schema_version: *schema_version,
            },
            TerminalMutationReceipt::Rejected {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                reason,
                schema_version,
            } => Self::Rejected {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                reason,
                schema_version: *schema_version,
            },
            TerminalMutationReceipt::Conflicted {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                reason,
                schema_version,
            } => Self::Conflicted {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                reason,
                schema_version: *schema_version,
            },
            TerminalMutationReceipt::RolledBack {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                reason,
                schema_version,
            } => Self::RolledBack {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                reason,
                schema_version: *schema_version,
            },
        }
    }
}
