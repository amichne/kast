use crate::agent::{
    AgentAddDeclarationAuthority, AgentAddDeclarationPlanResult, AgentAddFileAuthority,
    AgentAddFilePlanResult, AgentExactByteImage, AgentExactFileImage,
    AgentExactFileImageCasRequest, AgentExactFileImageCasResponse,
    AgentMutationPostconditionAuthority, AgentMutationPostconditionEvidence,
    AgentMutationScratchSet, AgentRenameAuthority, AgentRenamePreview, AgentReplacementAuthority,
    AgentReplacementPlanResult, BACKEND_RECOVERY_DETAILS_INVALID, LeasedRawOperation,
    execute_leased_raw_value,
};
use crate::agent_adapter;
use crate::cli::{
    AgentAddFileArgs, AgentCommand, AgentLeaseAccessArgs, AgentLeaseAcquireArgs,
    AgentMutationApplyArgs, AgentPlacementAnchor, AgentRenameArgs, AgentReplaceDeclarationArgs,
    AgentScopedMutationArgs, AgentWorkspaceLeaseId, KastChangeArgs, KastChangeCommand,
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
    Rename { symbol: String, new_name: String },
    AddFile { path: PathBuf },
    AddDeclaration { path: PathBuf },
    Replace { symbol: String },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChangeResult {
    plan_id: String,
    operation: &'static str,
    plan: Value,
    next: String,
}

include!("execution.rs");
include!("operation.rs");
include!("recovery.rs");
include!("session.rs");
include!("storage.rs");
include!("verification.rs");
