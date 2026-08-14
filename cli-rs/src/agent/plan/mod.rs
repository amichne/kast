use crate::agent::{
    AgentAddDeclarationAuthority, AgentAddDeclarationPlanResult, AgentAddFileAuthority,
    AgentExactByteImage, AgentExactFileImage, AgentExactFileImageCasRequest,
    AgentExactFileImageCasResponse, AgentMutationPostconditionAuthority,
    AgentMutationPostconditionEvidence, AgentMutationScratchSet, AgentRenameAuthority,
    AgentRenamePreview, AgentReplacementAuthority, AgentReplacementPlanResult,
    BACKEND_RECOVERY_DETAILS_INVALID, LeasedRawOperation, execute_leased_raw_value,
};
use crate::agent_adapter;
use crate::cli::{
    AgentAddFileArgs, AgentCommand, AgentLeaseAccessArgs, AgentLeaseAcquireArgs,
    AgentMutationApplyArgs, AgentPlacementAnchor, AgentRenameArgs, AgentReplaceDeclarationArgs,
    AgentScopedMutationArgs, AgentSelectorHandle, AgentWorkspaceLeaseId, KastChangePlanArgs,
    KastChangePlanCommand, OutputFormat,
};
use crate::error::{CliError, Result};
use crate::runtime::{WorkspaceLeaseOwnership, WorkspaceLeaseReleaseReceipt, WorkspaceLeaseState};
use crate::{config, manifest, output, runtime};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File, OpenOptions};
use std::io::{IsTerminal, Read, Write};
use std::path::{Path, PathBuf};
use uuid::{Uuid, Version};

const PLAN_SCHEMA_VERSION: u32 = 6;
const RECOVERY_SCHEMA_VERSION: u32 = 5;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredPlan {
    schema_version: u32,
    plan_id: Uuid,
    workspace_root: String,
    operation: StoredOperation,
    content_sha256: Option<String>,
    state: StoredPlanState,
    #[serde(skip)]
    runtime_output: Option<PlanOutputContext>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(
    tag = "operation",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredOperation {
    Rename {
        authority: Box<AgentRenameAuthority>,
    },
    AddFile {
        authority: Box<AgentAddFileAuthority>,
    },
    AddDeclaration {
        authority: Box<AgentAddDeclarationAuthority>,
    },
    Replace {
        authority: Box<AgentReplacementAuthority>,
    },
}

enum RequestedOperation {
    Rename { selector: String, new_name: String },
    AddFile { path: PathBuf },
    AddDeclaration { path: PathBuf },
    Replace { selector: String },
}

enum PreparedOperation {
    Rename {
        selector: crate::agent::public_protocol::SymbolSelector,
        new_name: String,
    },
    AddFile {
        path: PathBuf,
    },
    AddDeclaration {
        path: PathBuf,
    },
    Replace {
        selector: crate::agent::public_protocol::SymbolSelector,
    },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChangeResult {
    plan_id: String,
    operation: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    selector: Option<String>,
    plan: Value,
    next: String,
}

#[derive(Clone, Copy, Debug)]
struct PlanOutputContext {
    format: OutputFormat,
    operation: crate::agent::public_protocol::OperationId,
}

fn plan_output_context(
    format: OutputFormat,
    operation: crate::agent::public_protocol::OperationId,
) -> PlanOutputContext {
    PlanOutputContext { format, operation }
}

fn print_plan_protocol(
    context: PlanOutputContext,
    status: crate::agent::public_protocol::OperationStatus,
    value: &impl Serialize,
) -> Result<()> {
    let fields = serde_json::to_value(value)?
        .as_object()
        .cloned()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The mutation operation returned a non-object result.",
            )
        })?;
    let envelope = crate::agent::public_protocol::ProtocolEnvelope::projected(
        context.operation,
        status,
        fields,
    );
    output::print_structured(&envelope, context.format)
}

fn print_plan_rejection(context: PlanOutputContext, failure: &impl Serialize) -> Result<()> {
    let envelope = crate::agent::public_protocol::ProtocolEnvelope::projected_rejected(
        context.operation,
        failure,
    )?;
    output::print_structured(&envelope, context.format)
}

pub(crate) fn run_apply(raw: String, output_format: OutputFormat) -> Result<i32> {
    let plan_id = match crate::agent::public_protocol::PlanId::parse(&raw) {
        Ok(plan_id) => plan_id,
        Err(message) => {
            return agent_adapter::print_actionable_failure(
                crate::agent::public_protocol::OperationId::ChangeApply,
                "PLAN_ID_MALFORMED",
                message,
                "Use the plan ID returned by a `kast change plan` operation.",
                output_format,
            );
        }
    };
    run_apply_typed(plan_id, output_format)
}

pub(crate) fn run_recover(raw: String, output_format: OutputFormat) -> Result<i32> {
    let recovery_id = match crate::agent::public_protocol::RecoveryId::parse(&raw) {
        Ok(recovery_id) => recovery_id,
        Err(message) => {
            return agent_adapter::print_actionable_failure(
                crate::agent::public_protocol::OperationId::ChangeRecover,
                "RECOVERY_ID_MALFORMED",
                message,
                "Use the recovery ID returned by `kast change apply`.",
                output_format,
            );
        }
    };
    run_recover_typed(recovery_id, output_format)
}

include!("execution.rs");
include!("operation.rs");
include!("recovery.rs");
include!("session.rs");
include!("storage.rs");
include!("verification.rs");
